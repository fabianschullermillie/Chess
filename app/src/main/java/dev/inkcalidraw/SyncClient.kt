package dev.inkcalidraw

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/** Server URL + login, stored on the device. */
class SyncAccount(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("sync", 0)
    var url: String
        get() = prefs.getString("url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SYNC_URL
        set(v) = prefs.edit().putString("url", v.trim().trimEnd('/')).apply()
    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()
    var login: String?
        get() = prefs.getString("login", null)
        set(v) = prefs.edit().putString("login", v).apply()
    val signedIn get() = url.isNotBlank() && token != null

    fun signOut() { token = null; login = null }

    companion object {
        val http: OkHttpClient by lazy { OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
    }
}

/** Plain REST calls (blocking: call from a background thread). */
class SyncApi(private val account: SyncAccount) {
    private fun req(path: String) = Request.Builder().url(account.url + path).header("Authorization", "Bearer ${account.token}")

    private fun <T> call(r: Request, body: (Response) -> T): T = SyncAccount.http.newCall(r).execute().use { res ->
        if (res.code == 401) { account.signOut(); error("signed out") }
        body(res)
    }

    fun putNote(id: String, name: String, folder: String) = call(req("/api/notes/$id").put(
        buildJsonObject { put("name", name); put("folder", folder) }.toString().toRequestBody(JSON),
    ).build()) { }

    fun notes(): List<JsonObject> = call(req("/api/notes").build()) { r -> (Json.parseToJsonElement(r.body!!.string()) as JsonArray).map { it as JsonObject } }

    fun snapshot(id: String): JsonObject = call(req("/api/notes/$id/snapshot").build()) { r -> Json.parseToJsonElement(r.body!!.string()) as JsonObject }

    fun hasFile(sha: String): Boolean = call(req("/api/files/$sha").head().build()) { it.code == 200 }
    fun putFile(sha: String, mime: String, bytes: ByteArray) = call(req("/api/files/$sha").put(bytes.toRequestBody(mime.toMediaType())).build()) { r -> check(r.isSuccessful) { "upload failed: ${r.code}" } }
    fun getFile(sha: String): ByteArray = call(req("/api/files/$sha").build()) { r -> check(r.isSuccessful) { "download failed: ${r.code}" }; r.body!!.bytes() }

    /** GitHub device flow: returns {user_code, verification_uri, device_code, interval}. */
    fun deviceStart(): JsonObject = SyncAccount.http.newCall(Request.Builder().url(account.url + "/auth/device/start").post("{}".toRequestBody(JSON)).build())
        .execute().use { Json.parseToJsonElement(it.body!!.string()) as JsonObject }

    fun devicePoll(deviceCode: String): JsonObject = SyncAccount.http.newCall(Request.Builder().url(account.url + "/auth/device/poll")
        .post(buildJsonObject { put("device_code", deviceCode) }.toString().toRequestBody(JSON)).build())
        .execute().use { Json.parseToJsonElement(it.body!!.string()) as JsonObject }

    companion object { val JSON = "application/json".toMediaType() }
}

/**
 * Live sync of one note. `known` mirrors what the server has for every element, so local edits go out
 * as patches/appends against it and remote edits are merged by version.
 */
class NoteSync(
    private val account: SyncAccount,
    private val noteId: String,
    startSeq: Long,
    /** Called on the main thread with remote element changes and/or meta. */
    private val onRemote: (List<JsonObject>, JsonObject?) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val known = HashMap<String, JsonObject>()
    private val outbox = LinkedHashMap<String, JsonObject>()
    private var pendingMeta: JsonObject? = null
    private var ws: WebSocket? = null
    private var live = false
    private var closed = false
    private var retryMs = 500L
    @Volatile var seq: Long = startSeq; private set
    @Volatile var sentBytes = 0L; private set
    private val flushTask = Runnable { flush() }
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor()

    init { connect() }

    private fun connect() {
        if (closed) return
        val url = account.url.replaceFirst("http", "ws") + "/api/notes/$noteId/ws?token=${account.token}"
        onStatus("connecting")
        ws = SyncAccount.http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryMs = 500
                send(buildJsonObject { put("t", "hello"); put("since", seq) })
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(SyncProtocol.decode(text))
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(SyncProtocol.decode(bytes.toByteArray()))
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped()
        })
    }

    private fun dropped() {
        synchronized(lock) { live = false; ws = null }
        main.post { onStatus("offline") }
        if (!closed) main.postDelayed({ connect() }, retryMs.also { retryMs = (retryMs * 2).coerceAtMost(15_000) })
    }

    private fun send(m: JsonObject) {
        val ws = ws ?: return
        when (val data = SyncProtocol.encode(m)) {
            is String -> { ws.send(data); sentBytes += data.length }
            is ByteArray -> { ws.send(data.toByteString()); sentBytes += data.size }
        }
    }

    private fun handle(msg: JsonObject) {
        val changed = ArrayList<JsonObject>()
        var meta: JsonObject? = null
        when ((msg["t"] as JsonPrimitive).content) {
            "sync" -> synchronized(lock) {
                (msg["ops"] as JsonArray).forEach { o -> (o as JsonObject)["full"]?.let { f -> f as JsonObject; known[SyncProtocol.id(f)] = f; changed += f } }
                seq = (msg["seq"] as JsonPrimitive).longOrNull ?: seq
                meta = msg["meta"] as? JsonObject
                live = true
                main.post { onStatus("live") }
                if (outbox.isNotEmpty() || pendingMeta != null) main.post { schedule() }
            }
            "ops", "full" -> synchronized(lock) {
                val missing = ArrayList<String>()
                for (o in msg["ops"] as JsonArray) {
                    val op = o as JsonObject
                    val id = SyncProtocol.opId(op)
                    val cur = known[id]
                    if (cur != null && !SyncProtocol.wins(SyncProtocol.opVersion(op), SyncProtocol.opNonce(op), cur)) continue
                    val next = SyncProtocol.applyOp(cur, op)
                    if (next == null) { missing += id; continue }
                    known[id] = next; changed += next
                }
                (msg["seq"] as? JsonPrimitive)?.longOrNull?.let { seq = maxOf(seq, it) }
                meta = msg["meta"] as? JsonObject
                if (missing.isNotEmpty()) send(buildJsonObject { put("t", "get"); put("ids", JsonArray(missing.map { JsonPrimitive(it) })) })
            }
            "ack" -> synchronized(lock) {
                (msg["seq"] as? JsonPrimitive)?.longOrNull?.let { seq = maxOf(seq, it) }
                val need = (msg["need"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()
                for (id in need) known.remove(id)?.let { outbox[id] = it }
                if (need.isNotEmpty()) main.post { schedule() }
            }
        }
        if (changed.isNotEmpty() || meta != null) main.post { onRemote(changed, meta) }
    }

    /** Queue local elements whose version differs from the server's (main thread). */
    fun local(elements: List<Element>) {
        synchronized(lock) {
            for (e in elements) {
                val k = known[e.id]
                if (k != null && SyncProtocol.version(k) == e.version.toLong() && SyncProtocol.nonce(k) == SyncProtocol.nonce(e.json)) continue
                if (outbox[e.id]?.let { SyncProtocol.version(it) == e.version.toLong() } == true) continue
                outbox[e.id] = SyncProtocol.normalize(e.json)
            }
        }
        schedule()
    }

    fun meta(m: JsonObject) {
        synchronized(lock) {
            val cur = pendingMeta
            pendingMeta = if (cur == null) m else JsonObject(cur + m + ("files" to JsonObject(((cur["files"] as? JsonObject) ?: JsonObject(emptyMap())) + ((m["files"] as? JsonObject) ?: JsonObject(emptyMap())))))
        }
        schedule()
    }

    private fun schedule() { main.removeCallbacks(flushTask); main.postDelayed(flushTask, 40) }

    private fun flush() {
        val msg = synchronized(lock) {
            if (!live || ws == null || (outbox.isEmpty() && pendingMeta == null)) return
            val ops = outbox.map { (id, e) -> SyncProtocol.diffOp(known[id], e).also { known[id] = e } }
            outbox.clear()
            val meta = pendingMeta; pendingMeta = null
            buildJsonObject {
                put("t", "push"); put("ops", JsonArray(ops))
                if (meta != null) put("meta", meta)
            }
        }
        sender.execute { send(msg) } // keep encoding/network writes off the UI thread, in order
    }

    fun close() { closed = true; main.removeCallbacks(flushTask); flush(); sender.execute { ws?.close(1000, "closed") }; sender.shutdown() }
}
