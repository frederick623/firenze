# SBL Core (Kotlin + SQLite)

This repository implements a Hong Kong **Securities Borrowing and Lending (SBL) Core** service in Kotlin, using **SQLite** as the current persistence layer (no external SQL server required).

The system is the operational source of truth for:
- what is lendable,
- what is on loan,
- whether loans are sufficiently collateralized,
- what fees and regulatory outputs are owed.

## 1. Setup

### Prerequisites
- Git
- PowerShell (Windows) or Bash (macOS/Linux)
- Python (used by toolchain bootstrap to install `jdk4py`)
- Network access to the configured artifact repositories

### Windows (PowerShell)
From the repo root:

```powershell
.\scripts\setup-toolchain.ps1
```

This populates local runtime dependencies under `.tooling\`.

### macOS / Linux (Bash)
From the repo root:

```bash
chmod +x scripts/setup-toolchain.sh build.sh run.sh test.sh
./scripts/setup-toolchain.sh
```

Optional environment overrides for `setup-toolchain.sh`:
- `SBL_CA_CERT` (corporate CA certificate path for curl)
- `SBL_PYTHON` (python interpreter, default `python3`)

## 2. Build, Run, Test

### Build
Windows:
```powershell
.\build.ps1
```

macOS/Linux:
```bash
./build.sh
```

Build output:
- `out/sbl.jar`

### Run the system
Starting the system only opens the database and applies the schema (plus the static
collateral-type/haircut configuration). **No dummy data is loaded** and the existing
database is never deleted.

Windows:
```powershell
.\run.ps1
```

macOS/Linux:
```bash
./run.sh
```

### Seed demo data (separate step)
Dummy data is loaded by a separate tool, `com.piscessoft.sbl.seed.DemoDataSeederKt`.
It is skipped if the database already contains counterparties.

```powershell
.\seed.ps1               # reference data only: counterparties, SBLAs, securities, prices, rates, inventory
.\seed.ps1 --scenario    # reference data + multi-day demo scenario (contracts, collateral, fees, CAs, reports)
```

macOS/Linux: `./seed.sh [--scenario]`. The seeder accepts the same DB flags as `run`.
To start from scratch with SQLite, delete `out/sbl.db` first.

Output artifacts:
- SQLite database: `out/sbl.db`
- Regulatory CSV output (scenario only): `out/reports/`

### Switching connector and connection string
The app accepts DB connector and JDBC URL via **CLI flags** or **environment variables**.

Supported connectors:
- `sqlite` (default)
- `postgresql`

Configuration options:
- `--db-connector` or `SBL_DB_CONNECTOR`
- `--db-url` or `SBL_DB_URL`
- `--db-user` or `SBL_DB_USER`
- `--db-password` or `SBL_DB_PASSWORD`

Examples:

SQLite (explicit URL):
```bash
./run.sh --db-connector sqlite --db-url jdbc:sqlite:out/custom-sbl.db
```

PostgreSQL:
```bash
./run.sh --db-connector postgresql \
  --db-url jdbc:postgresql://localhost:5432/sbl_core \
  --db-user sbl_user \
  --db-password sbl_pass
