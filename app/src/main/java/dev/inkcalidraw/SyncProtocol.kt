package dev.inkcalidraw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Kotlin side of the sync protocol (see sync/src/protocol.ts for the full description).
 * Ops are JSON objects: {id, v, n, full} or {id, v, n, base, patch, del?, app?}.
 */
object SyncProtocol {
    private val json = Json { prettyPrint = false }

    /**
     * JSON numbers the way JavaScript writes them (2.0 → 2), so elements compare equal after a
     * round-trip through the server/web and deltas stay minimal.
     */
    fun normalize(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { normalize(it.value) })
        is JsonArray -> JsonArray(e.map { normalize(it) })
        is JsonPrimitive -> if (!e.isString && e.content.contains('.') && e.content.toDoubleOrNull()?.let { it % 1.0 == 0.0 && kotlin.math.abs(it) < 9e15 } == true)
            JsonPrimitive(e.content.toDouble().toLong()) else e
        else -> e
    }

    fun normalize(e: JsonObject): JsonObject = normalize(e as JsonElement) as JsonObject

    fun version(e: JsonObject) = (e["version"] as? JsonPrimitive)?.longOrNull ?: 0L
    fun nonce(e: JsonObject) = (e["versionNonce"] as? JsonPrimitive)?.longOrNull ?: 0L
    fun id(e: JsonObject) = (e["id"] as? JsonPrimitive)?.content ?: ""

    fun wins(v: Long, n: Long, local: JsonObject?): Boolean {
        if (local == null) return true
        val lv = version(local); val ln = nonce(local)
        return v > lv || (v == lv && n < ln)
    }

    /** Smallest op turning [prev] (what the server has) into [next]. */
    fun diffOp(prev: JsonObject?, next: JsonObject): JsonObject {
        val head = buildJsonObject { put("id", id(next)); put("v", version(next)); put("n", nonce(next)) }
        if (prev == null) return JsonObject(head + ("full" to next))
        val patch = LinkedHashMap<String, JsonElement>()
        val app = LinkedHashMap<String, JsonElement>()
        for ((k, b) in next) {
            if (k == "version" || k == "versionNonce") continue
            val a = prev[k]
            if (a == b) continue
            if (a is JsonArray && b is JsonArray && b.size > a.size && a.indices.all { a[it] == b[it] }) app[k] = JsonArray(b.subList(a.size, b.size))
            else patch[k] = b
        }
        val del = prev.keys.filter { it !in next }
        val op = LinkedHashMap<String, JsonElement>(head)
        op["base"] = JsonPrimitive(version(prev))
        op["patch"] = JsonObject(patch)
        if (del.isNotEmpty()) op["del"] = JsonArray(del.map { JsonPrimitive(it) })
        if (app.isNotEmpty()) op["app"] = JsonObject(app)
        val o = JsonObject(op)
        return if (o.toString().length > next.toString().length * 0.8) JsonObject(head + ("full" to next)) else o
    }

    /** Applies an op; null if it's a patch whose base the receiver doesn't have. */
    fun applyOp(current: JsonObject?, op: JsonObject): JsonObject? {
        (op["full"] as? JsonObject)?.let { return it }
        val base = (op["base"] as? JsonPrimitive)?.longOrNull ?: return null
        if (current == null || version(current) != base) return null
        val m = LinkedHashMap(current)
        (op["patch"] as? JsonObject)?.forEach { (k, v) -> m[k] = v }
        (op["del"] as? JsonArray)?.forEach { m.remove((it as JsonPrimitive).content) }
        (op["app"] as? JsonObject)?.forEach { (k, items) -> m[k] = JsonArray(((m[k] as? JsonArray) ?: JsonArray(emptyList())) + (items as JsonArray)) }
        m["version"] = op["v"]!!
        m["versionNonce"] = op["n"]!!
        return JsonObject(m)
    }

    fun opVersion(op: JsonObject) = (op["v"] as JsonPrimitive).longOrNull ?: 0L
    fun opNonce(op: JsonObject) = (op["n"] as JsonPrimitive).longOrNull ?: 0L
    fun opId(op: JsonObject) = (op["id"] as JsonPrimitive).content

    /** Text for small messages, deflate-raw bytes for big ones (same as the web/server). */
    fun encode(msg: JsonObject): Any {
        val text = json.encodeToString(JsonObject.serializer(), msg)
        if (text.length < 1024) return text
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        d.setInput(text.toByteArray()); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    fun decode(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    fun decode(bytes: ByteArray): JsonObject {
        val inf = Inflater(true)
        inf.setInput(bytes)
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) }
        inf.end()
        return decode(out.toString(Charsets.UTF_8.name()))
    }

    @Suppress("unused") private fun keep(o: JsonPrimitive) = o.intOrNull
}
