package com.piscessoft.sbl.api

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.math.BigInteger
import java.time.temporal.TemporalAccessor
import java.util.concurrent.ConcurrentHashMap

class JsonException(message: String) : RuntimeException(message)

/**
 * Minimal, dependency-free JSON codec for the REST layer.
 *
 * Writing: domain objects (Kotlin data classes under `com.piscessoft.sbl`) are serialised
 * from their declared fields; BigDecimal is emitted as a plain JSON number so monetary
 * precision is preserved; java.time values and enums are emitted as strings.
 *
 * Reading: produces Map / List / String / BigDecimal / Boolean / null. Numbers are always
 * BigDecimal so no precision is lost before validation.
 */
object Json {

    private const val MAX_DEPTH = 64
    private const val DOMAIN_PACKAGE = "com.piscessoft.sbl."
    private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    fun write(value: Any?): String = StringBuilder().also { write(it, value, 0) }.toString()

    fun parse(text: String): Any? = Parser(text).document()

    // ---- writer ----

    private fun write(sb: StringBuilder, v: Any?, depth: Int) {
        if (depth > MAX_DEPTH) throw JsonException("Object graph too deep")
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is BigDecimal -> sb.append(v.toPlainString())
            is Int, is Long, is Short, is Byte, is BigInteger -> sb.append(v.toString())
            is Double -> if (v.isFinite()) sb.append(v) else sb.append("null")
            is Float -> if (v.isFinite()) sb.append(v) else sb.append("null")
            is Enum<*> -> quote(sb, v.name)
            is TemporalAccessor -> quote(sb, v.toString())
            is java.io.File -> quote(sb, v.name)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, value, depth + 1)
                }
                sb.append('}')
            }
            is Iterable<*> -> writeArray(sb, v.iterator(), depth)
            is Array<*> -> writeArray(sb, v.iterator(), depth)
            is Pair<*, *> -> write(sb, mapOf("first" to v.first, "second" to v.second), depth)
            else -> if (v.javaClass.name.startsWith(DOMAIN_PACKAGE)) writeObject(sb, v, depth)
                    else quote(sb, v.toString())
        }
    }

    private fun writeArray(sb: StringBuilder, it: Iterator<*>, depth: Int) {
        sb.append('[')
        var first = true
        while (it.hasNext()) {
            if (!first) sb.append(',')
            first = false
            write(sb, it.next(), depth + 1)
        }
        sb.append(']')
    }

    private fun writeObject(sb: StringBuilder, v: Any, depth: Int) {
        sb.append('{')
        var first = true
        for (f in fieldsOf(v.javaClass)) {
            if (!first) sb.append(',')
            first = false
            quote(sb, f.name)
            sb.append(':')
            write(sb, f.get(v), depth + 1)
        }
        sb.append('}')
    }

    private fun fieldsOf(cls: Class<*>): List<Field> = fieldCache.getOrPut(cls) {
        val out = mutableListOf<Field>()
        var c: Class<*>? = cls
        while (c != null && c.name.startsWith(DOMAIN_PACKAGE)) {
            c.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) && !it.isSynthetic }
                .forEach { it.isAccessible = true; out += it }
            c = c.superclass
        }
        out
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ' || ch == '\u2028' || ch == '\u2029') {
                    sb.append("\\u").append(String.format("%04x", ch.code))
                } else sb.append(ch)
            }
        }
        sb.append('"')
    }

    // ---- reader ----

    private class Parser(private val s: String) {
        private var i = 0

        fun document(): Any? {
            val v = value(0)
            ws()
            if (i != s.length) fail("Unexpected trailing content")
            return v
        }

        private fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("Nesting too deep")
            ws()
            if (i >= s.length) fail("Unexpected end of input")
            val c = s[i]
            return when {
                c == '{' -> obj(depth)
                c == '[' -> arr(depth)
                c == '"' -> str()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c in '0'..'9' -> num()
                else -> fail("Unexpected character '$c'")
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (peek() == '}') { i++; return out }
            while (true) {
                ws()
                if (peek() != '"') fail("Expected object key")
                val key = str()
                ws()
                expect(':')
                out[key] = value(depth + 1)
                ws()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            ws()
            if (peek() == ']') { i++; return out }
            while (true) {
                out += value(depth + 1)
                ws()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("Unterminated escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Invalid unicode escape")
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: fail("Invalid unicode escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("Invalid escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("Control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): BigDecimal {
            val start = i
            if (peek() == '-') i++
            if (!digits()) fail("Invalid number")
            if (peek() == '.') { i++; if (!digits()) fail("Invalid number") }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                if (!digits()) fail("Invalid number")
            }
            return try { BigDecimal(s.substring(start, i)) } catch (e: NumberFormatException) { fail("Invalid number") }
        }

        private fun digits(): Boolean {
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            return i > start
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Invalid literal")
            i += word.length
            return v
        }

        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek(): Char? = if (i < s.length) s[i] else null
        private fun expect(c: Char) { if (peek() != c) fail("Expected '$c'"); i++ }
        private fun fail(msg: String): Nothing = throw JsonException("Malformed JSON at position $i: $msg")
    }
}
