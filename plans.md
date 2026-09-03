# plans.md — Hong Kong SBL System (SBL-Core Focus)



> **Version:** 3.0 (Simplified — SBL Core)

> **Date:** September 2026

> **Scope:** SBL system core — Locate, Position/Inventory, Contract Lifecycle,

>            Collateral, Fee & Billing, Corporate Actions, Regulatory Reporting,

>            Audit Trail.

>

> **Out of Scope (handled by external systems):**

>   - Pre-trade risk checks (tick rule, short sell flagging) → Trading System

>   - Order management & execution → Trading System / OMS

>   - Settlement instruction generation (DVP/FOP, CCASS) → Settlement System

>   - Fails management & buy-in → Settlement System

>   - General ledger / accounting → Finance System

>

> **This system's role:** Single source of truth for what can be lent,

> what is currently lent, what collateral is held, and what is owed.

> It answers the question: "Do we have it? Can we lend it? Is it covered?"

> at any point in time.



---



## Table of Contents



1. [System Context & Boundaries](#1-system-context--boundaries)

2. [Module 1 — Locate Management](#2-module-1--locate-management)

3. [Module 2 — Position & Inventory Management](#3-module-2--position--inventory-management)

4. [Module 3 — Contract Lifecycle Management](#4-module-3--contract-lifecycle-management)

5. [Module 4 — Collateral Management](#5-module-4--collateral-management)

6. [Module 5 — Fee & Billing](#6-module-5--fee--billing)

7. [Module 6 — Corporate Actions Impact](#7-module-6--corporate-actions-impact)

8. [Module 7 — Regulatory Reporting](#8-module-7--regulatory-reporting)

9. [Module 8 — Audit Trail & Record Keeping](#9-module-8--audit-trail--record-keeping)

10. [Core Data Model](#10-core-data-model)

11. [Integration Boundaries](#11-integration-boundaries)

12. [Implementation Phases](#12-implementation-phases)

13. [Non-Functional Requirements](#13-non-functional-requirements)

14. [Risk & Open Issues](#14-risk--open-issues)



---



## 1. System Context & Boundaries



### 1.1 What This System Does


┌─────────────────────────────────────────────────────────────────────┐

│ SBL CORE SYSTEM │

│ │

│ ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐ │

│ │ Locate │ │ Position & │ │ Contract Lifecycle │ │

│ │ Management │ │ Inventory │ │ Management │ │

│ └──────────────┘ └──────────────┘ └──────────────────────┘ │

│ │

│ ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐ │

│ │ Collateral │ │ Fee & │ │ Corporate Actions │ │

│ │ Management │ │ Billing │ │ Impact │ │

│ └──────────────┘ └──────────────┘ └──────────────────────┘ │

│ │

│ ┌──────────────┐ ┌──────────────────────────────────────────┐ │

│ │ Regulatory │ │ Audit Trail & Record Keeping │ │

│ │ Reporting │ │ (SFC 1yr/7yr, IRD, Cap.571X) │ │

│ └──────────────┘ └──────────────────────────────────────────┘ │

└─────────────────────────────────────────────────────────────────────┘

▲ ▲ ▲

│ │ │

Trading System Settlement System Market Data Feed

(sends locate (sends settlement (closing prices,

requests; confirmations; FX rates,

receives position updates) CA events)

approvals)





### 1.2 Key Interfaces (What We Consume & Produce)



| Direction | Counterpart System | Data Exchanged |

|---|---|---|

| **Inbound** | Trading System | Locate requests; borrow confirmations |

| **Inbound** | Settlement System | Settlement confirmations (open/close leg settled) |

| **Inbound** | Settlement System | Position updates (settled long positions) |

| **Inbound** | Market Data Feed | EOD closing prices, intraday prices, FX rates |

| **Inbound** | HKEX | Designated Securities list (daily sync) |

| **Inbound** | Corporate Actions Feed | CA events (dividends, splits, rights, etc.) |

| **Outbound** | Trading System | Locate approvals / rejections with quantity & rate |

| **Outbound** | Settlement System | Contract open/close instructions (not SI — just intent) |

| **Outbound** | SFC SPRS | Short position report CSV (weekly/daily) |

| **Outbound** | IRD | Annual return Form SBUL 1 |

| **Outbound** | Counterparties | Margin call notifications, fee statements, recall notices |



### 1.3 The Central Question This System Answers



At any moment, for any security, the system must be able to answer:



LOCATE: "Can I lend X shares of stock Y to borrower Z right now?"

INVENTORY: "How much of stock Y do I have available to lend in total?"

CONTRACT: "What are all my open loans of stock Y, to whom, at what rate?"

COLLATERAL:"Is every open loan fully collateralised right now?"

EXPOSURE: "What is my total exposure to borrower Z across all loans?"

FEE: "How much has borrower Z accrued in fees today / this month?"

REPORTING: "What is my net short position in stock Y for SFC reporting?"



---



## 2. Module 1 — Locate Management



### Purpose

A **locate** is the pre-borrow confirmation that a specific quantity of a security

is available to lend. It is the entry point of the entire SBL workflow. The Trading

System requests a locate before placing a short sell order; this module approves,

rejects, or partially fills it.



In HK: a locate is the system-side implementation of the **documentary assurance**

requirement under SFO s.171 (hold notice / blanket assurance / borrow).



---



### 2.1 Locate Request Lifecycle



Trading System sends LocateRequest

│

▼

┌───────────────────────────────────────────────────────────┐

│ Step 1: Eligibility Check │

│ → Is security on HKEX Designated Securities list? │

│ → Is security under CA restriction (rights period)? │

│ → Is borrower on approved borrower list? │

│ → Is borrower's SBLA registered with IRD? │

│ → Result: ELIGIBLE | INELIGIBLE(reason) │

└───────────────────────────────────────────────────────────┘

│ ELIGIBLE

▼

┌───────────────────────────────────────────────────────────┐

│ Step 2: Availability Check │

│ → Query lendable pool for requested security │

│ → Deduct already-committed locates (not yet converted │

│ to contracts) from available pool │

│ → Result: available_qty │

└───────────────────────────────────────────────────────────┘

│

▼

┌───────────────────────────────────────────────────────────┐

│ Step 3: Rate Determination │

│ → Look up rate schedule for security + borrower tier │

│ → Apply: fixed rate | floating (HIBOR-linked) | │

│ negotiated rate │

│ → Check minimum spread requirements │

└───────────────────────────────────────────────────────────┘

│

▼

┌───────────────────────────────────────────────────────────┐

│ Step 4: Limit Checks │

│ → Counterparty credit limit: headroom ≥ new exposure? │

│ → Concentration limit: per-issuer, per-borrower, │

│ per-sector within lendable pool │

│ → Short interest limit: total short interest in stock │

│ ≤ 10% of outstanding shares (HK regulatory cap) │

└───────────────────────────────────────────────────────────┘

│

▼

┌───────────────────────────────────────────────────────────┐

│ Step 5: Decision & Commitment │

│ → APPROVED: commit qty from lendable pool; │

│ create locate record; return rate + expiry │

│ → PARTIAL: approve lesser qty; explain shortfall │

│ → REJECTED: return reason code │

└───────────────────────────────────────────────────────────┘

│ APPROVED / PARTIAL

▼

┌───────────────────────────────────────────────────────────┐

│ Step 6: Documentary Assurance Record (SFO s.171) │

│ → Auto-create documentary_assurance record: │

│ type = HOLD_NOTICE (if qty reserved but not yet │

│ converted to contract) │

│ type = BORROW (once contract is confirmed) │

│ → Record: borrower, security, qty, timestamp (HKT), │

│ expiry, channel, assurance_basis │

│ → Retention: 1 year (7 years for licensed corps) │

└───────────────────────────────────────────────────────────┘





---



### 2.2 Locate States



REQUESTED → locate request received from Trading System

APPROVED → qty committed from lendable pool; hold notice created

PARTIAL → lesser qty approved; remainder rejected

REJECTED → no availability or failed eligibility/limit checks

EXPIRED → approved locate not converted to contract within expiry window

CONVERTED → locate converted to active SBL contract

CANCELLED → cancelled by requester before conversion





---



### 2.3 Locate Commitment Pool



The locate commitment pool tracks all **approved-but-not-yet-contracted** locates.

This is critical: without it, the same inventory can be double-committed.



Available to Lend (real-time) =

Lendable Pool (from Module 2)

− Open Locate Commitments (APPROVED, not yet CONVERTED or EXPIRED)

− Quantity on Active Contracts (from Module 3)





**Expiry management:**

- Each locate has a configurable expiry window (default: same trading day, 16:00 HKT)

- Expired locates automatically release their committed quantity back to the pool

- Expiry job runs every 5 minutes during trading hours



---



### 2.4 Locate Data Schema



```sql

CREATE TABLE locate_request (

    locate_id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    status              VARCHAR(20)  NOT NULL,

    -- REQUESTED | APPROVED | PARTIAL | REJECTED | EXPIRED | CONVERTED | CANCELLED



    -- Requester

    borrower_id         UUID         NOT NULL REFERENCES counterparty(id),

    requesting_system   VARCHAR(50)  NOT NULL,  -- e.g. 'TRADING_SYSTEM'

    request_ref         VARCHAR(100),           -- Trading system's own reference



    -- Security

    stock_code          VARCHAR(10)  NOT NULL,

    requested_qty       BIGINT       NOT NULL,

    approved_qty        BIGINT,

    rejected_qty        BIGINT,

    reject_reason       VARCHAR(200),



    -- Rate

    fee_rate            NUMERIC(10,6),

    fee_type            VARCHAR(10),            -- FIXED | FLOATING | NEGOTIATED

    rate_basis          VARCHAR(20),            -- ANNUAL | DAILY



    -- Timing

    requested_at        TIMESTAMPTZ  NOT NULL,

    approved_at         TIMESTAMPTZ,

    expires_at          TIMESTAMPTZ,            -- locate commitment expiry

    converted_at        TIMESTAMPTZ,

    contract_id         UUID         REFERENCES sbl_contract(contract_id),



    -- Compliance

    assurance_type      VARCHAR(20),            -- HOLD_NOTICE | BLANKET_ASSURANCE | BORROW

    assurance_id        UUID         REFERENCES documentary_assurance(assurance_id),

    assurance_basis     VARCHAR(20),            -- TRADING_BOOK | TRADING_UNIT | LEGAL_ENTITY

    sbla_id             UUID         REFERENCES sbla(id),



    -- Eligibility snapshot at time of request

    designated_security BOOLEAN      NOT NULL,

    tick_rule_exempt    BOOLEAN      NOT NULL DEFAULT FALSE,



    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()

);

2.5 Blanket Assurance Management

For high-volume borrowers, a blanket assurance covers a pool of securities

rather than individual locates. The system must track pool utilisation:



sql

CREATE TABLE blanket_assurance (

    blanket_id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    lender_id           UUID         NOT NULL REFERENCES counterparty(id),

    borrower_id         UUID         NOT NULL REFERENCES counterparty(id),

    stock_code          VARCHAR(10)  NOT NULL,

    pool_qty_original   BIGINT       NOT NULL,

    pool_qty_remaining  BIGINT       NOT NULL,  -- decremented on each locate approval

    valid_from          TIMESTAMPTZ  NOT NULL,

    valid_until         TIMESTAMPTZ  NOT NULL,

    channel             VARCHAR(20)  NOT NULL,

    tape_reference      VARCHAR(200),

    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()

);

2.6 Rate Schedule

sql

CREATE TABLE rate_schedule (

    schedule_id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    stock_code          VARCHAR(10),            -- NULL = applies to all stocks

    borrower_tier       VARCHAR(20),            -- TIER_1 | TIER_2 | TIER_3 | NULL = all

    fee_type            VARCHAR(10)  NOT NULL,  -- FIXED | FLOATING

    annual_rate         NUMERIC(10,6),          -- for FIXED

    floating_index      VARCHAR(20),            -- e.g. HIBOR_1M for FLOATING

    floating_spread_bps INTEGER,                -- spread over index in bps

    min_rate            NUMERIC(10,6),          -- floor rate

    effective_from      DATE         NOT NULL,

    effective_to        DATE,

    priority            INTEGER      NOT NULL,  -- lower = higher priority

    -- Most specific rule wins (stock+tier > stock > tier > default)

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()

);

3. Module 2 — Position & Inventory Management

Purpose

Maintain a real-time, accurate view of what is available to lend (lendable pool)

and what is currently on loan (loan book). This is the inventory engine that

feeds Module 1 (Locate) and Module 3 (Contract Lifecycle).



3.1 Position Types

The system must track positions across multiple dimensions:



For each (owner, stock_code, account_type):



Account Types:

  PROPRIETARY           Own firm book — fully lendable

  CLIENT_DISCRETIONARY  Client with lending mandate — lendable

  CLIENT_NON_DISC       Client without lending mandate — NOT lendable

  LENDING_POOL          Explicitly earmarked for lending — lendable

  PLEDGED               Posted as collateral — NOT lendable

  CA_RESTRICTED         Under corporate action restriction — NOT lendable

  ON_LOAN               Currently lent out — not available (already deployed)

  RECALLED              Recall notice served — expected back within 2 days

  COLLATERAL_HELD       Collateral received from borrowers — tracked separately

3.2 Lendable Pool Calculation

Lendable Pool(stock_code) =



  SETTLED positions in:

    PROPRIETARY + CLIENT_DISCRETIONARY + LENDING_POOL



  MINUS:

    Pledged positions (PLEDGED)

    CA-restricted positions (CA_RESTRICTED)

    Pending own delivery obligations (T+1, T+2 unsettled sells)

    Regulatory minimum holdings (if configured)



  MINUS:

    Open Locate Commitments (APPROVED locates not yet CONVERTED or EXPIRED)

    Quantity on Active Contracts (ON_LOAN)



  PLUS (optional — configurable):

    Recalled positions (RECALLED) — expected return within 2 business days

    [Include only if recall notice served and return is high-confidence]



= Net Available to Lend

Key rule: Only settled positions count. Unsettled (T+1, T+2) positions

received from the settlement system are tracked but not included in the lendable

pool until settlement is confirmed.



3.3 Position Update Events (Inbound from Settlement System)

The settlement system pushes position events to the SBL system via a message queue:



Event: POSITION_SETTLED

  → New settled long position received

  → Add to lendable pool (if account_type is lendable)



Event: POSITION_PLEDGED

  → Position posted as collateral to third party

  → Remove from lendable pool



Event: POSITION_CA_RESTRICTED

  → CA restriction applied (e.g. rights subscription period)

  → Remove from lendable pool; alert open loans on this stock



Event: LOAN_OPEN_SETTLED

  → Settlement system confirms open leg of SBL contract settled

  → Move qty from lendable pool to ON_LOAN

  → Activate contract in Module 3



Event: LOAN_CLOSE_SETTLED

  → Settlement system confirms close leg settled (securities returned)

  → Move qty from ON_LOAN back to lendable pool

  → Close contract in Module 3



Event: COLLATERAL_RECEIVED

  → Collateral received from borrower

  → Add to COLLATERAL_HELD positions

  → Update collateral_position in Module 4

3.4 Inventory Dashboard — Key Views

The system must support these real-time views for operations:



View    Description

Lendable Pool   Available qty per stock, broken down by account type

Loan Book   All active loans: stock, qty, borrower, rate, open date, collateral status

Locate Pipeline All approved-but-not-converted locates with expiry countdown

Recall Pipeline All recalled loans with expected return dates

Hot Stocks  Securities where lendable pool < 20% of total holdings (configurable threshold)

Concentration View  % of lendable pool committed to each borrower / sector

Short Interest  Total qty on loan per stock as % of total outstanding shares

3.5 Designated Securities Sync

The SBL system (not the Trading System) owns the Designated Securities list because

it directly affects lendable inventory eligibility.



Daily sync job (07:30 HKT):

  → Download HKEX Designated Securities CSV

  → Diff against current snapshot

  → On REMOVAL:

      → Set stock as ineligible for new locates immediately

      → Flag all open locate commitments on this stock for review

      → Alert operations team

  → On ADDITION:

      → Mark stock as eligible

  → Publish DesignatedSecurityChangedEvent to downstream consumers

      (Trading System subscribes to this for pre-trade checks)

3.6 Position Data Schema

sql

CREATE TABLE position (

    position_id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    owner_id            UUID         NOT NULL REFERENCES counterparty(id),

    stock_code          VARCHAR(10)  NOT NULL,

    account_type        VARCHAR(30)  NOT NULL,

    quantity            BIGINT       NOT NULL,

    settlement_status   VARCHAR(20)  NOT NULL,  -- SETTLED | PENDING_T1 | PENDING_T2

    lendable            BOOLEAN      NOT NULL,  -- derived; updated on each position event

    as_of_date          DATE         NOT NULL,

    updated_at          TIMESTAMPTZ  NOT NULL,

    source_system       VARCHAR(50)  NOT NULL,  -- SETTLEMENT_SYSTEM | MANUAL | CCASS_REPORT

    source_ref          VARCHAR(100)            -- reference from source system

);



CREATE TABLE lendable_pool_snapshot (

    snapshot_id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    stock_code          VARCHAR(10)  NOT NULL,

    as_of_datetime      TIMESTAMPTZ  NOT NULL,

    gross_lendable      BIGINT       NOT NULL,  -- before locate commitments

    locate_committed    BIGINT       NOT NULL,  -- approved locates not yet converted

    on_loan             BIGINT       NOT NULL,  -- active contracts

    net_available       BIGINT       NOT NULL,  -- gross - committed - on_loan

    -- Breakdown by account type

    qty_proprietary     BIGINT,

    qty_client_disc     BIGINT,

    qty_lending_pool    BIGINT,

    qty_recalled        BIGINT,                 -- recalled, expected back

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()

);

-- Snapshots taken: on every position event + every 15 minutes during trading hours

4. Module 3 — Contract Lifecycle Management

Purpose

Manage the full lifecycle of every SBL contract from confirmation through to close.

This module does not generate settlement instructions (that is the Settlement

System's job). It manages the contractual state and triggers notifications to

the Settlement System when action is needed.



4.1 Contract States

PENDING_CONFIRM

    │  Both parties confirm terms

    ▼

CONFIRMED

    │  SBL system sends "open intent" to Settlement System

    │  Settlement System confirms open leg settled

    ▼

ACTIVE ──────────────────────────────────────────────────────┐

    │                                                         │

    ├── [Recall]      → RECALLED                             │

    │       │           │ Return settled → CLOSED            │

    │       │           ▼                                     │

    │       │       RETURN_PENDING                           │

    │       │                                                 │

    ├── [Re-rate]     → RERATED (returns to ACTIVE)          │

    │                                                         │

    ├── [Rollover]    → ROLLED_OVER (returns to ACTIVE)      │

    │                                                         │

    ├── [Partial Ret] → PARTIALLY_RETURNED (stays ACTIVE     │

    │                   with reduced quantity)                │

    │                                                         │

    ├── [CA Event]    → CA_ADJUSTED (returns to ACTIVE)      │

    │                                                         │

    └── [Return]      → RETURN_PENDING                       │

                            │ Return settled → CLOSED ◄──────┘



CANCELLED   (pre-settlement; both parties agree)

DEFAULTED   (borrower default; collateral liquidation triggered)

SUSPENDED   (regulatory directive)

4.2 Contract Events — Responsibilities

Event   Who Triggers    SBL System Does Settlement System Does

Open    Borrower confirms   Create contract; deduct from lendable pool; start fee accrual   Settle open leg (securities transfer)

Recall  Lender  Update status to RECALLED; notify borrower; start recall timer  Settle return leg on borrower's return

Return  Borrower    Update status to RETURN_PENDING; notify Settlement System   Settle return leg

Re-rate Either party    Update fee rate; recalculate accruals from effective date   No action

Rollover    Either party    Extend close date; re-validate collateral   No action

Partial Return  Borrower    Reduce contract quantity; adjust collateral requirement Settle partial return leg

CA Adjustment   System (CA feed)    Adjust quantity / manufactured payment (see Module 6)   Settle CA-related transfers

Close   Settlement System confirms  Mark contract CLOSED; release collateral; finalise fees Confirm return leg settled

Default Risk / System   Mark DEFAULTED; trigger collateral liquidation workflow Liquidate collateral

4.3 Recall Management

Recall is one of the most operationally critical events. The system must track

every recall with precision.



Recall Initiated (by Lender or System — e.g. CA event, limit breach)

        │

        ▼

Recall Notice Created:

  - recall_id, contract_id

  - recall_qty (full or partial)

  - notice_served_at (exact timestamp HKT)

  - return_deadline (T+2 business days, configurable)

  - recall_reason (LENDER_REQUEST | CA_EVENT | LIMIT_BREACH | REGULATORY)

        │

        ▼

Borrower Notified (automated — email / system message / SWIFT MT599)

        │

        ▼

Recall Timer Running:

  T+1 alert: "Return due tomorrow"

  T+2 deadline: if not returned → escalate to operations

  T+3: if still not returned → consider default / buy-in

        │

        ├── Return received (Settlement System confirms) → CLOSED

        │

        └── Not returned by deadline:

              → Escalate to Risk team

              → Options: extend deadline | declare default | source replacement borrow

4.4 Regulated Person 5-Business-Day Timer (Cap.571X)

For borrows by regulated persons (substantial shareholders ≥5%, directors):



Contract opened for regulated person

        │

        ▼

5-business-day countdown starts from open_date

        │

        ├── Shares deployed for prescribed purpose within 5 days:

        │     → Record purpose (on-lending / short selling / market making)

        │     → No deemed acquisition

        │

        └── NOT deployed within 5 days:

              → Day 4: alert compliance team

              → Day 5: deemed acquisition triggered

              → Generate Cap.571X disclosure record

              → Notify compliance team for filing

4.5 Contract Data Schema

sql

CREATE TABLE sbl_contract (

    contract_id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    status                      VARCHAR(30)  NOT NULL,

    trade_type                  VARCHAR(20)  NOT NULL,  -- FIXED | AUCTION | NEGOTIATED

    transaction_model           VARCHAR(20)  NOT NULL,

    -- PRINCIPAL | AGENCY | CCP | BILATERAL | FOREIGN



    -- Parties

    lender_id                   UUID         NOT NULL REFERENCES counterparty(id),

    borrower_id                 UUID         NOT NULL REFERENCES counterparty(id),

    agent_id                    UUID         REFERENCES counterparty(id),



    -- Security & Quantity

    stock_code                  VARCHAR(10)  NOT NULL,

    quantity                    BIGINT       NOT NULL,

    quantity_returned           BIGINT       NOT NULL DEFAULT 0,

    quantity_outstanding        BIGINT       GENERATED ALWAYS AS (quantity - quantity_returned) STORED,



    -- Dates

    open_date                   DATE         NOT NULL,

    close_date                  DATE,                   -- NULL = open-ended

    actual_close_date           DATE,



    -- Rate

    fee_rate                    NUMERIC(10,6) NOT NULL,

    fee_type                    VARCHAR(10)  NOT NULL,  -- FIXED | FLOATING

    fee_rate_effective_date     DATE,



    -- Collateral

    collateral_ratio_required   NUMERIC(5,2) NOT NULL,  -- 1.00 or 1.05

    is_short_sale_borrow        BOOLEAN      NOT NULL DEFAULT FALSE,



    -- Compliance

    sbla_id                     UUID         NOT NULL REFERENCES sbla(id),

    locate_id                   UUID         REFERENCES locate_request(locate_id),

    assurance_id                UUID         REFERENCES documentary_assurance(assurance_id),

    assurance_basis             VARCHAR(20),



    -- Regulated person

    regulated_person_flag       BOOLEAN      NOT NULL DEFAULT FALSE,

    regulated_person_deadline   DATE,

    regulated_person_deployed   BOOLEAN,

    regulated_person_purpose    VARCHAR(100),



    -- IRD tracking

    potential_non_exempt        BOOLEAN      NOT NULL DEFAULT FALSE,

    non_exempt_reason           VARCHAR(200),



    -- Optimistic locking

    version                     INTEGER      NOT NULL DEFAULT 1,

    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW()

);



CREATE TABLE recall_notice (

    recall_id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    contract_id             UUID         NOT NULL REFERENCES sbl_contract(contract_id),

    recall_qty              BIGINT       NOT NULL,

    recall_reason           VARCHAR(50)  NOT NULL,

    notice_served_at        TIMESTAMPTZ  NOT NULL,

    return_deadline         DATE         NOT NULL,

    status                  VARCHAR(20)  NOT NULL,

    -- PENDING | PARTIALLY_RETURNED | RETURNED | OVERDUE | DEFAULTED

    returned_qty            BIGINT       NOT NULL DEFAULT 0,

    returned_at             TIMESTAMPTZ,

    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()

);

5. Module 4 — Collateral Management

Purpose

Track, value, and manage all collateral held against open SBL contracts.

Perform daily mark-to-market, issue margin calls, and handle substitutions.

This is the most complex module in the SBL core system.



5.1 Collateral Rules (HK-Specific)

HKEX Schedule 6:

  General SBL:          collateral ≥ 100% of borrowed securities market value

  Short sale borrow:    collateral ≥ 105% of borrowed securities market value

  MTM frequency:        at least DAILY (using previous day closing price)

  Margin call response: borrower must top up AT LEAST DAILY

  Excess release:       borrower may demand release if collateral > required ratio

  CSB exemption:        Schedule 6 does NOT apply to HKSCC CSB transactions

5.2 Eligible Collateral & Haircuts

sql

CREATE TABLE collateral_type_config (

    collateral_type     VARCHAR(30)  PRIMARY KEY,

    description         VARCHAR(200),

    currency            VARCHAR(3)   NOT NULL,

    haircut_pct         NUMERIC(5,4) NOT NULL,

    -- Haircut: adjusted_value = market_value × (1 - haircut_pct)

    eligible_general    BOOLEAN      NOT NULL DEFAULT TRUE,

    eligible_short_sale BOOLEAN      NOT NULL DEFAULT TRUE,

    max_concentration   NUMERIC(5,2),

    -- max % of total collateral from this type (NULL = no limit)

    notes               TEXT

);



-- HK-specific seed values

INSERT INTO collateral_type_config VALUES

('CASH_HKD',    'HKD cash',                    'HKD', 0.0000, TRUE,  TRUE,  NULL, 'Full value'),

('CASH_RMB',    'RMB cash',                    'CNY', 0.0260, TRUE,  TRUE,  NULL, 'HKSCC 2.6% haircut vs HKD'),

('CASH_USD',    'USD cash',                    'USD', 0.0080, TRUE,  TRUE,  NULL, 'HKSCC 0.8% haircut vs HKD'),

('HK_GOVT_BOND','HK Government bonds',         'HKD', 0.0200, TRUE,  TRUE,  0.50, 'Max 50% of total collateral'),

('MARGIN_EQTY', 'HKEX margin-eligible equities','HKD', 0.2500, TRUE,  TRUE,  0.30, 'Max 30%; per HKEX margin list'),

('BANK_GUAR',   'Bank guarantee',              'HKD', 0.0000, TRUE,  TRUE,  NULL, 'Bilateral only');

5.3 Daily MTM Process

Trigger: EOD closing prices received from Market Data Feed (~16:15 HKT)



FOR EACH active contract:



  1. BORROWED SECURITIES VALUE

     borrowed_value = quantity_outstanding × closing_price(stock_code)



  2. REQUIRED COLLATERAL

     required_collateral = borrowed_value × collateral_ratio_required

     (1.00 for general; 1.05 for short sale borrow)



  3. CURRENT COLLATERAL VALUE

     current_collateral = Σ (market_value(asset_i) × (1 - haircut_i))

     for all collateral positions linked to this contract



  4. SURPLUS / DEFICIT

     surplus_deficit = current_collateral - required_collateral

     coverage_ratio  = current_collateral / required_collateral



  5. MARGIN CALL LOGIC

     IF coverage_ratio < 1.00:

       → Create margin_call record

       → Set deadline = next business day 09:00 HKT

       → Notify borrower immediately

       → Set margin_call_status = PENDING



     IF coverage_ratio > required_ratio + 0.05 (configurable buffer):

       → Notify borrower of right to request excess release (Schedule 6 Reg.11)



  6. PERSIST

     → Update collateral_position (market values, adjusted values)

     → Append to collateral_mtm_history (immutable record)

     → Update fee_accrual for the day (see Module 5)

5.4 Margin Call Workflow

Margin Call Issued (T, ~16:30 HKT)

        │

        ▼

Borrower notified via configured channel

(email / system message / SWIFT MT599)

        │

        ▼

Deadline: T+1, 09:00 HKT



        ├── Cash top-up received:

        │     → Validate amount ≥ deficit

        │     → Update collateral_position

        │     → Set margin_call_status = RESOLVED

        │

        ├── Collateral substitution requested:

        │     → Validate new collateral eligibility & sufficiency

        │     → Atomic swap (see 5.5)

        │     → Set margin_call_status = RESOLVED

        │

        └── No response by T+1 09:00 HKT:

              → Escalate: margin_call_status = ESCALATED

              → Alert Risk team

              → Risk options:

                  (a) Grant extension (max 1 business day, with approval)

                  (b) Issue recall notice → Module 3 recall workflow

                  (c) Declare default → collateral liquidation

5.5 Collateral Substitution

Borrower requests substitution:

  old_collateral_id, new_asset, new_quantity

        │

        ▼

Validation:

  1. Is new_asset eligible (per collateral_type_config)?

  2. Is new_asset on HKEX margin list (if MARGIN_EQTY type)?

  3. new_adjusted_value ≥ required_collateral?

  4. Concentration limit not breached by new_asset?

        │ PASS

        ▼

Atomic swap (single DB transaction):

  → Accept new collateral (create new collateral_position)

  → Release old collateral (update status = RELEASED)

  → Notify Settlement System to move collateral assets

  → Log substitution event to audit trail

        │

        ▼

If substitution is to resolve a margin call:

  → Set margin_call_status = RESOLVED

5.6 Collateral Data Schema

sql

CREATE TABLE collateral_position (

    position_id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    contract_id             UUID         NOT NULL REFERENCES sbl_contract(contract_id),

    collateral_type         VARCHAR(30)  NOT NULL REFERENCES collateral_type_config(collateral_type),

    asset_id                VARCHAR(20)  NOT NULL,  -- stock code, ISIN, or 'CASH_HKD' etc.

    quantity                NUMERIC(20,4) NOT NULL,

    market_value_local      NUMERIC(20,4) NOT NULL,  -- in collateral currency

    fx_rate_to_hkd          NUMERIC(10,6) NOT NULL DEFAULT 1.0,

    market_value_hkd        NUMERIC(20,4) NOT NULL,  -- converted to HKD

    haircut_pct             NUMERIC(5,4)  NOT NULL,

    adjusted_value_hkd      NUMERIC(20,4) NOT NULL,  -- after haircut, in HKD

    last_mtm_date           DATE          NOT NULL,

    status                  VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',

    -- ACTIVE | RELEASED | SUBSTITUTED | LIQUIDATED

    margin_call_id          UUID          REFERENCES margin_call(call_id),

    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW()

);



CREATE TABLE margin_call (

    call_id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    contract_id             UUID         NOT NULL REFERENCES sbl_contract(contract_id),

    call_date               DATE         NOT NULL,

    borrowed_value_hkd      NUMERIC(20,4) NOT NULL,

    required_collateral_hkd NUMERIC(20,4) NOT NULL,

    current_collateral_hkd  NUMERIC(20,4) NOT NULL,

    deficit_hkd             NUMERIC(20,4) NOT NULL,

    coverage_ratio          NUMERIC(8,6)  NOT NULL,

    deadline                TIMESTAMPTZ   NOT NULL,

    status                  VARCHAR(20)   NOT NULL,

    -- PENDING | RESOLVED | ESCALATED | DEFAULTED

    resolved_at             TIMESTAMPTZ,

    resolution_method       VARCHAR(30),

    -- CASH_TOPUP | SUBSTITUTION | EXTENSION | RECALL | DEFAULT

    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW()

);



CREATE TABLE collateral_mtm_history (

    history_id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    contract_id             UUID         NOT NULL REFERENCES sbl_contract(contract_id),

    mtm_date                DATE         NOT NULL,

    borrowed_value_hkd      NUMERIC(20,4) NOT NULL,

    required_collateral_hkd NUMERIC(20,4) NOT NULL,

    actual_collateral_hkd   NUMERIC(20,4) NOT NULL,

    coverage_ratio          NUMERIC(8,6)  NOT NULL,

    margin_call_triggered   BOOLEAN       NOT NULL DEFAULT FALSE,

    closing_price           NUMERIC(20,4) NOT NULL,

    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW()

    -- Append-only: no UPDATE or DELETE

);

6. Module 5 — Fee & Billing

Purpose

Accrue daily fees on all active contracts, manage fee settlements, and generate

billing statements for counterparties.



6.1 Fee Accrual Formula

Daily Fee = Previous Day Closing Price × Quantity Outstanding × (Annual Rate / 365)



For floating rate:

  Annual Rate = Floating Index Rate (e.g. 1M HIBOR) + Spread (bps / 100)

  Index rate fetched daily from market data feed



Cumulative Fee = Σ Daily Fees from Open Date to current date

6.2 Fee Accrual Process

Trigger: After EOD MTM batch completes (closing prices already loaded)



FOR EACH active contract:

  1. Fetch closing_price for stock_code (already in price_snapshot)

  2. Fetch current annual_rate (fixed or floating)

     → For floating: fetch index rate from market data; add spread

  3. Calculate daily_fee = closing_price × qty_outstanding × (rate / 365)

  4. Append to fee_accrual table

  5. Update contract.cumulative_fee



For agency trades:

  6. Split fee per configured agent/lender split ratio

     e.g. 40% to agent, 60% to lender

  7. Record split in fee_split table

6.3 Fee Settlement

Settlement basis: configurable per counterparty

  MONTHLY:    settle on last business day of each month

  ON_CLOSE:   settle when contract closes

  WEEKLY:     settle every Friday



Settlement process:

  1. Aggregate all unsettled daily_fee records for the period

  2. Generate fee statement (PDF + CSV)

  3. Dispatch to counterparty by 10:00 HKT next business day

  4. Await counterparty confirmation (bilateral compare)

  5. On agreement: mark fee_accrual records as SETTLED

  6. On dispute: flag for operations review; hold settlement

  7. Notify Settlement System to process cash payment

6.4 Fee Data Schema

sql

CREATE TABLE fee_accrual (

    accrual_id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    contract_id         UUID         NOT NULL REFERENCES sbl_contract(contract_id),

    accrual_date        DATE         NOT NULL,

    closing_price       NUMERIC(20,4) NOT NULL,

    qty_outstanding     BIGINT        NOT NULL,

    annual_rate         NUMERIC(10,6) NOT NULL,

    daily_fee_hkd       NUMERIC(20,4) NOT NULL,

    cumulative_fee_hkd  NUMERIC(20,4) NOT NULL,

    -- For floating rate

    index_rate          NUMERIC(10,6),

    spread_bps          INTEGER,

    settlement_status   VARCHAR(20)   NOT NULL DEFAULT 'ACCRUED',

    -- ACCRUED | SETTLED | DISPUTED | WAIVED

    settlement_batch_id UUID,

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()

    -- Append-only

);



CREATE TABLE fee_split (

    split_id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    accrual_id          UUID         NOT NULL REFERENCES fee_accrual(accrual_id),

    recipient_id        UUID         NOT NULL REFERENCES counterparty(id),

    recipient_role      VARCHAR(20)  NOT NULL,  -- LENDER | AGENT

    split_pct           NUMERIC(5,2) NOT NULL,

    split_amount_hkd    NUMERIC(20,4) NOT NULL

);



CREATE TABLE fee_statement (

    statement_id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    counterparty_id     UUID         NOT NULL REFERENCES counterparty(id),

    period_from         DATE         NOT NULL,

    period_to           DATE         NOT NULL,

    total_fee_hkd       NUMERIC(20,4) NOT NULL,

    status              VARCHAR(20)   NOT NULL,

    -- DRAFT | SENT | AGREED | DISPUTED | SETTLED

    sent_at             TIMESTAMPTZ,

    agreed_at           TIMESTAMPTZ,

    dispute_reason      TEXT,

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()

);

7. Module 6 — Corporate Actions Impact

Purpose

Detect corporate action events on securities with open loans and take the

appropriate contractual action. This module does not process the CA itself

(that is the CA system's job) — it manages the impact on open SBL contracts.



7.1 CA Event Types & SBL Impact

CA Type Impact on Open Loan SBL System Action

Cash dividend   Borrower owes lender equivalent cash (manufactured dividend)    Generate manufactured payment record; notify borrower

Stock dividend / bonus  Borrower owes additional shares Adjust contract quantity upward; notify Settlement System

Stock split Contract quantity changes per split ratio   Adjust quantity; update fee accrual basis

Consolidation   Contract quantity changes per consolidation ratio   Adjust quantity; update fee accrual basis

Rights issue    Lender may need shares to subscribe Issue recall notice (Module 3); or cash compensation if recall not possible

Scrip dividend  Lender chooses cash or scrip    Notify lender; record election; adjust contract if scrip chosen

Delisting / takeover    Loan cannot continue    Initiate early termination; cash settlement at scheme price

Voting / AGM    Lender may want to vote Alert lender T-5 before record date; initiate recall if lender requests

Name / code change  Stock code changes  Update contract.stock_code; update position records

7.2 CA Processing Workflow

CA Event Received (from CA feed / HKEX announcement)

        │

        ▼

Identify all active contracts on affected stock_code

        │

        ▼

For each affected contract:

  Determine action type (see table above)

        │

        ├── MANUFACTURED_PAYMENT:

        │     → Create manufactured_payment record

        │     → Amount = dividend_per_share × qty_outstanding

        │     → Payment due date = CA payment date

        │     → Notify borrower; notify Settlement System to collect

        │

        ├── QUANTITY_ADJUSTMENT:

        │     → Calculate new_qty = old_qty × adjustment_ratio

        │     → Update contract.quantity

        │     → Adjust collateral requirement (MTM will recalculate)

        │     → Log CA_ADJUSTED lifecycle event

        │

        ├── RECALL_REQUIRED:

        │     → Trigger recall workflow (Module 3)

        │     → recall_reason = CA_EVENT

        │     → Set return_deadline = ex_date - 1 business day

        │

        └── EARLY_TERMINATION:

              → Initiate contract close at scheme/takeover price

              → Notify both parties

              → Notify Settlement System

7.3 CA Alert Timeline

T-10 business days before ex-date:

  → Alert operations: "CA event approaching for open loans on [stock]"



T-5 business days before ex-date:

  → Alert lender: "Do you wish to recall shares to exercise voting rights?"



T-3 business days before ex-date:

  → If recall required and not yet initiated: auto-initiate recall

  → If manufactured payment: confirm payment details with borrower



T-1 business day before ex-date:

  → Final check: all required recalls initiated?

  → All manufactured payment amounts confirmed?



Ex-date:

  → Quantity adjustments applied (splits, bonus issues)

  → Manufactured payment due date set



Payment date:

  → Confirm manufactured payment received

  → If not received: escalate to operations

7.4 CA Data Schema

sql

CREATE TABLE ca_event (

    ca_event_id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    stock_code          VARCHAR(10)  NOT NULL,

    ca_type             VARCHAR(30)  NOT NULL,

    ex_date             DATE,

    record_date         DATE,

    payment_date        DATE,

    adjustment_ratio    NUMERIC(10,6),  -- for splits/consolidations/bonus

    cash_amount         NUMERIC(20,4),  -- for dividends

    currency            VARCHAR(3),

    description         TEXT,

    source              VARCHAR(50),    -- HKEX_FEED | MANUAL

    processed           BOOLEAN         NOT NULL DEFAULT FALSE,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()

);



CREATE TABLE ca_contract_impact (

    impact_id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    ca_event_id         UUID         NOT NULL REFERENCES ca_event(ca_event_id),

    contract_id         UUID         NOT NULL REFERENCES sbl_contract(contract_id),

    action_type         VARCHAR(30)  NOT NULL,

    -- MANUFACTURED_PAYMENT | QTY_ADJUSTMENT | RECALL | EARLY_TERMINATION | ALERT_ONLY

    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',

    old_quantity        BIGINT,

    new_quantity        BIGINT,

    payment_amount_hkd  NUMERIC(20,4),

    payment_due_date    DATE,

    payment_status      VARCHAR(20),

    -- PENDING | RECEIVED | OVERDUE

    recall_id           UUID         REFERENCES recall_notice(recall_id),

    notes               TEXT,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    processed_at        TIMESTAMPTZ

);

8. Module 7 — Regulatory Reporting

Purpose

Automate all HK regulatory submissions. The SBL system owns this because it holds

the authoritative position and contract data needed for each report.



8.1 SFC Short Position Reporting (SPRS)

Basis: SF (Short Position Reporting) Rules L.N.48/2012



Threshold (lower of):



0.02% of total issued shares × closing price, OR

HK$30 million in value

For ETFs/REITs (security_type = TRST): HK$30M threshold only.



Frequency:



Weekly (default): as at last trading day of each week

Daily (triggered): if threshold breached on any day during the week

Deadline: Midnight Tuesday HKT (weekly); next trading day (daily)



Net Short Position Calculation

Net Short Position(stock_code) =



  Total quantity on active SBL contracts (ON_LOAN) for short sale borrows

  [i.e. contracts where is_short_sale_borrow = TRUE]



  PLUS any other short positions sourced from Trading System

  (Trading System pushes net short position data to SBL system daily)



  MINUS any long positions that offset (per assurance_basis used)

Important: The assurance_basis used for SPRS must be consistent with

the basis used for pre-trade covered short selling checks (SFO s.170).



CSV File Specification

Filename: yyyymmdd[SPRID].csv  e.g. 20260901_SPRID12345.csv



Fields (per row, comma-separated, no header row):

  1. SPRID                    (Short Position Reporting ID — from SFC registration)

  2. Legal entity name

  3. Stock code               (no leading zeros, e.g. "5" not "00005")

  4. Stock name               (exact name from HKEX Designated Securities list)

  5. Net short position (shares)  (integer, no commas)

  6. Net short position value (HKD, up to 3 decimal places, no commas)



Limits: max 5,000 rows per file; max 5MB

If exceeded: split into multiple files

SPRS Submission Workflow

Friday EOD (or daily if triggered):

  1. Calculate net short position per Designated Security

  2. Apply threshold filter

  3. Generate CSV file

  4. Validate: stock names match HKEX DS list exactly

  5. Submit via SPRS web portal (HTTPS)

  6. Store: submission reference number + acknowledgement

  7. Archive CSV file (7-year retention)



If resubmission needed:

  → Include previous submission reference + reason for resubmission

8.2 IRD Annual Return — Form SBUL 1

Basis: SDO Cap.117 s.19(13); IRD Stamping Circular 01/2026

(changed from semi-annual to annual filing)



Period: 1 January – 31 December



Deadline: 31 January of following year



Penalty: HK$5,000 for late filing (s.19(15))



Transaction Classification (4 Non-Exempt Categories)

For each transaction under each registered SBLA:



Category 1: Effected BEFORE SBLA registration

  → Stamp duty payable; report in Schedule 1



Category 2: Borrowed stocks NOT used for specified purposes

  → Stamp duty payable; report in Schedule 2

  → Specified purposes: short selling, on-lending, market making,

    arbitrage, hedging, index rebalancing



Category 3: NOT returned at end of agreed term or on demand

  → Stamp duty payable; report in Schedule 3



Category 4: Settled by means OTHER than stock return

  → e.g. cash settlement, netting

  → Stamp duty payable; report in Schedule 4



All other transactions: EXEMPT (no stamp duty)

NIL return: still required if any transactions occurred during the year

SBUL 1 Generation Workflow

Throughout the year:

  → Tag each contract against its SBA number (IRD-issued SBLA registration number)

  → Flag any transaction matching Categories 1–4 above

  → Maintain Stock Borrowing Ledger (SBUL 3) per SBLA



January (annual):

  1. Aggregate all transactions for the calendar year per SBLA

  2. Classify: exempt vs non-exempt (Categories 1–4)

  3. Generate draft Form SBUL 1 for compliance review

  4. Compliance sign-off

  5. Submit by 31 January (via IRD eTAX / iAM Smart / paper)

  6. Archive confirmation (7-year retention)

8.3 Cap.571X Disclosure Records

For regulated persons (substantial shareholders ≥5%, directors):



System generates disclosure record within 3 business days of each event:

  - Borrow initiated

  - Return completed

  - Shares deployed for prescribed purpose



Record fields:

  - Regulated person identity

  - Event type (BORROW | RETURN | DEPLOYMENT)

  - Event date

  - Security, quantity

  - Prescribed purpose (if deployment)

  - Disclosure deadline



Retention: 3 years from record creation

SFC access: provide within 5 business days of request

8.4 Lender Daily Summary

Generated: daily, dispatched by 10:00 HKT next business day



Content per lender:

  - All hold notices given (security, qty, time, borrower)

  - All blanket assurances given (security, pool size, borrower)

  - All borrows entered into (contract reference, security, qty, rate)

  - All recalls initiated

  - All returns received



Format: PDF + CSV (per lender preference)

Retention: 1 year

9. Module 8 — Audit Trail & Record Keeping

Purpose

Maintain an immutable, queryable audit log satisfying all SFC and IRD retention

requirements. Every state change in the system must be logged.



9.1 Retention Tiers

Record Type Retention   Basis

Documentary assurance records   1 year  Securities (Stock Lending) Rules s.5

Taped telephone records 1 year  SFC Guidance

All SBL transaction records (licensed corps)    7 years SFO s.130

SPRS submission records 7 years SFO s.130

Cap.571X disclosure records 3 years Cap.571X Rules

Form SBUL 1 & Stock Borrowing Ledger    7 years Best practice / SFO s.130

Collateral MTM history  7 years SFO s.130

Margin call records 7 years SFO s.130

9.2 Audit Log Schema

sql

CREATE TABLE audit_log (

    log_id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    entity_type         VARCHAR(50)  NOT NULL,

    -- CONTRACT | LOCATE | COLLATERAL | ASSURANCE | FEE | CA | REPORT | SBLA

    entity_id           VARCHAR(100) NOT NULL,

    event_type          VARCHAR(100) NOT NULL,

    -- CREATED | STATE_CHANGED | MARGIN_CALL_ISSUED | MARGIN_CALL_RESOLVED

    -- RECALLED | RETURNED | RERATED | ROLLED_OVER | CA_ADJUSTED

    -- REPORT_SUBMITTED | SBLA_REGISTERED | DEEMED_ACQUISITION | etc.

    event_timestamp     TIMESTAMPTZ  NOT NULL,

    actor_id            VARCHAR(100) NOT NULL,  -- user ID or system process name

    actor_type          VARCHAR(20)  NOT NULL,  -- USER | SYSTEM | EXTERNAL

    old_value           JSONB,

    new_value           JSONB,

    regulatory_ref      VARCHAR(200),

    -- e.g. "SFO s.171" | "HKEX Schedule 6 Reg.9" | "Cap.571X SBL Rules"

    retention_tier      VARCHAR(5)   NOT NULL,  -- '1Y' | '3Y' | '7Y'

    retention_expiry    DATE         NOT NULL,

    source_ip           VARCHAR(45),

    session_id          VARCHAR(100)

    -- NO UPDATE or DELETE ever permitted on this table

    -- Enforced at DB level: revoke UPDATE, DELETE privileges from all roles

);



CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id);

CREATE INDEX idx_audit_timestamp ON audit_log(event_timestamp);

CREATE INDEX idx_audit_retention ON audit_log(retention_expiry)

    WHERE retention_expiry < NOW();  -- for archival jobs

9.3 SFC On-Demand Export

Compliance officer triggers export:

  Input: entity_type, entity_id (or date range + counterparty)

  Output: PDF report + CSV data export



System must produce all records within 5 business days of SFC request.

Export includes: all audit_log entries + linked documents

  (assurance records, contracts, collateral history, fee statements,

   SPRS submissions, SBUL 1 filings)



Access control: COMPLIANCE_OFFICER role required

All export actions are themselves logged to audit_log

10. Core Data Model

Entity Relationship Overview

counterparty ──────────────────────────────────────────────────────┐

    │                                                               │

    │ 1:N                                                           │

    ▼                                                               │

  sbla ──────────────────────────────────────────────────────────┐ │

    │                                                             │ │

    │ 1:N                                                         │ │

    ▼                                                             │ │

locate_request ──────────────────────────────────────────────┐   │ │

    │                                                         │   │ │

    │ 1:1                                                     │   │ │

    ▼                                                         │   │ │

documentary_assurance                                         │   │ │

    │                                                         │   │ │

    │ 1:1                                                     │   │ │

    ▼                                                         │   │ │

sbl_contract ◄───────────────────────────────────────────────┘   │ │

    │           (locate_id FK)                                     │ │

    │                                                              │ │

    ├──► lifecycle_event (1:N)                                     │ │

    ├──► recall_notice (1:N)                                       │ │

    ├──► collateral_position (1:N) ──► collateral_mtm_history      │ │

    ├──► margin_call (1:N)                                         │ │

    ├──► fee_accrual (1:N) ──► fee_split                           │ │

    └──► ca_contract_impact (1:N) ──► ca_event                     │ │

                                                                   │ │

position ──────────────────────────────────────────────────────────┘ │

    │                                                                 │

lendable_pool_snapshot                                               │

                                                                     │

short_position_report ───────────────────────────────────────────────┘

audit_log (references all entities by entity_type + entity_id)

11. Integration Boundaries

What This System Receives

Source  Data    Trigger

Trading System  Locate requests Real-time (per order)

Trading System  Net short position data EOD

Settlement System   Settlement confirmations (open/close leg)   Real-time event

Settlement System   Settled position updates    Real-time event

Market Data Feed    EOD closing prices  ~16:15 HKT daily

Market Data Feed    Intraday prices (for collateral)    Every 15 min

Market Data Feed    FX rates (RMB/USD vs HKD)   EOD + intraday

Market Data Feed    Floating rate index (HIBOR) Daily

HKEX    Designated Securities list  Daily 07:30 HKT + ad hoc

CA Feed / HKEX  Corporate action events Daily + ad hoc

What This System Sends

Destination Data    Trigger

Trading System  Locate approval / rejection Real-time (per request)

Trading System  Designated Securities changes   On sync

Settlement System   Contract open/close intent  On contract state change

Settlement System   Collateral transfer instructions    On margin call / substitution

Settlement System   Manufactured dividend payment   On CA event

Counterparties  Margin call notifications   On MTM breach

Counterparties  Recall notices  On recall event

Counterparties  Fee statements  Monthly / on close

Counterparties  Lender daily summary    By 10:00


