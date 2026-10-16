package com.piscessoft.sbl.db

/**
 * Portable schema for the SBL Core system. Types are intentionally conservative so the
 * same DDL can be applied on SQLite and PostgreSQL:
 *   UUID -> TEXT, TIMESTAMPTZ -> TEXT(ISO-8601), BIGINT -> INTEGER,
 *   NUMERIC -> TEXT(BigDecimal), BOOLEAN -> INTEGER(0/1), JSONB -> TEXT.
 *
 * Cross-references that would create circular FK definitions (locate<->contract) are kept
 * as plain TEXT columns with application-level integrity.
 */
object Schema {

    fun apply(db: Database) {
        // Collateral moved from per-contract to per-counterparty accounts: set aside the old
        // per-contract tables so the new DDL can create the account-level ones.
        for (t in LEGACY_COLLATERAL_TABLES) {
            if (hasColumn(db, t, "contract_id")) {
                check(!tableExists(db, "${t}_legacy")) { "Cannot migrate $t: ${t}_legacy already exists" }
                db.exec("ALTER TABLE $t RENAME TO ${t}_legacy")
            }
        }
        stripComments(DDL).split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { db.exec(it) }
        // Additive columns for databases created before these columns existed.
        addColumnIfMissing(db, "sbl_contract", "book_side", "TEXT NOT NULL DEFAULT 'LOAN_OUT'")
        addColumnIfMissing(db, "counterparty", "fee_share_pct", "TEXT")
        addColumnIfMissing(db, "counterparty", "rebate_spread_bps", "INTEGER")
        addColumnIfMissing(db, "fee_statement", "total_rebate_hkd", "TEXT NOT NULL DEFAULT '0'")
        addColumnIfMissing(db, "fee_statement", "net_amount_hkd", "TEXT")
        addColumnIfMissing(db, "designated_security", "issuer_id", "TEXT")
        addColumnIfMissing(db, "designated_security", "currency", "TEXT NOT NULL DEFAULT 'HKD'")
        if (addColumnIfMissing(db, "collateral_type_config", "nominal_valued", "INTEGER NOT NULL DEFAULT 0")) {
            db.update("""UPDATE collateral_type_config SET nominal_valued=1
                         WHERE collateral_type IN ('CASH_HKD','CASH_RMB','CASH_USD','BANK_GUAR')""")
        }
        seed(db)
        migrateLegacyCollateral(db)
    }

    private val LEGACY_COLLATERAL_TABLES = listOf("collateral_position", "margin_call", "collateral_mtm_history")

    /**
     * Copies per-contract collateral rows into the counterparty accounts (each contract's
     * borrower). Idempotent; the *_legacy tables are kept for reference.
     */
    private fun migrateLegacyCollateral(db: Database) {
        if (tableExists(db, "collateral_position_legacy")) db.update(
            """INSERT INTO collateral_position (position_id,counterparty_id,collateral_type,asset_id,quantity,
                   market_value_local,fx_rate_to_hkd,market_value_hkd,haircut_pct,adjusted_value_hkd,
                   last_mtm_date,status,margin_call_id,created_at,updated_at)
               SELECT p.position_id,k.borrower_id,p.collateral_type,p.asset_id,p.quantity,
                   p.market_value_local,p.fx_rate_to_hkd,p.market_value_hkd,p.haircut_pct,p.adjusted_value_hkd,
                   p.last_mtm_date,p.status,p.margin_call_id,p.created_at,p.updated_at
               FROM collateral_position_legacy p JOIN sbl_contract k ON k.contract_id=p.contract_id
               WHERE p.position_id NOT IN (SELECT position_id FROM collateral_position)""")
        if (tableExists(db, "margin_call_legacy")) db.update(
            """INSERT INTO margin_call (call_id,counterparty_id,call_date,borrowed_value_hkd,
                   required_collateral_hkd,current_collateral_hkd,deficit_hkd,coverage_ratio,deadline,status,
                   resolved_at,resolution_method,created_at)
               SELECT m.call_id,k.borrower_id,m.call_date,m.borrowed_value_hkd,
                   m.required_collateral_hkd,m.current_collateral_hkd,m.deficit_hkd,m.coverage_ratio,m.deadline,m.status,
                   m.resolved_at,m.resolution_method,m.created_at
               FROM margin_call_legacy m JOIN sbl_contract k ON k.contract_id=m.contract_id
               WHERE m.call_id NOT IN (SELECT call_id FROM margin_call)""")
        if (tableExists(db, "collateral_mtm_history_legacy")) db.update(
            """INSERT INTO contract_mtm_history (history_id,contract_id,mtm_date,closing_price,
                   borrowed_value_hkd,required_collateral_hkd,created_at)
               SELECT h.history_id,h.contract_id,h.mtm_date,h.closing_price,
                   h.borrowed_value_hkd,h.required_collateral_hkd,h.created_at
               FROM collateral_mtm_history_legacy h
               WHERE h.history_id NOT IN (SELECT history_id FROM contract_mtm_history)""")
    }

