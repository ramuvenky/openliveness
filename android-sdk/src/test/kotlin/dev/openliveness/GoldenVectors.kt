package dev.openliveness

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File

data class GoldenVector(val name: String, val input: Any?, val expected: String)

object GoldenVectors {
    private val moshi: Moshi = Moshi.Builder().build()

    fun load(): List<GoldenVector> {
        val candidates = listOf(
            File("../protocol/canonical-json-vectors.json"),
            File("protocol/canonical-json-vectors.json"),
            File("../../protocol/canonical-json-vectors.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("canonical-json-vectors.json not found")
        val type = Types.newParameterizedType(
            List::class.java,
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )
        val adapter = moshi.adapter<List<Map<String, Any?>>>(type)
        val raw = adapter.fromJson(file.readText())!!
        return raw.map { GoldenVector(it["name"] as String, it["input"], it["expected"] as String) }
    }
}
