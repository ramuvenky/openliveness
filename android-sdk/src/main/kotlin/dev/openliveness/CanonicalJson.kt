package dev.openliveness

import java.util.Locale

/**
 * Canonical JSON encoder matching the server's canonicalize() implementation
 * in verify/. Rules: keys sorted alphabetically (recursive), no whitespace,
 * UTF-8. Numbers: integer-valued doubles with |x| < 1e16 emit as integers
 * ("1" not "1.0"); all other finite doubles emit as fixed-point "%.6f".
 * NaN and infinity throw. The output of this encoder is what gets
 * SHA-256-hashed and signed.
 */
object CanonicalJson {

    fun encode(value: Any?): String {
        val sb = StringBuilder()
        write(value, sb)
        return sb.toString()
    }

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Float -> out.append(formatNumber(value.toDouble()))
            is Double -> out.append(formatNumber(value))
            is Number -> out.append(formatNumber(value.toDouble()))
            is String -> out.append(quote(value))
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, el ->
                    if (i > 0) out.append(',')
                    write(el, out)
                }
                out.append(']')
            }
            is Map<*, *> -> {
                out.append('{')
                val keys = value.keys.map { it.toString() }.sorted()
                keys.forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    out.append(quote(k))
                    out.append(':')
                    write(value[k], out)
                }
                out.append('}')
            }
            else -> throw IllegalArgumentException("Unsupported canonical JSON type: ${value::class}")
        }
    }

    private fun formatNumber(d: Double): String {
        if (!d.isFinite()) {
            throw IllegalArgumentException("non-finite number in canonical JSON")
        }
        return if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e16) {
            d.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.6f", d)
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\u000C' -> sb.append("\\f")
                '\r' -> sb.append("\\r")
                else -> if (c.code < 0x20) {
                    sb.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
