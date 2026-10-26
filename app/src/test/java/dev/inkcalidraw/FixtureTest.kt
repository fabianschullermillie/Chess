package dev.inkcalidraw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Produces fixtures that a Node script checks against the real @excalidraw/excalidraw package:
 *  - a scene authored by this app (handwriting-like pen strokes + edge cases + a foreign element)
 *  - the outline our Kotlin perfect-freehand port computes for every freedraw element
 */
class FixtureTest {
    private val out = File(System.getProperty("fixtureDir") ?: "build/fixtures").apply { mkdirs() }

    /** Fake S Pen samples: a cursive-ish wave with pen-like pressure and jitter, sampled at ~240 Hz. */
    private fun penStroke(x0: Float, y0: Float, seed: Int, n: Int = 120): List<FloatArray> {
        val rnd = Random(seed)
        return (0 until n).map { i ->
            val t = i / (n - 1f)
            val x = x0 + t * 260 + 18 * sin(t * 14 * PI).toFloat() + rnd.nextFloat() * 0.6f
            val y = y0 + 30 * sin(t * 6 * PI + seed).toFloat() + 22 * cos(t * 14 * PI).toFloat() + rnd.nextFloat() * 0.6f
            val p = (0.15f + 0.75f * sin(t * PI).toFloat() + rnd.nextFloat() * 0.05f).coerceIn(0f, 1f)
            floatArrayOf(x, y, p)
        }
    }

    @Test
    fun writeFixtures() {
        val scene = ExcalidrawScene.empty()
        var idx: String? = null
        fun add(samples: List<FloatArray>, color: String, w: Double) {
            idx = FractionalIndex.after(idx)
            scene.elements.add(ExcalidrawScene.newFreedraw(samples, color, w, idx))
        }
        add(penStroke(40f, 80f, 1), "#1e1e1e", 1.0)
        add(penStroke(40f, 220f, 2), "#e03131", 1.5)
        add(penStroke(40f, 380f, 3, n = 300), "#1971c2", 4.0)
        // sharp zig-zag (exercises the sharp-corner branch)
        add(listOf(floatArrayOf(400f, 100f, .5f), floatArrayOf(480f, 180f, .6f), floatArrayOf(400f, 260f, .7f),
            floatArrayOf(480f, 340f, .6f), floatArrayOf(400f, 420f, .4f)).flatMap { listOf(it) }.let { base ->
            // densify like a real pen would
            base.zipWithNext().flatMap { (a, b) -> (0 until 20).map { k -> val t = k / 20f; floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t) } } + listOf(base.last())
        }, "#2f9e44", 2.0)
        add(listOf(floatArrayOf(560f, 120f, 0.8f)), "#1e1e1e", 2.0)                                // a dot
        add(listOf(floatArrayOf(560f, 200f, 0.3f), floatArrayOf(640f, 230f, 0.9f)), "#f08c00", 2.0) // 2-point stroke

        // An element "created on excalidraw.com" with fields we don't model; must survive untouched.
        val foreign = buildJsonObject {
            put("id", "foreignRect01"); put("type", "rectangle"); put("x", 560); put("y", 300)
            put("width", 160); put("height", 90); put("angle", 0); put("strokeColor", "#1e1e1e")
            put("backgroundColor", "#a5d8ff"); put("fillStyle", "hachure"); put("strokeWidth", 2)
            put("strokeStyle", "solid"); put("roughness", 1); put("opacity", 100)
            put("groupIds", buildJsonArray { }); put("frameId", null as String?); put("index", "Zz")
            put("roundness", buildJsonObject { put("type", 3) }); put("seed", 1234); put("version", 7)
            put("versionNonce", 99); put("isDeleted", false)
            put("boundElements", buildJsonArray { add(buildJsonObject { put("type", "text"); put("id", "someLabel") }) })
            put("updated", 1730000000000); put("link", "https://excalidraw.com"); put("locked", false)
            put("customData", buildJsonObject { put("obsidian", "keep-me") })
        }
        scene.elements.add(0, Element(foreign))

