package com.piscessoft.sbl.app

import com.piscessoft.sbl.api.ApiKeyAuth
import com.piscessoft.sbl.api.ApiServer
import com.piscessoft.sbl.api.Principal
import com.piscessoft.sbl.api.Role
import com.piscessoft.sbl.api.SblApi

/**
 * SBL Core system entry point. Opens the database, applies the schema (including the
 * static collateral-type configuration) and wires the services. No dummy data is loaded
 * here - use the separate demo seeder (com.piscessoft.sbl.seed.DemoDataSeederKt).
 *
 * With `--serve` the REST API is started (see [SblApi]) and the process keeps running.
 */
object App {

    fun run(args: Array<String> = emptyArray()) {
        if ("--serve" in args) {
            serve(args)
            return
        }
        DbBootstrap.open(args).use { db ->
            val services = SblServices(db)
            val counterparties = db.queryLong("SELECT COUNT(*) FROM counterparty")
            val contracts = db.queryLong("SELECT COUNT(*) FROM sbl_contract")
            println("SBL Core ready: counterparties=$counterparties contracts=$contracts " +
                "auditRecords=${services.audit.total()}")
        }
    }

    private fun serve(args: Array<String>) {
        val host = argValue(args, "--http-host") ?: System.getenv("SBL_HTTP_HOST") ?: "127.0.0.1"
        val portText = argValue(args, "--http-port") ?: System.getenv("SBL_HTTP_PORT") ?: "8080"
        val port = portText.toIntOrNull()?.takeIf { it in 0..65535 } ?: error("Invalid HTTP port '$portText'")
        val auth = loadApiKeys()

        val db = DbBootstrap.open(args)
        val services = SblServices(db)
        val server = ApiServer(db, services.audit, auth, SblApi(services).routes())
        val address = server.start(host, port)
        Runtime.getRuntime().addShutdownHook(Thread {
            server.stop()
            db.close()
        })
        println("SBL REST API listening on http://${address.hostString}:${address.port}/api/v1 (Ctrl+C to stop)")
    }

    /** API keys come from the environment only, never from CLI flags (visible in process lists). */
    private fun loadApiKeys(): ApiKeyAuth {
        val spec = System.getenv("SBL_API_KEYS")
        if (!spec.isNullOrBlank()) return ApiKeyAuth.parse(spec)
        val key = ApiKeyAuth.randomKey()
        println("WARNING: SBL_API_KEYS is not set. Generated a one-off OPERATOR key for this session:")
        println("  X-API-Key: $key")
        return ApiKeyAuth.of(key to Principal("operator", Role.OPERATOR, null))
    }

    private fun argValue(args: Array<String>, flag: String): String? {
        args.forEachIndexed { i, a ->
            if (a.startsWith("$flag=")) return a.substringAfter("=")
            if (a == flag) return args.getOrNull(i + 1) ?: error("Missing value for $flag")
        }
        return null
    }
}

fun main(args: Array<String>) {
    App.run(args)
}