    private fun hasColumn(db: Database, table: String, column: String): Boolean =
        db.conn.metaData.getColumns(null, null, table, null).use { rs ->
            generateSequence { if (rs.next()) rs.getString("COLUMN_NAME") else null }
                .any { it.equals(column, ignoreCase = true) }
        }

    private fun tableExists(db: Database, table: String): Boolean =
        db.conn.metaData.getTables(null, null, table, null).use { rs ->
            generateSequence { if (rs.next()) rs.getString("TABLE_NAME") else null }
                .any { it.equals(table, ignoreCase = true) }
        }

    /** Returns true when the column was added. */
    private fun addColumnIfMissing(db: Database, table: String, column: String, definition: String): Boolean {
        val exists = hasColumn(db, table, column)
        if (!exists) db.exec("ALTER TABLE $table ADD COLUMN $column $definition")
        return !exists
    }

    /** Remove `--` line comments so semicolons inside comments don't split statements. */
    private fun stripComments(sql: String): String =
        sql.lineSequence().joinToString("\n") { line ->
            val idx = line.indexOf("--")
            if (idx >= 0) line.substring(0, idx) else line
        }

    private fun seed(db: Database) {
        val count = db.queryLong("SELECT COUNT(*) FROM collateral_type_config")
        if (count > 0L) return
        // HK-specific eligible collateral & haircuts (plan section 5.2). Nominal-valued types
        // (cash, guarantees) are valued at face: quantity is the amount, unit price 1.
        val rows = listOf(
            arrayOf("CASH_HKD", "HKD cash", "HKD", "0.0000", 1, 1, null, 1, "Full value"),
            arrayOf("CASH_RMB", "RMB cash", "CNY", "0.0260", 1, 1, null, 1, "HKSCC 2.6% haircut vs HKD"),
            arrayOf("CASH_USD", "USD cash", "USD", "0.0080", 1, 1, null, 1, "HKSCC 0.8% haircut vs HKD"),
            arrayOf("HK_GOVT_BOND", "HK Government bonds", "HKD", "0.0200", 1, 1, "0.50", 0, "Max 50% of required collateral"),
            arrayOf("MARGIN_EQTY", "HKEX margin-eligible equities", "HKD", "0.2500", 1, 1, "0.30", 0, "Max 30% of required collateral; per HKEX margin list"),
            arrayOf("BANK_GUAR", "Bank guarantee", "HKD", "0.0000", 1, 1, null, 1, "Bilateral only")
        )
        for (r in rows) {
            db.update(
                """INSERT INTO collateral_type_config
                   (collateral_type, description, currency, haircut_pct, eligible_general,
                    eligible_short_sale, max_concentration, nominal_valued, notes)
                   VALUES (?,?,?,?,?,?,?,?,?)""",
                *r
            )
        }
    }