        // A pasted screenshot: PNG bytes -> files map keyed by SHA-1, referenced by an image element.
        val bytes = javaClass.classLoader!!.getResourceAsStream("slide.png")!!.readBytes()
        val fileId = java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        scene.addFile(fileId, "image/png", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes))
        idx = FractionalIndex.after(idx)
        scene.elements.add(ExcalidrawScene.newImage(fileId, 760.0, 60.0, 240.0, 140.0, idx))

        val sceneJson = scene.toJson()
        File(out, "scene.excalidraw").writeText(sceneJson)

        // Round-trip through our own parser must be lossless.
        val again = ExcalidrawScene.parse(sceneJson).toJson()
        assertEquals(Json.parseToJsonElement(sceneJson), Json.parseToJsonElement(again))
        assertEquals(foreign, Json.parseToJsonElement(again).jsonObject["elements"]!!.jsonArray[0])

        val outlines = buildJsonObject {
            for (el in scene.elements.filter { it.type == "freedraw" }) {
                put(el.id, JsonArray(el.freedrawOutline().map { p -> buildJsonArray { add(JsonPrimitive(p[0])); add(JsonPrimitive(p[1])) } }))
            }
        }
        File(out, "outlines.json").writeText(outlines.toString())

        // fractional indices we generate, for comparison with the npm `fractional-indexing` package
        var k: String? = null
        val keys = (0 until 300).map { k = FractionalIndex.after(k); k!! }
        File(out, "keys.json").writeText(keys.joinToString(",", "[", "]") { "\"$it\"" })
        assertTrue(keys.zipWithNext().all { (a, b) -> a < b })
    }

    /** An A4 note: page frames, strokes bound to pages, a locked "PDF page" image, and the pen config. */
    @Test
    fun writePagedFixture() {
        val scene = Pages.newPagedScene(PageOrientation.PORTRAIT, PageDirection.VERTICAL)
        val p2 = Pages.addPage(scene, PageOrientation.PORTRAIT, PageDirection.VERTICAL)
        val p1 = Pages.pages(scene)[0]
        assertEquals(Pages.A4_LONG + Pages.GAP, p2.y, 1e-9)

        val bytes = javaClass.classLoader!!.getResourceAsStream("slide.png")!!.readBytes()
        val fileId = java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        scene.addFile(fileId, "image/png", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes))
        scene.elements.add(ExcalidrawScene.newImage(fileId, p1.x, p1.y, p1.w, p1.w * 140 / 240, FractionalIndex.after(scene.lastIndex()), frameId = p1.id, locked = true))
        // a stroke that runs off the right edge of page 1 (Excalidraw clips it to the frame)
        scene.elements.add(ExcalidrawScene.newFreedraw(penStroke(560f, 620f, 7), "#e03131", 2.0, FractionalIndex.after(scene.lastIndex()), frameId = p1.id))
        scene.elements.add(ExcalidrawScene.newFreedraw(penStroke(120f, (p2.y + 200).toFloat(), 8), "#1971c2", 3.0, FractionalIndex.after(scene.lastIndex()), frameId = p2.id))
        scene.elements.add(ExcalidrawScene.newFreedraw(penStroke(120f, (p2.y + 400).toFloat(), 9), "#ffd43b", 8.0, FractionalIndex.after(scene.lastIndex()), opacity = 40, frameId = p2.id))
        NoteMeta(tool = "highlighter", pen = PenConfig("#2f9e44", 1.5, 100), highlighter = PenConfig("#ffd43b", 8.0, 40), paged = true).writeTo(scene)
        File(out, "paged.excalidraw").writeText(scene.toJson())

        // meta round-trips
        val back = NoteMeta.readFrom(ExcalidrawScene.parse(scene.toJson()))
        assertEquals("highlighter", back.tool); assertEquals(PenConfig("#2f9e44", 1.5, 100), back.pen); assertTrue(back.paged)

        // relayout to horizontal moves page 2 and its strokes by the same delta
        val before = scene.elements.first { it.frameId == p2.id }
        Pages.relayout(scene, PageDirection.HORIZONTAL)
        val p2After = Pages.pages(scene)[1]
        val after = scene.elements.first { it.id == before.id }
        assertEquals(p2After.x - p2.x, after.x - before.x, 1e-9)
        assertEquals(p2After.y - p2.y, after.y - before.y, 1e-9)
        assertEquals(p1.x + p1.w + Pages.GAP, p2After.x, 1e-9)
    }
}
