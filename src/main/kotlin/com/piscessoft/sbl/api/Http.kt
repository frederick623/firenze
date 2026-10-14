package com.piscessoft.sbl.api

import com.piscessoft.sbl.db.Database
import com.piscessoft.sbl.model.ActorType
import com.piscessoft.sbl.service.AuditService
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// ---- errors ----

class ApiException(val status: Int, message: String) : RuntimeException(message)

fun badRequest(msg: String): Nothing = throw ApiException(400, msg)
fun notFound(what: String): Nothing = throw ApiException(404, "$what not found")
fun forbidden(msg: String): Nothing = throw ApiException(403, msg)

// ---- security ----

/** OPERATOR = back-office / ops desk (all functions). USER = a counterparty (borrower/lender/agent). */
enum class Role { USER, OPERATOR }

/** Authenticated caller. A USER is always bound to exactly one counterparty id. */
data class Principal(val name: String, val role: Role, val counterpartyId: String?) {
    val isOperator: Boolean get() = role == Role.OPERATOR
}

enum class Access { PUBLIC, AUTHENTICATED, OPERATOR }

/**
 * API-key authentication. Keys are held only as SHA-256 digests and compared in constant
 * time. Configuration format (env `SBL_API_KEYS`): `key:ROLE:id;key:ROLE:id`, where `id`
 * is the operator name for OPERATOR keys and the counterparty id for USER keys.
 */
class ApiKeyAuth private constructor(private val keys: List<Pair<ByteArray, Principal>>) {

    fun authenticate(presented: String?): Principal? {
        if (presented.isNullOrBlank()) return null
        val digest = digest(presented.trim())
        var match: Principal? = null
        // Scan every entry so timing does not reveal which key (if any) matched.
        for ((k, p) in keys) if (MessageDigest.isEqual(k, digest)) match = p
        return match
    }

    companion object {
        private const val MIN_KEY_LENGTH = 16

        fun parse(spec: String): ApiKeyAuth {
            val entries = spec.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { idx, e ->
                val parts = e.split(':').map { it.trim() }
                require(parts.size == 3) { "SBL_API_KEYS entry #${idx + 1} must be key:ROLE:id" }
                val (key, roleText, id) = parts
                require(key.length >= MIN_KEY_LENGTH) {
                    "SBL_API_KEYS entry #${idx + 1}: key must be at least $MIN_KEY_LENGTH characters"
                }
                val role = Role.entries.firstOrNull { it.name.equals(roleText, ignoreCase = true) }
                    ?: throw IllegalArgumentException("SBL_API_KEYS entry #${idx + 1}: unknown role '$roleText'")
                require(id.isNotEmpty()) { "SBL_API_KEYS entry #${idx + 1}: id is required" }
                digest(key) to Principal(id, role, if (role == Role.USER) id else null)
            }
            require(entries.isNotEmpty()) { "SBL_API_KEYS contains no entries" }
            return ApiKeyAuth(entries)
        }

        fun of(vararg entries: Pair<String, Principal>): ApiKeyAuth =
            ApiKeyAuth(entries.map { digest(it.first) to it.second })

        fun randomKey(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun digest(s: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(UTF_8))
    }
}

// ---- request / response ----

class Reply(val status: Int, val body: Any?)

fun created(body: Any?) = Reply(201, body)

/** One inbound request, with typed, validating accessors for path, query and JSON body. */
class Call(
    val method: String,
    val path: String,
    private val pathParams: Map<String, String>,
    private val queryParams: Map<String, String>,
    private val rawBody: String,
    private val caller: Principal?
) {
    val principal: Principal get() = caller ?: throw ApiException(401, "Authentication required")

    @Suppress("UNCHECKED_CAST")
    val body: Map<String, Any?> by lazy {
        if (rawBody.isBlank()) emptyMap()
        else Json.parse(rawBody) as? Map<String, Any?> ?: badRequest("Request body must be a JSON object")
    }

    fun path(name: String): String = pathParams[name] ?: error("Route has no path parameter '$name'")

    fun query(name: String): String? = queryParams[name]?.trim()?.takeIf { it.isNotEmpty() }
        ?.also { if (it.length > MAX_STRING) badRequest("'$name' is too long") }

    fun queryDate(name: String): LocalDate? = query(name)?.let { parseDate(name, it) }

    fun queryDecimal(name: String): BigDecimal? = query(name)?.let {
        it.toBigDecimalOrNull() ?: badRequest("'$name' must be a number")
    }

    fun str(name: String): String = optStr(name) ?: badRequest("'$name' is required")

    fun optStr(name: String): String? = when (val v = body[name]) {
        null -> null
        is String -> v.trim().takeIf { it.isNotEmpty() }
            ?.also { if (it.length > MAX_STRING) badRequest("'$name' is too long") }
        else -> badRequest("'$name' must be a string")
    }

    fun long(name: String): Long = optLong(name) ?: badRequest("'$name' is required")

    fun optLong(name: String): Long? = when (val v = body[name]) {
        null -> null
        is BigDecimal -> try { v.longValueExact() } catch (e: ArithmeticException) {
            badRequest("'$name' must be a whole number")
        }
        else -> badRequest("'$name' must be a number")
    }

    fun optInt(name: String): Int? = optLong(name)?.let {
        if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) badRequest("'$name' is out of range") else it.toInt()
    }

