package dev.openliveness

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CDLSessionTest {

    @Test
    fun `canonical JSON sorts keys and strips whitespace`() {
        val obj = mapOf("b" to 1, "a" to "x", "c" to listOf(3, 1, 2))
        assertEquals("""{"a":"x","b":1,"c":[3,1,2]}""", CanonicalJson.encode(obj))
    }

    @Test
    fun `canonical JSON orders nested keys`() {
        val obj = mapOf("outer" to mapOf("z" to true, "a" to false))
        assertEquals("""{"outer":{"a":false,"z":true}}""", CanonicalJson.encode(obj))
    }

    @Test
    fun `canonical JSON formats integer-valued doubles as ints and fractionals as fixed point`() {
        val obj = mapOf("score" to 0.85, "pulse" to true, "count" to 3.0)
        assertEquals("""{"count":3,"pulse":true,"score":0.850000}""", CanonicalJson.encode(obj))
    }

    @Test
    fun `canonical JSON rejects non-finite doubles`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalJson.encode(mapOf("n" to Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalJson.encode(mapOf("n" to Double.POSITIVE_INFINITY))
        }
    }

    @Test
    fun `canonical JSON matches shared cross-platform golden vectors`() {
        for (vec in GoldenVectors.load()) {
            assertEquals(vec.expected, CanonicalJson.encode(vec.input), "vector ${vec.name}")
        }
    }
}
