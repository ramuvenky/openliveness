import Foundation

/// Canonical JSON encoding used for the ES256 signature preimage.
/// Rules: keys sorted alphabetically (recursive), no whitespace, UTF-8.
/// Numbers: integer-valued doubles with |x| < 1e16 emit as integers ("1"
/// not "1.0"); all other finite doubles emit as fixed-point "%.6f". NaN
/// and infinity throw. This spec is reproducible in Python, Kotlin, and
/// Swift without relying on shortest-round-trip algorithms that diverge
/// across runtimes. Must byte-match the server's canonicalize() in verify/.
enum CanonicalJSON {

    static func encode(_ value: Any) throws -> Data {
        var out = ""
        try write(value, into: &out)
        guard let data = out.data(using: .utf8) else {
            throw CDLError.networkError("canonical encode failed")
        }
        return data
    }

    private static func write(_ value: Any, into out: inout String) throws {
        switch value {
        case is NSNull:
            out += "null"
        case let b as Bool:
            out += b ? "true" : "false"
        case let n as Int:
            out += String(n)
        case let n as Int64:
            out += String(n)
        case let n as Double:
            out += try formatNumber(n)
        case let n as Float:
            out += try formatNumber(Double(n))
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() {
                out += n.boolValue ? "true" : "false"
            } else {
                out += try formatNumber(n.doubleValue)
            }
        case let s as String:
            out += quote(s)
        case let arr as [Any]:
            out += "["
            for (i, el) in arr.enumerated() {
                if i > 0 { out += "," }
                try write(el, into: &out)
            }
            out += "]"
        case let dict as [String: Any]:
            out += "{"
            let sortedKeys = dict.keys.sorted()
            for (i, k) in sortedKeys.enumerated() {
                if i > 0 { out += "," }
                out += quote(k)
                out += ":"
                try write(dict[k]!, into: &out)
            }
            out += "}"
        default:
            throw CDLError.networkError("unsupported type in canonical JSON")
        }
    }

    private static func formatNumber(_ d: Double) throws -> String {
        guard d.isFinite else {
            throw CDLError.networkError("non-finite number in canonical JSON")
        }
        if d.rounded() == d && abs(d) < 1e16 {
            return String(Int64(d))
        }
        return String(format: "%.6f", d)
    }

    private static func quote(_ s: String) -> String {
        var out = "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\u{08}": out += "\\b"
            case "\u{09}": out += "\\t"
            case "\u{0A}": out += "\\n"
            case "\u{0C}": out += "\\f"
            case "\u{0D}": out += "\\r"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out += String(scalar)
                }
            }
        }
        out += "\""
        return out
    }
}
