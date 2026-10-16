package com.piscessoft.sbl.repo

import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.model.AllocationSource
import com.piscessoft.sbl.model.ContractAllocation
import com.piscessoft.sbl.util.SblTime
import java.sql.ResultSet
import java.time.LocalDate

class ContractAllocationRepo(private val db: Database) {
    fun insert(a: ContractAllocation) = db.update(
        """INSERT INTO contract_allocation (allocation_id,contract_id,stock_code,source_type,
           source_owner_id,position_id,source_contract_id,quantity,recalled_qty,effective_from,
           effective_to,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        a.allocationId, a.contractId, a.stockCode, a.sourceType.name, a.sourceOwnerId,
        a.positionId, a.sourceContractId, a.quantity, a.recalledQty,
        SblTime.formatDate(a.effectiveFrom), SblTime.formatDate(a.effectiveTo),
        SblTime.formatTs(a.createdAt), SblTime.formatTs(a.updatedAt)
    )

    fun update(a: ContractAllocation) = db.update(
        """UPDATE contract_allocation SET quantity=?, recalled_qty=?, effective_to=?, updated_at=?
           WHERE allocation_id=?""",
        a.quantity, a.recalledQty, SblTime.formatDate(a.effectiveTo),
        SblTime.formatTs(SblTime.now()), a.allocationId
    )

    fun find(id: String): ContractAllocation? =
        db.queryOne("SELECT * FROM contract_allocation WHERE allocation_id=?", id) { map(it) }

    /** Full history (current and closed rows) for a contract. */
    fun forContract(contractId: String): List<ContractAllocation> =
        db.query("""SELECT * FROM contract_allocation WHERE contract_id=?
                    ORDER BY effective_from, created_at""", contractId) { map(it) }

    fun currentForContract(contractId: String): List<ContractAllocation> =
        db.query("""SELECT * FROM contract_allocation WHERE contract_id=? AND effective_to IS NULL
                    ORDER BY created_at""", contractId) { map(it) }

    /** Rows in force on [date]: effective_from <= date < effective_to. */
    fun activeOn(contractId: String, date: LocalDate): List<ContractAllocation> =
        forContract(contractId).filter { a ->
            !date.isBefore(a.effectiveFrom) && (a.effectiveTo == null || date.isBefore(a.effectiveTo))
        }

    fun currentForPosition(positionId: String): List<ContractAllocation> =
        db.query("""SELECT * FROM contract_allocation WHERE position_id=? AND effective_to IS NULL
                    ORDER BY created_at""", positionId) { map(it) }

    fun currentForSourceContract(sourceContractId: String): List<ContractAllocation> =
        db.query("""SELECT * FROM contract_allocation WHERE source_contract_id=? AND effective_to IS NULL
                    ORDER BY created_at""", sourceContractId) { map(it) }

    fun current(): List<ContractAllocation> =
        db.query("SELECT * FROM contract_allocation WHERE effective_to IS NULL ORDER BY created_at") { map(it) }

    private fun map(rs: ResultSet) = ContractAllocation(
        allocationId = rs.getString("allocation_id"),
        contractId = rs.getString("contract_id"),
        stockCode = rs.getString("stock_code"),
        sourceType = AllocationSource.valueOf(rs.getString("source_type")),
        sourceOwnerId = rs.getString("source_owner_id"),
        positionId = rs.getString("position_id"),
        sourceContractId = rs.getString("source_contract_id"),
        quantity = rs.getLong("quantity"),
        recalledQty = rs.getLong("recalled_qty"),
        effectiveFrom = SblTime.parseDate(rs.getString("effective_from"))!!,
        effectiveTo = SblTime.parseDate(rs.getString("effective_to")),
        createdAt = SblTime.parseTs(rs.getString("created_at"))!!,
        updatedAt = SblTime.parseTs(rs.getString("updated_at"))!!
    )
}
