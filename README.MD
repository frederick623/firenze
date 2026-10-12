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

### Run demo scenario
Windows:
```powershell
.\run.ps1
```

macOS/Linux:
```bash
./run.sh
```

Run output artifacts:
- SQLite demo database: `out/sbl-demo.db`
- Regulatory CSV output: `out/reports/`

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

If no DB settings are provided, the app defaults to `jdbc:sqlite:out/sbl-demo.db`.

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

## 3. Component / Microservice View

The current implementation is a **modular monolith**. Each business module is isolated in its own service class and can be extracted into a microservice boundary later.

| Module | Responsibility | Core service class |
|---|---|---|
| 1. Locate Management | Locate approval/rejection, stock eligibility, limits, assurance checks | `LocateService` |
| 2. Position & Inventory | Lendable pool and inventory snapshots | `InventoryService` |
| 3. Contract Lifecycle | Open/return/recall lifecycle, status transitions, regulated-person timing, buy-in escalation | `ContractService` |
| 4. Collateral Management | Collateral posting/substitution/release, daily MTM, margin calls | `CollateralService` |
| 5. Fee & Billing | Daily accruals, split allocation, fee statements and settlement | `FeeService` |
| 6. Corporate Actions | Event ingestion and contract impact processing (dividends/splits/rights/delisting, etc.) | `CorporateActionService` |
| 7. Regulatory Reporting | SPRS short-position reporting, IRD SBUL1 classification, Cap.571X records | `ReportingService` |
| 8. Audit Trail | Immutable audit logging and retention tagging | `AuditService` |

## 4. Codebase Structure

```text
src/main/kotlin/com/piscessoft/sbl/
  app/      # Demo app entrypoint and service wiring (Main.kt)
  db/       # SQLite connection + schema bootstrap
  model/    # Domain enums and data models
  repo/     # JDBC repositories (persistence adapters)
  service/  # Business modules (1..8)
  util/     # Time, money, IDs
```

Key entrypoints:
- `com.piscessoft.sbl.app.MainKt` (demo application)
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
