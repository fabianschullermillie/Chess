package dev.inkcalidraw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.round
import kotlin.random.Random

/**
 * An Excalidraw element kept as its raw JSON object, so every field we don't understand
 * (bindings, frames, custom data, fields added by future Excalidraw versions) survives a round-trip.
 */
class Element(var json: JsonObject) {
    val id: String get() = json.str("id") ?: ""
    val type: String get() = json.str("type") ?: ""
    val isDeleted: Boolean get() = json["isDeleted"]?.jsonPrimitive?.booleanOrNull ?: false
    val version: Int get() = json["version"]?.jsonPrimitive?.intOrNull ?: 1
    val x: Double get() = json.num("x")
    val y: Double get() = json.num("y")
    val width: Double get() = json.num("width")
    val height: Double get() = json.num("height")
    val angle: Double get() = json.num("angle")
    val strokeColor: String get() = json.str("strokeColor") ?: "#1e1e1e"
    val backgroundColor: String get() = json.str("backgroundColor") ?: "transparent"
    val strokeWidth: Double get() = json["strokeWidth"]?.jsonPrimitive?.doubleOrNull ?: 1.0
    val opacity: Double get() = json["opacity"]?.jsonPrimitive?.doubleOrNull ?: 100.0
    val index: String? get() = json.str("index")

    /** Points relative to (x, y), as [x, y] pairs. */
    val points: List<DoubleArray>
        get() = (json["points"] as? JsonArray)?.map { p ->
            val a = p.jsonArray; doubleArrayOf(a[0].jsonPrimitive.doubleOrNull ?: 0.0, a[1].jsonPrimitive.doubleOrNull ?: 0.0)
        } ?: emptyList()

    val pressures: List<Double>
        get() = (json["pressures"] as? JsonArray)?.map { it.jsonPrimitive.doubleOrNull ?: 0.5 } ?: emptyList()

    val simulatePressure: Boolean get() = json["simulatePressure"]?.jsonPrimitive?.booleanOrNull ?: true
    val hasLastCommittedPoint: Boolean get() = json["lastCommittedPoint"].let { it != null && it !is JsonNull }

    /** Exactly the input Excalidraw feeds into perfect-freehand for a freedraw element. */
    fun freedrawInput(): List<DoubleArray> {
        val pts = points
        if (simulatePressure) return pts
        if (pts.isEmpty()) return listOf(doubleArrayOf(0.0, 0.0, 0.5))
        val pr = pressures
        return pts.mapIndexed { i, p -> doubleArrayOf(p[0], p[1], pr.getOrElse(i) { -1.0 }) }
    }

    fun freedrawOutline(): List<DoubleArray> = PerfectFreehand.getStroke(
        freedrawInput(),
        // Excalidraw's restore() nulls lastCommittedPoint on load, so files always render with last=false.
        PerfectFreehand.excalidrawOptions(strokeWidth, simulatePressure, last = false),
    )

    val fileId: String? get() = json.str("fileId")

    /** Image flip, Excalidraw's `scale: [sx, sy]` (each 1 or -1). */
    val imageScale: Pair<Float, Float>
        get() = (json["scale"] as? JsonArray)?.let {
            Pair(it[0].jsonPrimitive.doubleOrNull?.toFloat() ?: 1f, it[1].jsonPrimitive.doubleOrNull?.toFloat() ?: 1f)
        } ?: Pair(1f, 1f)

    /** A copy with a fresh id/seed and no group/binding links, as Excalidraw's duplicate does. */
    fun copyAsNew(index: String?): Element {
        val m = json.toMutableMap()
        m["id"] = JsonPrimitive(ExcalidrawScene.randomId())
        m["seed"] = JsonPrimitive(Random.nextInt(1, Int.MAX_VALUE))
        m["version"] = JsonPrimitive(1)
        m["boundElements"] = JsonNull
        m["groupIds"] = JsonArray(emptyList())
        if (index != null) m["index"] = JsonPrimitive(index) else m.remove("index")
        return Element(JsonObject(m))
    }