    private val DDL = """
    CREATE TABLE IF NOT EXISTS counterparty (
        id                  TEXT PRIMARY KEY,
        name                TEXT NOT NULL,
        cp_type             TEXT NOT NULL,      -- LENDER | BORROWER | AGENT | INTERNAL
        tier                TEXT,               -- TIER_1 | TIER_2 | TIER_3
        approved_borrower   INTEGER NOT NULL DEFAULT 0,
        credit_limit_hkd    TEXT,               -- counterparty credit limit
        fee_settlement_basis TEXT NOT NULL DEFAULT 'MONTHLY', -- MONTHLY | WEEKLY | ON_CLOSE
        regulated_person    INTEGER NOT NULL DEFAULT 0,
        fee_share_pct       TEXT,               -- share of lending fee passed to this owner when the firm lends its stock (null = global default)
        rebate_spread_bps   INTEGER,            -- spread below the cash benchmark paid as rebate on cash collateral (null = default)
        created_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS sbla (
        id                  TEXT PRIMARY KEY,
        borrower_id         TEXT NOT NULL REFERENCES counterparty(id),
        lender_id           TEXT REFERENCES counterparty(id),
        sba_number          TEXT NOT NULL,      -- IRD-issued registration number
        registered_with_ird INTEGER NOT NULL DEFAULT 1,
        registered_at       TEXT NOT NULL,
        valid_until         TEXT,
        status              TEXT NOT NULL DEFAULT 'ACTIVE',
        created_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS designated_security (
        stock_code          TEXT PRIMARY KEY,
        stock_name          TEXT NOT NULL,
        security_type       TEXT NOT NULL DEFAULT 'EQTY',   -- EQTY | TRST (ETF/REIT)
        issued_shares       INTEGER NOT NULL,
        eligible            INTEGER NOT NULL DEFAULT 1,
        ca_restricted       INTEGER NOT NULL DEFAULT 0,
        updated_at          TEXT NOT NULL,
        issuer_id           TEXT,               -- groups share lines of the same issuer (wrong-way risk); NULL = stock code
        currency            TEXT NOT NULL DEFAULT 'HKD' -- trading currency of the closing price
    );

    CREATE TABLE IF NOT EXISTS collateral_type_config (
        collateral_type     TEXT PRIMARY KEY,
        description         TEXT,
        currency            TEXT NOT NULL,
        haircut_pct         TEXT NOT NULL,
        eligible_general    INTEGER NOT NULL DEFAULT 1,
        eligible_short_sale INTEGER NOT NULL DEFAULT 1,
        max_concentration   TEXT,               -- max share of required collateral this type may cover
        notes               TEXT,
        nominal_valued      INTEGER NOT NULL DEFAULT 0  -- 1 = valued at face (cash, guarantees); 0 = needs a market price
    );

    CREATE TABLE IF NOT EXISTS rate_schedule (
        schedule_id         TEXT PRIMARY KEY,
        stock_code          TEXT,               -- NULL = all stocks
        borrower_tier       TEXT,               -- NULL = all tiers
        fee_type            TEXT NOT NULL,      -- FIXED | FLOATING
        annual_rate         TEXT,               -- for FIXED
        floating_index      TEXT,               -- e.g. HIBOR_1M
        floating_spread_bps INTEGER,
        min_rate            TEXT,
        effective_from      TEXT NOT NULL,
        effective_to        TEXT,
        priority            INTEGER NOT NULL,   -- lower = higher priority
        created_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS price_snapshot (
        stock_code          TEXT NOT NULL,
        price_date          TEXT NOT NULL,
        closing_price       TEXT NOT NULL,
        PRIMARY KEY (stock_code, price_date)
    );

    CREATE TABLE IF NOT EXISTS fx_rate (
        currency            TEXT NOT NULL,
        rate_date           TEXT NOT NULL,
        rate_to_hkd         TEXT NOT NULL,
        PRIMARY KEY (currency, rate_date)
    );

    CREATE TABLE IF NOT EXISTS floating_index (
        index_name          TEXT NOT NULL,
        rate_date           TEXT NOT NULL,
        annual_rate         TEXT NOT NULL,
        PRIMARY KEY (index_name, rate_date)
    );

    CREATE TABLE IF NOT EXISTS documentary_assurance (
        assurance_id        TEXT PRIMARY KEY,
        assurance_type      TEXT NOT NULL,      -- HOLD_NOTICE | BLANKET_ASSURANCE | BORROW
        lender_id           TEXT REFERENCES counterparty(id),
        borrower_id         TEXT NOT NULL REFERENCES counterparty(id),
        stock_code          TEXT NOT NULL,
        quantity            INTEGER NOT NULL,
        assurance_basis     TEXT,               -- TRADING_BOOK | TRADING_UNIT | LEGAL_ENTITY
        channel             TEXT,
        created_ts          TEXT NOT NULL,      -- exact HKT timestamp
        expires_at          TEXT,
        retention_tier      TEXT NOT NULL DEFAULT '1Y',
        retention_expiry    TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS blanket_assurance (
        blanket_id          TEXT PRIMARY KEY,
        lender_id           TEXT NOT NULL REFERENCES counterparty(id),
        borrower_id         TEXT NOT NULL REFERENCES counterparty(id),
        stock_code          TEXT NOT NULL,
        pool_qty_original   INTEGER NOT NULL,
        pool_qty_remaining  INTEGER NOT NULL,
        valid_from          TEXT NOT NULL,
        valid_until         TEXT NOT NULL,
        channel             TEXT NOT NULL,
        tape_reference      TEXT,
        status              TEXT NOT NULL DEFAULT 'ACTIVE',
        created_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS sbl_contract (
        contract_id                 TEXT PRIMARY KEY,
        status                      TEXT NOT NULL,
        trade_type                  TEXT NOT NULL,   -- FIXED | AUCTION | NEGOTIATED
        transaction_model           TEXT NOT NULL,   -- PRINCIPAL | AGENCY | CCP | BILATERAL | FOREIGN
        lender_id                   TEXT NOT NULL REFERENCES counterparty(id),
        borrower_id                 TEXT NOT NULL REFERENCES counterparty(id),
        agent_id                    TEXT REFERENCES counterparty(id),
        stock_code                  TEXT NOT NULL,
        quantity                    INTEGER NOT NULL,
        quantity_returned           INTEGER NOT NULL DEFAULT 0,
        quantity_outstanding        INTEGER NOT NULL,
        open_date                   TEXT NOT NULL,
        close_date                  TEXT,
        actual_close_date           TEXT,
        fee_rate                    TEXT NOT NULL,
        fee_type                    TEXT NOT NULL,   -- FIXED | FLOATING
        fee_rate_effective_date     TEXT,
        floating_index              TEXT,
        floating_spread_bps         INTEGER,
        collateral_ratio_required   TEXT NOT NULL,   -- 1.00 or 1.05
        is_short_sale_borrow        INTEGER NOT NULL DEFAULT 0,
        sbla_id                     TEXT REFERENCES sbla(id),
        locate_id                   TEXT,            -- plain column (avoids FK cycle)
        assurance_id                TEXT,
        assurance_basis             TEXT,
        agent_split_pct             TEXT,            -- % of fee to agent (agency trades)
        regulated_person_flag       INTEGER NOT NULL DEFAULT 0,
        regulated_person_deadline   TEXT,
        regulated_person_deployed   INTEGER,
        regulated_person_purpose    TEXT,
        potential_non_exempt        INTEGER NOT NULL DEFAULT 0,
        non_exempt_reason           TEXT,
        ird_category                INTEGER NOT NULL DEFAULT 0,  -- 0 = exempt; 1..4 non-exempt
        cumulative_fee_hkd          TEXT NOT NULL DEFAULT '0',
        book_side                   TEXT NOT NULL DEFAULT 'LOAN_OUT', -- LOAN_OUT | BORROW_IN (firm borrows from the street)
        version                     INTEGER NOT NULL DEFAULT 1,
        created_at                  TEXT NOT NULL,
        updated_at                  TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS contract_allocation (
        allocation_id       TEXT PRIMARY KEY,
        contract_id         TEXT NOT NULL REFERENCES sbl_contract(contract_id),
        stock_code          TEXT NOT NULL,
        source_type         TEXT NOT NULL,      -- FIRM | CLIENT_MARGIN | THIRD_PARTY | STREET_BORROW
        source_owner_id     TEXT NOT NULL REFERENCES counterparty(id),
        position_id         TEXT,               -- set for inventory sources
        source_contract_id  TEXT REFERENCES sbl_contract(contract_id), -- set for STREET_BORROW (the borrow-in)
        quantity            INTEGER NOT NULL,
        recalled_qty        INTEGER NOT NULL DEFAULT 0, -- portion under a recall issued because this source fell short
        effective_from      TEXT NOT NULL,
        effective_to        TEXT,               -- exclusive; null = current
        created_at          TEXT NOT NULL,
        updated_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS locate_request (
        locate_id           TEXT PRIMARY KEY,
        status              TEXT NOT NULL,
        borrower_id         TEXT NOT NULL REFERENCES counterparty(id),
        requesting_system   TEXT NOT NULL,
        request_ref         TEXT,
        stock_code          TEXT NOT NULL,
        requested_qty       INTEGER NOT NULL,
        approved_qty        INTEGER,
        rejected_qty        INTEGER,
        reject_reason       TEXT,
        fee_rate            TEXT,
        fee_type            TEXT,
        rate_basis          TEXT,
        requested_at        TEXT NOT NULL,
        approved_at         TEXT,
        expires_at          TEXT,
        converted_at        TEXT,
        contract_id         TEXT,
        assurance_type      TEXT,
        assurance_id        TEXT,
        assurance_basis     TEXT,
        sbla_id             TEXT,
        designated_security INTEGER NOT NULL DEFAULT 0,
        tick_rule_exempt    INTEGER NOT NULL DEFAULT 0,
        created_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS lifecycle_event (
        event_id            TEXT PRIMARY KEY,
        contract_id         TEXT NOT NULL REFERENCES sbl_contract(contract_id),
        event_type          TEXT NOT NULL,
        from_status         TEXT,
        to_status           TEXT,
        detail              TEXT,
        event_ts            TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS contract_rate_history (
        rate_history_id     TEXT PRIMARY KEY,
        contract_id         TEXT NOT NULL REFERENCES sbl_contract(contract_id),
        fee_type            TEXT NOT NULL,      -- FIXED | FLOATING | NEGOTIATED
        annual_rate         TEXT,
        floating_index      TEXT,
        floating_spread_bps INTEGER,
        effective_from      TEXT NOT NULL,
        effective_to        TEXT,
        reason              TEXT,
        created_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS recall_notice (
        recall_id           TEXT PRIMARY KEY,
        contract_id         TEXT NOT NULL REFERENCES sbl_contract(contract_id),
        recall_qty          INTEGER NOT NULL,
        recall_reason       TEXT NOT NULL,      -- LENDER_REQUEST | CA_EVENT | LIMIT_BREACH | REGULATORY
        notice_served_at    TEXT NOT NULL,
        return_deadline     TEXT NOT NULL,
        status              TEXT NOT NULL,      -- PENDING | PARTIALLY_RETURNED | RETURNED | OVERDUE | DEFAULTED
        returned_qty        INTEGER NOT NULL DEFAULT 0,
        returned_at         TEXT,
        created_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS settlement_leg (
        leg_id               TEXT PRIMARY KEY,
        contract_id          TEXT NOT NULL REFERENCES sbl_contract(contract_id),
        leg_type             TEXT NOT NULL,     -- OPEN (delivery to borrower) | RETURN (redelivery to lender)
        quantity             INTEGER NOT NULL,
        trade_date           TEXT NOT NULL,
        intended_settle_date TEXT NOT NULL,
        actual_settle_date   TEXT,
        status               TEXT NOT NULL,     -- PENDING | SETTLED | FAILED | CANCELLED
        external_ref         TEXT UNIQUE,       -- CCASS / custodian confirmation reference
        fail_reason          TEXT,
        created_at           TEXT NOT NULL,
        updated_at           TEXT NOT NULL
    );

    -- Collateral account per counterparty (netting set): collateral covers the whole loan book.
    CREATE TABLE IF NOT EXISTS collateral_position (
        position_id         TEXT PRIMARY KEY,
        counterparty_id     TEXT NOT NULL REFERENCES counterparty(id),
        collateral_type     TEXT NOT NULL REFERENCES collateral_type_config(collateral_type),
        asset_id            TEXT NOT NULL,
        quantity            TEXT NOT NULL,
        market_value_local  TEXT NOT NULL,
        fx_rate_to_hkd      TEXT NOT NULL DEFAULT '1.0',
        market_value_hkd    TEXT NOT NULL,
        haircut_pct         TEXT NOT NULL,
        adjusted_value_hkd  TEXT NOT NULL,
        last_mtm_date       TEXT NOT NULL,
        status              TEXT NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | RELEASED | SUBSTITUTED | LIQUIDATED
        margin_call_id      TEXT,
        created_at          TEXT NOT NULL,
        updated_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS margin_call (
        call_id                 TEXT PRIMARY KEY,
        counterparty_id         TEXT NOT NULL REFERENCES counterparty(id),
        call_date               TEXT NOT NULL,
        borrowed_value_hkd      TEXT NOT NULL,
        required_collateral_hkd TEXT NOT NULL,
        current_collateral_hkd  TEXT NOT NULL,
        deficit_hkd             TEXT NOT NULL,
        coverage_ratio          TEXT NOT NULL,
        deadline                TEXT NOT NULL,
        status                  TEXT NOT NULL,   -- PENDING | RESOLVED | ESCALATED | DEFAULTED
        resolved_at             TEXT,
        resolution_method       TEXT,            -- CASH_TOPUP | SUBSTITUTION | EXTENSION | RECALL | DEFAULT
        created_at              TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS collateral_mtm_history (
        history_id              TEXT PRIMARY KEY,
        counterparty_id         TEXT NOT NULL REFERENCES counterparty(id),
        mtm_date                TEXT NOT NULL,
        borrowed_value_hkd      TEXT NOT NULL,
        required_collateral_hkd TEXT NOT NULL,
        actual_collateral_hkd   TEXT NOT NULL,
        coverage_ratio          TEXT NOT NULL,
        margin_call_triggered   INTEGER NOT NULL DEFAULT 0,
        created_at              TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS contract_mtm_history (
        history_id              TEXT PRIMARY KEY,
        contract_id             TEXT NOT NULL REFERENCES sbl_contract(contract_id),
        mtm_date                TEXT NOT NULL,
        closing_price           TEXT NOT NULL,
        borrowed_value_hkd      TEXT NOT NULL,
        required_collateral_hkd TEXT NOT NULL,
        created_at              TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS fee_accrual (
        accrual_id          TEXT PRIMARY KEY,
        contract_id         TEXT NOT NULL REFERENCES sbl_contract(contract_id),
        accrual_date        TEXT NOT NULL,
        closing_price       TEXT NOT NULL,
        qty_outstanding     INTEGER NOT NULL,
        annual_rate         TEXT NOT NULL,
        daily_fee_hkd       TEXT NOT NULL,
        cumulative_fee_hkd  TEXT NOT NULL,
        index_rate          TEXT,
        spread_bps          INTEGER,
        settlement_status   TEXT NOT NULL DEFAULT 'ACCRUED', -- ACCRUED | SETTLED | DISPUTED | WAIVED
        settlement_batch_id TEXT,
        created_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS fee_split (
        split_id            TEXT PRIMARY KEY,
        accrual_id          TEXT NOT NULL REFERENCES fee_accrual(accrual_id),
        recipient_id        TEXT NOT NULL REFERENCES counterparty(id),
        recipient_role      TEXT NOT NULL,      -- LENDER | AGENT | CLIENT_MARGIN | THIRD_PARTY
        split_pct           TEXT NOT NULL,
        split_amount_hkd    TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS fee_statement (
        statement_id        TEXT PRIMARY KEY,
        counterparty_id     TEXT NOT NULL REFERENCES counterparty(id),
        period_from         TEXT NOT NULL,
        period_to           TEXT NOT NULL,
        total_fee_hkd       TEXT NOT NULL,
        total_rebate_hkd    TEXT NOT NULL DEFAULT '0', -- cash-collateral rebate owed to the counterparty
        net_amount_hkd      TEXT,               -- fee - rebate (positive = counterparty pays)
        status              TEXT NOT NULL,      -- DRAFT | SENT | AGREED | DISPUTED | SETTLED
        sent_at             TEXT,
        agreed_at           TEXT,
        dispute_reason      TEXT,
        created_at          TEXT NOT NULL
    );

    -- Daily rebate on cash collateral held in a counterparty's account (benchmark - spread).
    CREATE TABLE IF NOT EXISTS rebate_accrual (
        accrual_id          TEXT PRIMARY KEY,
        counterparty_id     TEXT NOT NULL REFERENCES counterparty(id),
        position_id         TEXT NOT NULL REFERENCES collateral_position(position_id),
        accrual_date        TEXT NOT NULL,
        currency            TEXT NOT NULL,
        cash_amount         TEXT NOT NULL,      -- in [currency]
        fx_rate_to_hkd      TEXT NOT NULL,
        benchmark           TEXT NOT NULL,      -- HIBOR_1M | SOFR | EURIBOR ...
        index_rate          TEXT NOT NULL,
        spread_bps          INTEGER NOT NULL,
        rebate_rate         TEXT NOT NULL,      -- index_rate - spread; may be negative
        daily_rebate_hkd    TEXT NOT NULL,
        settlement_status   TEXT NOT NULL DEFAULT 'ACCRUED', -- ACCRUED | SETTLED | DISPUTED | WAIVED
        settlement_batch_id TEXT,
        created_at          TEXT NOT NULL,
        UNIQUE (position_id, accrual_date)
    );

    CREATE TABLE IF NOT EXISTS ca_event (
        ca_event_id         TEXT PRIMARY KEY,
        stock_code          TEXT NOT NULL,
        ca_type             TEXT NOT NULL,
        ex_date             TEXT,
        record_date         TEXT,
        payment_date        TEXT,
        adjustment_ratio    TEXT,
        cash_amount         TEXT,
        currency            TEXT,
        description         TEXT,
        source              TEXT,
        processed           INTEGER NOT NULL DEFAULT 0,
        created_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS ca_contract_impact (
        impact_id           TEXT PRIMARY KEY,
        ca_event_id         TEXT NOT NULL REFERENCES ca_event(ca_event_id),
        contract_id         TEXT NOT NULL REFERENCES sbl_contract(contract_id),
        action_type         TEXT NOT NULL,      -- MANUFACTURED_PAYMENT | QTY_ADJUSTMENT | RECALL | EARLY_TERMINATION | ALERT_ONLY
        status              TEXT NOT NULL DEFAULT 'PENDING',
        old_quantity        INTEGER,
        new_quantity        INTEGER,
        payment_amount_hkd  TEXT,
        payment_due_date    TEXT,
        payment_status      TEXT,               -- PENDING | RECEIVED | OVERDUE
        recall_id           TEXT,
        notes               TEXT,
        created_at          TEXT NOT NULL,
        processed_at        TEXT
    );

    CREATE TABLE IF NOT EXISTS short_position_report (
        report_id           TEXT PRIMARY KEY,
        report_date         TEXT NOT NULL,
        stock_code          TEXT NOT NULL,
        stock_name          TEXT NOT NULL,
        net_short_shares    INTEGER NOT NULL,
        net_short_value_hkd TEXT NOT NULL,
        reportable          INTEGER NOT NULL,
        submission_id       TEXT,
        created_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS sprs_submission (
        submission_id       TEXT PRIMARY KEY,
        report_date         TEXT NOT NULL,
        frequency           TEXT NOT NULL,      -- WEEKLY | DAILY
        filename            TEXT NOT NULL,
        row_count           INTEGER NOT NULL,
        submission_ref      TEXT,
        status              TEXT NOT NULL,      -- GENERATED | SUBMITTED | ACKNOWLEDGED
        created_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS ird_sbul_transaction (
        entry_id            TEXT PRIMARY KEY,
        sbla_id             TEXT NOT NULL,
        contract_id         TEXT NOT NULL,
        tax_year            INTEGER NOT NULL,
        category            INTEGER NOT NULL,   -- 0 exempt; 1..4 non-exempt schedules
        stampable           INTEGER NOT NULL DEFAULT 0,
        reason              TEXT,
        created_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS cap571x_disclosure (
        disclosure_id       TEXT PRIMARY KEY,
        person_id           TEXT NOT NULL,
        contract_id         TEXT,
        event_type          TEXT NOT NULL,      -- BORROW | RETURN | DEPLOYMENT | DEEMED_ACQUISITION
        event_date          TEXT NOT NULL,
        stock_code          TEXT NOT NULL,
        quantity            INTEGER NOT NULL,
        prescribed_purpose  TEXT,
        disclosure_deadline TEXT NOT NULL,
        status              TEXT NOT NULL DEFAULT 'PENDING',
        created_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS lendable_pool_snapshot (
        snapshot_id         TEXT PRIMARY KEY,
        stock_code          TEXT NOT NULL,
        as_of_datetime      TEXT NOT NULL,
        gross_lendable      INTEGER NOT NULL,
        locate_committed    INTEGER NOT NULL,
        on_loan             INTEGER NOT NULL,
        net_available       INTEGER NOT NULL,
        qty_proprietary     INTEGER,
        qty_client_disc     INTEGER,
        qty_lending_pool    INTEGER,
        qty_recalled        INTEGER,
        created_at          TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS position (
        position_id         TEXT PRIMARY KEY,
        owner_id            TEXT NOT NULL REFERENCES counterparty(id),
        stock_code          TEXT NOT NULL,
        account_type        TEXT NOT NULL,
        quantity            INTEGER NOT NULL,
        settlement_status   TEXT NOT NULL,      -- SETTLED | PENDING_T1 | PENDING_T2
        lendable            INTEGER NOT NULL,
        as_of_date          TEXT NOT NULL,
        updated_at          TEXT NOT NULL,
        source_system       TEXT NOT NULL,
        source_ref          TEXT
    );

    CREATE TABLE IF NOT EXISTS audit_log (
        log_id              TEXT PRIMARY KEY,
        entity_type         TEXT NOT NULL,
        entity_id           TEXT NOT NULL,
        event_type          TEXT NOT NULL,
        event_timestamp     TEXT NOT NULL,
        actor_id            TEXT NOT NULL,
        actor_type          TEXT NOT NULL,      -- USER | SYSTEM | EXTERNAL
        old_value           TEXT,
        new_value           TEXT,
        regulatory_ref      TEXT,
        retention_tier      TEXT NOT NULL,      -- 1Y | 3Y | 7Y
        retention_expiry    TEXT NOT NULL,
        source_ip           TEXT,
        session_id          TEXT
    );

    CREATE INDEX IF NOT EXISTS idx_audit_entity ON audit_log(entity_type, entity_id);
    CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_log(event_timestamp);
    CREATE INDEX IF NOT EXISTS idx_position_stock ON position(stock_code, settlement_status);
    CREATE INDEX IF NOT EXISTS idx_contract_status ON sbl_contract(status, stock_code);
    CREATE INDEX IF NOT EXISTS idx_alloc_contract ON contract_allocation(contract_id, effective_to);
    CREATE INDEX IF NOT EXISTS idx_alloc_position ON contract_allocation(position_id, effective_to);
    CREATE INDEX IF NOT EXISTS idx_alloc_source_contract ON contract_allocation(source_contract_id, effective_to);
    CREATE INDEX IF NOT EXISTS idx_locate_status ON locate_request(status, stock_code);
    CREATE INDEX IF NOT EXISTS idx_collateral_account ON collateral_position(counterparty_id, status);
    CREATE INDEX IF NOT EXISTS idx_margin_call_account ON margin_call(counterparty_id, status);
    CREATE INDEX IF NOT EXISTS idx_contract_mtm ON contract_mtm_history(contract_id, mtm_date);
    CREATE INDEX IF NOT EXISTS idx_fee_contract ON fee_accrual(contract_id, settlement_status);
    CREATE INDEX IF NOT EXISTS idx_leg_contract ON settlement_leg(contract_id, leg_type, status);
    CREATE INDEX IF NOT EXISTS idx_leg_status ON settlement_leg(status, intended_settle_date);
    """
}
