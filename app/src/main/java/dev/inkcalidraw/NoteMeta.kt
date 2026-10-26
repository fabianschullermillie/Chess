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
    /** Active pen profile for the pen tool (see PenProfiles). */
    val profile: String = "pen",
    /** Settings per pen profile id, including "highlighter". */
    val pens: Map<String, PenConfig> = emptyMap(),
    val paged: Boolean = false,
    val direction: PageDirection = PageDirection.VERTICAL,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
) {
    fun penFor(profileId: String): PenConfig = pens[profileId] ?: PenProfiles.byId(profileId).default
    fun activeProfileId(): String = if (tool == "highlighter") "highlighter" else profile
    fun activePen(): PenConfig = penFor(activeProfileId())
    fun withPen(profileId: String, cfg: PenConfig) = copy(pens = pens + (profileId to cfg))

    fun writeTo(scene: ExcalidrawScene) {
        val active = activePen()
        scene.setAppState(
            "currentItemStrokeColor" to JsonPrimitive(active.color),
            "currentItemStrokeWidth" to JsonPrimitive(active.strokeWidth),
            "currentItemOpacity" to JsonPrimitive(active.opacity),
        )
        val old = scene.appData.toMutableMap()
        old["version"] = JsonPrimitive(2)
        old["tool"] = JsonPrimitive(tool)
        old["profile"] = JsonPrimitive(profile)
        old["pens"] = buildJsonObject { (PenProfiles.all + PenProfiles.highlighter).forEach { put(it.id, penFor(it.id).toJson()) } }
        old.remove("pen"); old.remove("highlighter")
        old["pages"] = buildJsonObject {
            put("enabled", paged)
            put("direction", direction.name.lowercase())
            put("orientation", orientation.name.lowercase())
            put("format", "A4")
        }
        scene.appData = JsonObject(old)
    }

    companion object {
        fun readFrom(scene: ExcalidrawScene): NoteMeta {
            val d = scene.appData
            val pages = d["pages"] as? JsonObject
            val hasFrames = scene.elements.any { it.type == "frame" && !it.isDeleted && it.pageNumber != null }
            val pens = HashMap<String, PenConfig>()
            (d["pens"] as? JsonObject)?.forEach { (k, v) -> pens[k] = PenConfig.fromJson(v as? JsonObject, PenProfiles.byId(k).default) }
            // v1 files
            (d["pen"] as? JsonObject)?.let { pens.putIfAbsent("pen", PenConfig.fromJson(it, PenProfiles.byId("pen").default)) }
            (d["highlighter"] as? JsonObject)?.let { pens.putIfAbsent("highlighter", PenConfig.fromJson(it, PenProfiles.highlighter.default)) }
            // A file never touched by this app: take the pen from Excalidraw's own appState if present.
            if ("pen" !in pens) {
                val st = scene.appState
                (st["currentItemStrokeColor"] as? JsonPrimitive)?.content?.let { c ->
                    pens["pen"] = PenConfig(c, (st["currentItemStrokeWidth"] as? JsonPrimitive)?.doubleOrNull ?: 1.5, 100)
                }
            }
            return NoteMeta(
                tool = (d["tool"] as? JsonPrimitive)?.content?.takeIf { it in setOf("pen", "highlighter", "eraser", "select") } ?: "pen",
                profile = (d["profile"] as? JsonPrimitive)?.content?.takeIf { id -> PenProfiles.all.any { it.id == id } } ?: "pen",
                pens = pens,
                paged = (pages?.get("enabled") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: hasFrames,
                direction = if ((pages?.get("direction") as? JsonPrimitive)?.content == "horizontal") PageDirection.HORIZONTAL else PageDirection.VERTICAL,
                orientation = if ((pages?.get("orientation") as? JsonPrimitive)?.content == "landscape") PageOrientation.LANDSCAPE else PageOrientation.PORTRAIT,
            )
        }
    }
}