    fun withChanges(vararg changes: Pair<String, JsonElement>): Element {
        val m = json.toMutableMap()
        for ((k, v) in changes) m[k] = v
        m["version"] = JsonPrimitive(version + 1)
        m["versionNonce"] = JsonPrimitive(Random.nextInt(0, Int.MAX_VALUE))
        m["updated"] = JsonPrimitive(System.currentTimeMillis())
        return Element(JsonObject(m))
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.num(k: String): Double = (this[k] as? JsonPrimitive)?.doubleOrNull ?: 0.0

class ExcalidrawScene(
    /** Every top-level key except "elements" (appState, files, source, ...), kept verbatim. */
    private val rest: JsonObject,
    val elements: MutableList<Element>,
) {
    /** The scene's binary files (images), keyed by fileId, as stored in the file's top-level "files". */
    val files: MutableMap<String, JsonObject> =
        ((rest["files"] as? JsonObject)?.mapValues { it.value.jsonObject } ?: emptyMap()).toMutableMap()

    fun fileDataUrl(id: String): String? = (files[id]?.get("dataURL") as? JsonPrimitive)?.content

    fun addFile(id: String, mimeType: String, dataUrl: String) {
        if (id in files) return
        val now = System.currentTimeMillis()
        files[id] = buildJsonObject {
            put("mimeType", mimeType); put("id", id); put("dataURL", dataUrl)
            put("created", now); put("lastRetrieved", now)
        }
    }

    val backgroundColor: String
        get() = (rest["appState"] as? JsonObject)?.get("viewBackgroundColor")
            ?.let { (it as? JsonPrimitive)?.content } ?: "#ffffff"

    fun toJson(): String {
        val out = buildJsonObject {
            put("type", rest["type"] ?: JsonPrimitive("excalidraw"))
            put("version", rest["version"] ?: JsonPrimitive(2))
            put("source", rest["source"] ?: JsonPrimitive(SOURCE))
            put("elements", JsonArray(elements.map { it.json }))
            put("appState", rest["appState"] ?: buildJsonObject {
                put("gridSize", JsonNull); put("viewBackgroundColor", "#ffffff")
            })
            put("files", JsonObject(files))
            for ((k, v) in rest) if (k !in setOf("type", "version", "source", "elements", "appState", "files")) put(k, v)
        }
        return json.encodeToString(JsonObject.serializer(), out)
    }

    fun lastIndex(): String? = elements.mapNotNull { it.index }.maxOrNull()

    companion object {
        const val SOURCE = "https://github.com/inkcalidraw (android prototype)"
        private val json = Json { prettyPrint = false }

        fun empty() = ExcalidrawScene(JsonObject(emptyMap()), mutableListOf())

        fun parse(text: String): ExcalidrawScene {
            val root = Json.parseToJsonElement(text).jsonObject
            require((root["type"] as? JsonPrimitive)?.content == "excalidraw") { "Not an .excalidraw file" }
            val elements = (root["elements"] as? JsonArray)?.map { Element(it.jsonObject) }?.toMutableList() ?: mutableListOf()
            return ExcalidrawScene(JsonObject(root - "elements"), elements)
        }

        private fun r2(v: Double) = round(v * 100) / 100

        /**
         * Builds a freedraw element from absolute scene-space samples (x, y, pressure), the same
         * shape Excalidraw itself produces when you draw with a pen.
         */
        fun newFreedraw(
            samples: List<FloatArray>,
            strokeColor: String,
            strokeWidth: Int,
            index: String?,
            opacity: Int = 100,
        ): Element {
            require(samples.isNotEmpty())
            // Excalidraw discards freedraw elements with < 2 points on load; a tap in Excalidraw
            // stores the same point twice, so do the same for dots.
            @Suppress("NAME_SHADOWING")
            val samples = if (samples.size == 1) listOf(samples[0], samples[0].copyOf()) else samples
            val ox = samples[0][0].toDouble()
            val oy = samples[0][1].toDouble()
            val rel = samples.map { doubleArrayOf(r2(it[0] - ox), r2(it[1] - oy)) }
            val minX = rel.minOf { it[0] }; val maxX = rel.maxOf { it[0] }
            val minY = rel.minOf { it[1] }; val maxY = rel.maxOf { it[1] }
            val now = System.currentTimeMillis()
            return Element(buildJsonObject {
                put("id", randomId())
                put("type", "freedraw")
                put("x", ox); put("y", oy)
                put("width", maxX - minX); put("height", maxY - minY)
                put("angle", 0)
                put("strokeColor", strokeColor)
                put("backgroundColor", "transparent")
                put("fillStyle", "solid")
                put("strokeWidth", strokeWidth)
                put("strokeStyle", "solid")
                put("roughness", 1)
                put("opacity", opacity)
                put("groupIds", JsonArray(emptyList()))
                put("frameId", JsonNull)
                if (index != null) put("index", index)
                put("roundness", JsonNull)
                put("seed", Random.nextInt(1, Int.MAX_VALUE))
                put("version", 1)
                put("versionNonce", Random.nextInt(1, Int.MAX_VALUE))
                put("isDeleted", false)
                put("boundElements", JsonNull)
                put("updated", now)
                put("link", JsonNull)
                put("locked", false)
                put("points", buildJsonArray { rel.forEach { add(buildJsonArray { add(JsonPrimitive(it[0])); add(JsonPrimitive(it[1])) }) } })
                put("pressures", buildJsonArray { samples.forEach { add(JsonPrimitive(r2(it[2].toDouble().coerceIn(0.0, 1.0)))) } })
                put("simulatePressure", false)
                put("lastCommittedPoint", JsonNull)
            })
        }

        /**
         * An Excalidraw image element. The bytes live in the scene's "files" map under [fileId];
         * the element only references them, exactly like Excalidraw does.
         */
        fun newImage(fileId: String, x: Double, y: Double, width: Double, height: Double, index: String?): Element {
            return Element(buildJsonObject {
                put("id", randomId()); put("type", "image")
                put("x", x); put("y", y); put("width", width); put("height", height); put("angle", 0)
                put("strokeColor", "transparent"); put("backgroundColor", "transparent")
                put("fillStyle", "solid"); put("strokeWidth", 2); put("strokeStyle", "solid")
                put("roughness", 1); put("opacity", 100)
                put("groupIds", JsonArray(emptyList())); put("frameId", JsonNull)
                if (index != null) put("index", index)
                put("roundness", JsonNull)
                put("seed", Random.nextInt(1, Int.MAX_VALUE)); put("version", 1)
                put("versionNonce", Random.nextInt(1, Int.MAX_VALUE)); put("isDeleted", false)
                put("boundElements", JsonNull); put("updated", System.currentTimeMillis())
                put("link", JsonNull); put("locked", false)
                put("status", "saved"); put("fileId", fileId)
                put("scale", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(1)) })
                put("crop", JsonNull)
            })
        }

        private const val ID_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-"
        internal fun randomId() = (1..21).map { ID_CHARS[Random.nextInt(ID_CHARS.length)] }.joinToString("")
    }
}

/** Minimal port of the `fractional-indexing` package (what Excalidraw uses for element.index). */
object FractionalIndex {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private fun integerLength(head: Char): Int = when (head) {
        in 'a'..'z' -> head - 'a' + 2
        in 'A'..'Z' -> 'Z' - head + 2
        else -> error("invalid order key head: $head")
    }

    private fun incrementInteger(x: String): String? {
        val head = x[0]
        val digs = x.substring(1).toMutableList()
        var carry = true
        var i = digs.size - 1
        while (carry && i >= 0) {
            val d = DIGITS.indexOf(digs[i]) + 1
            if (d == DIGITS.length) digs[i] = '0' else { digs[i] = DIGITS[d]; carry = false }
            i--
        }
        if (carry) {
            if (head == 'Z') return "a0"
            if (head == 'z') return null
            val h = head + 1
            if (h > 'a') digs.add('0') else digs.removeAt(digs.size - 1)
            return h + digs.joinToString("")
        }
        return head + digs.joinToString("")
    }

    /** generateKeyBetween(a, null) */
    fun after(a: String?): String {
        if (a == null) return "a0"
        val intPart = a.substring(0, integerLength(a[0]))
        return incrementInteger(intPart) ?: (a + "V")
    }
}
