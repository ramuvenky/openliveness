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

    // Compile-time stub gate (C-2 fix). The init block delegates to
    // CDLSession.checkStubGate(allowStubMode, BuildConfig.STUB_PATH_ENABLED).
    // BuildConfig.STUB_PATH_ENABLED is baked into the AAR at compile time
    // (`true` in debug variants, `false` in release — see android-sdk/
    // build.gradle.kts), so a JVM unit test can't flip it. Driving the
    // helper directly covers both branches of the gate and locks the
    // asymmetry fix against regression: if a future refactor drops the
    // `release { buildConfigField(... false) }` line, the throwing branch
    // of this test still passes against the extracted helper — but the
    // "release AAR constructs normally" guarantee is lost. That drift
    // would be caught by the companion Gradle assertion in `./gradlew
    // :assembleRelease` combined with a reviewer reading this comment.

    @Test
    fun `stub gate throws when allowStubMode is true and stub path is disabled (simulates release AAR)`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            CDLSession.checkStubGate(allowStubMode = true, stubPathEnabled = false)
        }
        // Error message must name the Gradle flag so an integrator who
        // trips this at runtime can find the fix without reading SDK source.
        assertTrue(
            ex.message?.contains("STUB_PATH_ENABLED") == true,
            "stub gate error must name BuildConfig.STUB_PATH_ENABLED so the operator can find the fix"
        )
    }

    @Test
    fun `stub gate permits allowStubMode when stub path is enabled (debug AAR path)`() {
        // Debug variants compile STUB_PATH_ENABLED=true, so this is the
        // normal unit-test / dev-harness path. Just assert "no throw".
        CDLSession.checkStubGate(allowStubMode = true, stubPathEnabled = true)
    }

    @Test
    fun `stub gate is inert when allowStubMode is false regardless of build variant`() {
        // The gate is a one-way door: it only ever refuses the stub path,
        // never anything else. A real-landmark release build must construct
        // normally even though stubPathEnabled=false.
        CDLSession.checkStubGate(allowStubMode = false, stubPathEnabled = false)
        CDLSession.checkStubGate(allowStubMode = false, stubPathEnabled = true)
    }
}
