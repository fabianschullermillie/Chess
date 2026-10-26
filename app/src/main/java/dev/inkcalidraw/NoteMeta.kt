package dev.inkcalidraw

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class PenConfig(val color: String, val strokeWidth: Double, val opacity: Int) {
    fun toJson() = buildJsonObject { put("color", color); put("strokeWidth", strokeWidth); put("opacity", opacity) }

    companion object {
        fun fromJson(o: JsonObject?, default: PenConfig): PenConfig {
            if (o == null) return default
            return PenConfig(
                (o["color"] as? JsonPrimitive)?.content ?: default.color,
                (o["strokeWidth"] as? JsonPrimitive)?.doubleOrNull ?: default.strokeWidth,
                (o["opacity"] as? JsonPrimitive)?.intOrNull ?: default.opacity,
            )
        }
    }
}

enum class PageDirection { VERTICAL, HORIZONTAL }
enum class PageOrientation { PORTRAIT, LANDSCAPE }

/**
 * Per-note settings stored inside the .excalidraw file itself:
 *  - Excalidraw's own appState keys (currentItemStrokeColor/-Width/-Opacity) mirror the active pen, so
 *    excalidraw.com starts with the same pen when it opens the file.
 *  - Everything app-specific lives in a top-level "inkcalidraw" object, which Excalidraw ignores.
 */
data class NoteMeta(
    val tool: String = "pen",
    val pen: PenConfig = DEFAULT_PEN,
    val highlighter: PenConfig = DEFAULT_HIGHLIGHTER,
    /** null = infinite whiteboard; otherwise the note is made of pages (frames). */
    val paged: Boolean = false,
    val direction: PageDirection = PageDirection.VERTICAL,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
) {
    fun activePen(): PenConfig = if (tool == "highlighter") highlighter else pen

    fun writeTo(scene: ExcalidrawScene) {
        val active = activePen()
        scene.setAppState(
            "currentItemStrokeColor" to JsonPrimitive(active.color),
            "currentItemStrokeWidth" to JsonPrimitive(active.strokeWidth),
            "currentItemOpacity" to JsonPrimitive(active.opacity),
        )
        val old = scene.appData.toMutableMap()
        old["version"] = JsonPrimitive(1)
        old["tool"] = JsonPrimitive(tool)
        old["pen"] = pen.toJson()
        old["highlighter"] = highlighter.toJson()
        old["pages"] = buildJsonObject {
            put("enabled", paged)
            put("direction", direction.name.lowercase())
            put("orientation", orientation.name.lowercase())
            put("format", "A4")
        }
        scene.appData = JsonObject(old)
    }

    companion object {
        val DEFAULT_PEN = PenConfig("#1e1e1e", 2.0, 100)
        val DEFAULT_HIGHLIGHTER = PenConfig("#ffd43b", 8.0, 40)

        fun readFrom(scene: ExcalidrawScene): NoteMeta {
            val d = scene.appData
            val pages = d["pages"] as? JsonObject
            val hasFrames = scene.elements.any { it.type == "frame" && !it.isDeleted && it.pageNumber != null }
            // A file never touched by this app: take the pen from Excalidraw's own appState if present.
            val st = scene.appState
            val excalidrawPen = PenConfig(
                (st["currentItemStrokeColor"] as? JsonPrimitive)?.content ?: DEFAULT_PEN.color,
                (st["currentItemStrokeWidth"] as? JsonPrimitive)?.doubleOrNull ?: DEFAULT_PEN.strokeWidth,
                100,
            )
            return NoteMeta(
                tool = (d["tool"] as? JsonPrimitive)?.content?.takeIf { it in setOf("pen", "highlighter", "eraser", "select") } ?: "pen",
                pen = PenConfig.fromJson(d["pen"] as? JsonObject, excalidrawPen),
                highlighter = PenConfig.fromJson(d["highlighter"] as? JsonObject, DEFAULT_HIGHLIGHTER),
                paged = (pages?.get("enabled") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: hasFrames,
                direction = if ((pages?.get("direction") as? JsonPrimitive)?.content == "horizontal") PageDirection.HORIZONTAL else PageDirection.VERTICAL,
                orientation = if ((pages?.get("orientation") as? JsonPrimitive)?.content == "landscape") PageOrientation.LANDSCAPE else PageOrientation.PORTRAIT,
            )
        }
    }
}