    fun decimal(name: String): BigDecimal = optDecimal(name) ?: badRequest("'$name' is required")

    /** Accepts a JSON number or a numeric string (so clients can avoid float rounding). */
    fun optDecimal(name: String): BigDecimal? = when (val v = body[name]) {
        null -> null
        is BigDecimal -> v
        is String -> v.trim().toBigDecimalOrNull() ?: badRequest("'$name' must be a number")
        else -> badRequest("'$name' must be a number")
    }

    fun date(name: String): LocalDate = optDate(name) ?: badRequest("'$name' is required")

    fun optDate(name: String): LocalDate? = optStr(name)?.let { parseDate(name, it) }

    fun optBool(name: String): Boolean? = when (val v = body[name]) {
        null -> null
        is Boolean -> v
        else -> badRequest("'$name' must be true or false")
    }

    private fun parseDate(name: String, text: String): LocalDate = try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        badRequest("'$name' must be an ISO date (yyyy-MM-dd)")
    }

    companion object {
        const val MAX_STRING = 512
    }
}

inline fun <reified E : Enum<E>> parseEnum(field: String, text: String): E =
    enumValues<E>().firstOrNull { it.name.equals(text, ignoreCase = true) }
        ?: badRequest("'$field' must be one of ${enumValues<E>().joinToString { it.name }}")

inline fun <reified E : Enum<E>> Call.enumOf(name: String): E = optEnum<E>(name) ?: badRequest("'$name' is required")

inline fun <reified E : Enum<E>> Call.optEnum(name: String): E? = optStr(name)?.let { parseEnum<E>(name, it) }

inline fun <reified E : Enum<E>> Call.queryEnum(name: String): E? = query(name)?.let { parseEnum<E>(name, it) }

// ---- routing ----

class Route(val method: String, pattern: String, val access: Access, val handler: (Call) -> Any?) {
    private val segments = pattern.split('/').filter { it.isNotEmpty() }

    /** Fewer parameters = more specific; `/x/pending` wins over `/x/{id}`. */
    val paramCount: Int = segments.count { it.startsWith("{") }

    fun match(parts: List<String>): Map<String, String>? {
        if (parts.size != segments.size) return null
        val params = HashMap<String, String>()
        for ((seg, part) in segments.zip(parts)) {
            if (seg.startsWith("{") && seg.endsWith("}")) params[seg.substring(1, seg.length - 1)] = part
            else if (seg != part) return null
        }
        return params
    }
}

class RouteBuilder(private val prefix: String) {
    val routes = mutableListOf<Route>()

    fun get(path: String, access: Access = Access.AUTHENTICATED, h: (Call) -> Any?) { routes += Route("GET", prefix + path, access, h) }
    fun post(path: String, access: Access = Access.AUTHENTICATED, h: (Call) -> Any?) { routes += Route("POST", prefix + path, access, h) }
    fun put(path: String, access: Access = Access.AUTHENTICATED, h: (Call) -> Any?) { routes += Route("PUT", prefix + path, access, h) }
}

// ---- server ----

/**
 * JDK built-in HTTP server hosting the SBL REST API.
 *
 * Requests are executed on a single worker thread because the persistence layer holds a
 * single JDBC connection (single-writer design). Every non-GET request runs inside one DB
 * transaction - a failure part-way through a multi-step operation rolls back completely -
 * and successful calls are written to the audit trail with the authenticated principal.
 */