```

If no DB settings are provided, the app defaults to `jdbc:sqlite:out/sbl.db`.

### Run test suite
Windows:
```powershell
.\test.ps1
```

macOS/Linux:
```bash
./test.sh
```

The tests are self-contained in `src/test/kotlin/com/piscessoft/sbl/SblTests.kt` and run against in-memory SQLite.

### REST API

Start the HTTP server (JDK built-in `HttpServer`, no extra dependencies):
```powershell
$env:SBL_API_KEYS = "<opKey>:OPERATOR:ops1;<b1Key>:USER:B1"   # keys >= 16 chars
.\run.ps1 --serve --http-port 8080        # also: --http-host / SBL_HTTP_HOST, SBL_HTTP_PORT
```
If `SBL_API_KEYS` is unset, a one-off OPERATOR key is generated and printed at startup.
Send the key as `X-API-Key: <key>` or `Authorization: Bearer <key>`.

- **USER** keys are bound to a counterparty id and only see/act on their own locates, contracts, collateral, margin calls and statements.
- **OPERATOR** keys can do everything, including reference data, market data, corporate actions, reports, EOD batch and audit.

Book a deal (as borrower `B1`):
```bash
curl -X POST localhost:8080/api/v1/locates -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"stockCode":"00700","quantity":100000}'
curl -X POST localhost:8080/api/v1/contracts -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"locateId":"<locateId>","lenderId":"L1"}'
curl -X POST localhost:8080/api/v1/counterparties/B1/collateral -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"collateralType":"CASH_HKD","assetId":"HKD","quantity":40000000}'
curl localhost:8080/api/v1/counterparties/B1/collateral-account -H "X-API-Key: $KEY"
```

Collateral is held per counterparty account (cash perspective), not per contract. One account covers the borrower's whole book, so a surplus on one loan offsets a shortfall on another. There is at most one open margin call per account. `GET /counterparties/{id}/collateral-account` shows each collateral line, the cover by type, the requirement per contract, and the net position.

Collateral rules:
- **Valuation.** The system values collateral at face value for cash and bank guarantees, and at the latest market price otherwise. Posting is rejected if there is no price or FX rate.
- **Eligibility.** A type eligible for no kind of borrow is rejected. A type eligible only for general or only for short-sale borrows counts only up to that kind's requirement.
- **Concentration.** A type with `maxConcentration` counts only up to that share of the account's total requirement.
- **Client stock.** Settled stock in the borrower's own client accounts (`CLIENT_DISCRETIONARY`, `CLIENT_NON_DISC`) is counted automatically at the `MARGIN_EQTY` haircut and cap. Internal desks are excluded.
- **Wrong-way.** A security borrowed by the account, or any security with the same `issuerId`, cannot be pledged. Wrong-way lines that are already posted or held are excluded from cover.
- **Settlement.** An OPEN leg settles only if the account covers its delivered contracts plus the new one. Release and substitution must keep cover for all booked contracts.
- **Operator-only fields.** `collateralRatio` (floor 1.05 for short sales, 1.00 for general borrows) and `shortSale:false` on `POST /contracts` can only be set by an operator.
- **Escalation.** Margin calls still PENDING after their deadline are ESCALATED by EOD (or by `POST /operations/margin-calls/escalate`).
- **Missing prices.** Contracts with no price or FX are reported as `mtmUnpriced`. While any contract is unpriced, the account cannot release collateral and gets no margin call.

Migration: on startup, any old contract-level `collateral_position`, `margin_call` and `collateral_mtm_history` tables are renamed to `*_legacy` and their rows are copied to the borrower's account. Old MTM history moves to `contract_mtm_history`. Borrow-ins from street lenders are not mirrored. Their account key is the internal desk, so that desk nets across all street lenders.

| Area | Endpoints (prefix `/api/v1`) | Access |
|---|---|---|
| System | `GET /health`, `GET /me` | public / any |
| Reference data | `/counterparties[/{id}[/sblas]]`, `/securities[/{stockCode}[/ca-restriction]]`, `/collateral-types`, `/rate-schedules`, `GET /rates/quote` | reads: any, writes: operator |
| Market data | `GET /market/prices/{stockCode}[/history]`, `GET /market/quotes/{stockCode}`, `PUT /market/prices/{stockCode}`, `POST /market/prices` (bulk, optional `revalue`), `PUT /market/fx/{ccy}`, `PUT /market/indices/{index}`, `POST /operations/revalue` | writes: operator |
| Inventory | `GET /inventory/pools/{stockCode}`, `GET /inventory/hot-stocks`, `POST /inventory/pools/{stockCode}/snapshots`, `/positions`, `POST /positions/pledges` | positions: operator |
| Locates | `POST/GET /locates`, `GET /locates/{id}`, `POST /locates/{id}/cancel` | any (scoped) |
| Contracts | `POST/GET /contracts`, `GET /contracts/{id}[/events\|/recalls\|/rate-history]`, `POST /contracts/{id}/returns\|close\|recalls\|deployment` | any (scoped) |
| Contracts (ops) | `POST /contracts/{id}/rerate\|rollover\|terminate` | operator |
| Collateral | `POST/GET /counterparties/{id}/collateral`, `GET /counterparties/{id}/collateral-account\|mtm-history`, `GET /contracts/{id}/valuation\|mtm-history`, `POST /collateral/{positionId}/substitute\|release`, `GET /margin-calls[/{id}]`, `POST /margin-calls/{id}/resolve` | resolve: operator |
| Fees | `GET /contracts/{id}/accruals`, `POST /statements`, `GET /statements/{id}`, `POST /statements/{id}/send\|agree\|dispute\|settle` | create/send/settle: operator |
| Corporate actions | `POST /corporate-actions`, `GET /corporate-actions/pending`, `POST /corporate-actions/process`, `GET /corporate-actions/{id}[/impacts]`, `POST /corporate-actions/{id}/process` | operator |
| Reports | `POST /reports/short-positions\|sprs\|sbul1\|cap571x`, `GET /reports/lender-summary` | operator (summary: scoped) |
| Operations | `POST /operations/eod` and individual jobs under `/operations/...` | operator |
| Audit | `GET /audit/{entityType}/{entityId}`, `POST /audit/{entityType}/{entityId}/export` | operator |

Errors are JSON `{"error": {"status": 409, "message": "..."}}`: 400 invalid input, 401/403 auth, 404 not found (also for other parties' records), 409 invalid state, 422 business rule rejection. Every successful call is written to the audit trail (`entity_type = API`).

## 3. Component / Microservice View

The current implementation is a **modular monolith**. Each business module is isolated in its own service class and can be extracted into a microservice boundary later.

| Module | Responsibility | Core service class |
|---|---|---|
| 1. Locate Management | Locate approval/rejection, stock eligibility, limits, assurance checks | `LocateService` |
| 2. Position & Inventory | Lendable pool and inventory snapshots | `InventoryService` |
| 3. Contract Lifecycle | Open/return/recall lifecycle, status transitions, regulated-person timing, buy-in escalation | `ContractService` |
| 4. Collateral Management | Collateral posting/substitution/release, daily MTM, margin calls | `CollateralService` |
| 4a. Pricing | Instrument price store/history, quotes with FX, MTM of contracts, inventory positions and collateral | `PricingService` |
| 5. Fee & Billing | Daily accruals, split allocation, fee statements and settlement | `FeeService` |
| 6. Corporate Actions | Event ingestion and contract impact processing (dividends/splits/rights/delisting, etc.) | `CorporateActionService` |
| 7. Regulatory Reporting | SPRS short-position reporting, IRD SBUL1 classification, Cap.571X records | `ReportingService` |
| 8. Audit Trail | Immutable audit logging and retention tagging | `AuditService` |

## 4. Codebase Structure

```text
src/main/kotlin/com/piscessoft/sbl/
  app/      # System entrypoint (Main.kt), DB bootstrap and service wiring (Bootstrap.kt)
  api/      # REST API: HTTP server/auth/routing (Http.kt), JSON codec (Json.kt), endpoints (SblApi.kt)
  seed/     # Separate demo data seeder + demo scenario
  db/       # SQLite connection + schema bootstrap
  model/    # Domain enums and data models
  repo/     # JDBC repositories (persistence adapters)
  service/  # Business modules (1..8)
  util/     # Time, money, IDs
```

Key entrypoints:
- `com.piscessoft.sbl.app.MainKt` (system startup, no dummy data)
- `com.piscessoft.sbl.seed.DemoDataSeederKt` (demo data seeder)
- `com.piscessoft.sbl.SblTests` (self-contained assertions)

## 5. Data Model Notes (SQLite)

Schema is created by `db/Schema.kt` at startup.

Type mapping strategy:
- `UUID -> TEXT`
- `TIMESTAMPTZ -> TEXT` (ISO-8601)
- `BIGINT -> INTEGER`
- `NUMERIC -> TEXT` (BigDecimal-safe)
- `BOOLEAN -> INTEGER` (`0/1`)
- `JSONB -> TEXT`

This keeps deterministic financial precision while remaining portable on SQLite.
