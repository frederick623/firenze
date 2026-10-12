package com.piscessoft.sbl.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Thin JDBC access layer. Holds a single JDBC connection (the SBL core is modelled as
 * a single-writer service) and provides small transaction/query helpers so repositories
 * stay terse.
 */
enum class DbConnector(val id: String, val driverClass: String) {
    SQLITE("sqlite", "org.sqlite.JDBC"),
    POSTGRESQL("postgresql", "org.postgresql.Driver");

    companion object {
        fun parse(value: String?): DbConnector =
            when (value?.trim()?.lowercase()) {
                null, "", "sqlite", "org.sqlite.jdbc" -> SQLITE
                "postgres", "postgresql", "org.postgresql.driver" -> POSTGRESQL
                else -> error("Unsupported DB connector '$value'. Use sqlite or postgresql.")
            }
    }
}

data class DbConnectionConfig(
    val connector: DbConnector,
    val jdbcUrl: String,
    val username: String? = null,
    val password: String? = null
)

class Database private constructor(val conn: Connection, val connector: DbConnector) : AutoCloseable {

    companion object {
        /** Opens a file-backed database (created if missing) and applies the schema. */
        fun open(path: String): Database = open(
            DbConnectionConfig(
                connector = DbConnector.SQLITE,
                jdbcUrl = "jdbc:sqlite:$path"
            )
        )

        /** Opens a shared in-memory database (handy for tests). */
        fun openInMemory(): Database = open(
            DbConnectionConfig(
                connector = DbConnector.SQLITE,
                jdbcUrl = "jdbc:sqlite::memory:"
            )
        )

        /** Opens a JDBC connection using the selected connector and connection string. */
        fun open(config: DbConnectionConfig): Database {
            Class.forName(config.connector.driverClass)
            val c = if (!config.username.isNullOrBlank() || config.password != null) {
                DriverManager.getConnection(config.jdbcUrl, config.username ?: "", config.password ?: "")
            } else {
                DriverManager.getConnection(config.jdbcUrl)
            }
            return init(c, config.connector)
        }

        private fun init(c: Connection, connector: DbConnector): Database {
            c.autoCommit = true
            if (connector == DbConnector.SQLITE) {
                c.createStatement().use { st ->
                    st.execute("PRAGMA journal_mode=WAL")
                    st.execute("PRAGMA foreign_keys=ON")
                    st.execute("PRAGMA busy_timeout=5000")
                }
            }
            val db = Database(c, connector)
            Schema.apply(db)
            return db
        }
    }

    /** Runs [block] inside a transaction, committing on success and rolling back on error. */
    fun <T> tx(block: (Connection) -> T): T {
        val previous = conn.autoCommit
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            return result
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = previous
        }
    }

    fun exec(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    fun update(sql: String, vararg params: Any?): Int =
        conn.prepareStatement(sql).use { ps ->
            bind(ps, params)
            ps.executeUpdate()
        }

    fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): List<T> =
        conn.prepareStatement(sql).use { ps ->
            bind(ps, params)
            ps.executeQuery().use { rs ->
                val out = ArrayList<T>()
                while (rs.next()) out.add(mapper(rs))
                out
            }
        }

    fun <T> queryOne(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): T? =
        query(sql, *params, mapper = mapper).firstOrNull()

    fun queryLong(sql: String, vararg params: Any?): Long =
        conn.prepareStatement(sql).use { ps ->
            bind(ps, params)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

    private fun bind(ps: PreparedStatement, params: Array<out Any?>) {
        params.forEachIndexed { i, p ->
            when (p) {
                null -> ps.setObject(i + 1, null)
                is Boolean -> ps.setInt(i + 1, if (p) 1 else 0)
                is Int -> ps.setInt(i + 1, p)
                is Long -> ps.setLong(i + 1, p)
                is Double -> ps.setDouble(i + 1, p)
                else -> ps.setString(i + 1, p.toString())
            }
        }
    }

    override fun close() { conn.close() }
}

// --- ResultSet convenience extensions ---

fun ResultSet.stringOrNull(col: String): String? = getString(col)
fun ResultSet.boolean(col: String): Boolean {
    val v = getObject(col) ?: return false
    return when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> {
            when (v.trim().lowercase()) {
                "1", "t", "true", "y", "yes" -> true
                else -> false
            }
        }
        else -> getBoolean(col)
    }
}
fun ResultSet.longOf(col: String): Long = getLong(col)