class ApiServer(
    private val db: Database,
    private val audit: AuditService,
    private val auth: ApiKeyAuth,
    private val routes: List<Route>
) {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    fun start(host: String, port: Int): InetSocketAddress {
        val srv = HttpServer.create(InetSocketAddress(host, port), 0)
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "sbl-api") }
        srv.createContext("/") { ex -> handle(ex) }
        srv.executor = exec
        srv.start()
        server = srv
        executor = exec
        return srv.address
    }

    fun stop() {
        server?.stop(1)
        executor?.shutdown()
    }

    private fun handle(ex: HttpExchange) {
        try {
            val reply = try {
                dispatch(ex)
            } catch (e: ApiException) {
                if (e.status == 401 || e.status == 403) {
                    System.err.println("[sbl-api] ${e.status} ${ex.requestMethod} ${ex.requestURI.rawPath} from ${ex.remoteAddress.address.hostAddress}")
                }
                errorReply(e.status, e.message)
            } catch (e: JsonException) {
                errorReply(400, e.message)
            } catch (e: IllegalArgumentException) {
                errorReply(422, e.message)          // business-rule violation (require)
            } catch (e: IllegalStateException) {
                errorReply(409, e.message)          // invalid state transition (check / error)
            } catch (e: Exception) {
                System.err.println("[sbl-api] 500 ${ex.requestMethod} ${ex.requestURI.rawPath}")
                e.printStackTrace()
                errorReply(500, "Internal server error")
            }
            send(ex, reply)
        } catch (e: Exception) {
            System.err.println("[sbl-api] failed to write response: ${e.javaClass.simpleName}")
        } finally {
            ex.close()
        }
    }

    private fun dispatch(ex: HttpExchange): Reply {
        val method = ex.requestMethod.uppercase()
        val rawPath = ex.requestURI.rawPath ?: "/"
        val parts = rawPath.split('/').filter { it.isNotEmpty() }.map { decode(it) }

        val candidates = routes.mapNotNull { r -> r.match(parts)?.let { r to it } }
        if (candidates.isEmpty()) throw ApiException(404, "No such endpoint")
        val (route, params) = candidates.filter { it.first.method == method }.minByOrNull { it.first.paramCount }
            ?: run {
                ex.responseHeaders.set("Allow", candidates.joinToString(", ") { it.first.method })
                throw ApiException(405, "Method $method not allowed")
            }

        val principal = if (route.access == Access.PUBLIC) null else {
            val key = ex.requestHeaders.getFirst("X-API-Key")
                ?: ex.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                    ?.substring(7)
            auth.authenticate(key) ?: run {
                ex.responseHeaders.set("WWW-Authenticate", "Bearer")
                throw ApiException(401, "Missing or invalid API key")
            }
        }
        if (route.access == Access.OPERATOR && principal?.isOperator != true) {
            forbidden("Operator role required")
        }

        val body = readBody(ex)
        if (body.isNotBlank()) {
            val ct = ex.requestHeaders.getFirst("Content-Type") ?: ""
            if (!ct.lowercase().startsWith("application/json")) throw ApiException(415, "Content-Type must be application/json")
        }
        val call = Call(method, rawPath, params, parseQuery(ex.requestURI.rawQuery), body, principal)

        val result = if (method == "GET") route.handler(call) else db.tx {
            val r = route.handler(call)
            if (principal != null) {
                audit.log("API", "$method $rawPath", "API_REQUEST", principal.name, ActorType.USER,
                    newValue = "role=${principal.role}")
            }
            r
        }
        return result as? Reply ?: Reply(if (result == null) 204 else 200, result)
    }

    private fun readBody(ex: HttpExchange): String {
        val bytes = ex.requestBody.use { it.readNBytes(MAX_BODY_BYTES + 1) }
        if (bytes.size > MAX_BODY_BYTES) throw ApiException(413, "Request body too large")
        return String(bytes, UTF_8)
    }

    private fun send(ex: HttpExchange, reply: Reply) {
        val h = ex.responseHeaders
        h.set("X-Content-Type-Options", "nosniff")
        h.set("Cache-Control", "no-store")
        if (reply.status == 204 || reply.body == null) {
            ex.sendResponseHeaders(reply.status, -1)
            return
        }
        val bytes = Json.write(reply.body).toByteArray(UTF_8)
        h.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(reply.status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun errorReply(status: Int, message: String?) =
        Reply(status, mapOf("error" to mapOf("status" to status, "message" to (message ?: "Error"))))

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val k = pair.substringBefore('=')
            val v = if ('=' in pair) pair.substringAfter('=') else ""
            try {
                URLDecoder.decode(k, UTF_8) to URLDecoder.decode(v, UTF_8)
            } catch (e: IllegalArgumentException) {
                badRequest("Malformed query string")
            }
        }
    }

    /** Decode a path segment; '+' is literal in paths (unlike query strings). */
    private fun decode(seg: String): String = try {
        URLDecoder.decode(seg.replace("+", "%2B"), UTF_8)
    } catch (e: IllegalArgumentException) {
        badRequest("Malformed URL path")
    }

    companion object {
        const val MAX_BODY_BYTES = 1 shl 20 // 1 MiB
    }
}
