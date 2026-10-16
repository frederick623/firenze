package com.piscessoft.sbl.model

/** Domain enumerations. Enum names match the TEXT values persisted in SQLite. */

enum class CounterpartyType { LENDER, BORROWER, AGENT, INTERNAL }

enum class BorrowerTier { TIER_1, TIER_2, TIER_3 }

enum class FeeSettlementBasis { MONTHLY, WEEKLY, ON_CLOSE }

enum class LocateStatus { REQUESTED, APPROVED, PARTIAL, REJECTED, EXPIRED, CONVERTED, CANCELLED }

enum class AssuranceType { HOLD_NOTICE, BLANKET_ASSURANCE, BORROW }

enum class AssuranceBasis { TRADING_BOOK, TRADING_UNIT, LEGAL_ENTITY }

enum class FeeType { FIXED, FLOATING, NEGOTIATED }

/** Benchmark rate a FLOATING fee is priced against (index + spread). */
enum class FloatingIndex { HIBOR_1M, HIBOR_3M, SOFR, EURIBOR }

enum class AccountType(val lendable: Boolean) {
    PROPRIETARY(true),
    CLIENT_DISCRETIONARY(true),
    CLIENT_NON_DISC(false),
    LENDING_POOL(true),
    PLEDGED(false),
    CA_RESTRICTED(false),
    ON_LOAN(false),
    RECALLED(false),
    COLLATERAL_HELD(false)
}

enum class SettlementStatus { SETTLED, PENDING_T1, PENDING_T2 }

/** OPEN = stock delivered lender -> borrower; RETURN = stock redelivered borrower -> lender. */
enum class LegType { OPEN, RETURN }

enum class LegStatus { PENDING, SETTLED, FAILED, CANCELLED }

/** AUTO = system settles due legs itself; EXTERNAL = legs settle only on CCASS/custodian confirmation. */
enum class SettlementMode { AUTO, EXTERNAL }

enum class ContractStatus {
    PENDING_CONFIRM, CONFIRMED, ACTIVE, RECALLED, RETURN_PENDING,
    PARTIALLY_RETURNED, RERATED, ROLLED_OVER, CA_ADJUSTED,
    CLOSED, CANCELLED, DEFAULTED, SUSPENDED
}

enum class TradeType { FIXED, AUCTION, NEGOTIATED }

enum class TransactionModel { PRINCIPAL, AGENCY, CCP, BILATERAL, FOREIGN }

enum class RecallReason {
    LENDER_REQUEST, CA_EVENT, LIMIT_BREACH, REGULATORY,
    /** The inventory position supplying the loan was sold/withdrawn and could not be substituted. */
    SOURCE_SOLD,
    /** The street borrow-in supplying the loan was recalled and could not be substituted. */
    SOURCE_RECALLED
}

/** Which side of the book a contract sits on, from the firm's perspective. */
enum class BookSide { LOAN_OUT, BORROW_IN }

/** Where the shares delivered under a loan-out come from. */
enum class AllocationSource { FIRM, CLIENT_MARGIN, THIRD_PARTY, STREET_BORROW }

enum class RecallStatus { PENDING, PARTIALLY_RETURNED, RETURNED, OVERDUE, DEFAULTED }

enum class MarginCallStatus { PENDING, RESOLVED, ESCALATED, DEFAULTED }

enum class CollateralStatus { ACTIVE, RELEASED, SUBSTITUTED, LIQUIDATED }

enum class ResolutionMethod { CASH_TOPUP, SUBSTITUTION, EXTENSION, RECALL, DEFAULT }

enum class FeeSettlementStatus { ACCRUED, SETTLED, DISPUTED, WAIVED }

enum class StatementStatus { DRAFT, SENT, AGREED, DISPUTED, SETTLED }

enum class CaType {
    CASH_DIVIDEND, STOCK_DIVIDEND, STOCK_SPLIT, CONSOLIDATION,
    RIGHTS_ISSUE, SCRIP_DIVIDEND, DELISTING, VOTING, NAME_CHANGE
}

enum class CaActionType { MANUFACTURED_PAYMENT, QTY_ADJUSTMENT, RECALL, EARLY_TERMINATION, ALERT_ONLY }

enum class ActorType { USER, SYSTEM, EXTERNAL }

enum class SecurityType { EQTY, TRST }

/** Regulatory record-retention tiers (plan section 9.1). */
enum class RetentionTier(val code: String, val years: Int) {
    ONE_YEAR("1Y", 1),
    THREE_YEAR("3Y", 3),
    SEVEN_YEAR("7Y", 7);

    companion object {
        fun fromCode(code: String): RetentionTier = entries.first { it.code == code }
    }
}
