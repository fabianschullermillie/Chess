package dev.inkcalidraw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SyncProtocolTest {
    private fun stroke(n: Int, version: Int = 1, deleted: Boolean = false): JsonObject {
        val e = ExcalidrawScene.newFreedraw((0 until n).map { floatArrayOf(it * 2f, it * 1f, 0.5f) }, "#1e1e1e", 2.0, "a0").json
        val m = LinkedHashMap(e)
        m["id"] = JsonPrimitive("s1"); m["version"] = JsonPrimitive(version); m["versionNonce"] = JsonPrimitive(1000 + version)
        m["isDeleted"] = JsonPrimitive(deleted); m["updated"] = JsonPrimitive(1)
        return JsonObject(m)
    }

    @Test fun diffAndApplyRoundTrip() {
        val a = stroke(50); val b = stroke(80, version = 2)
        val op = SyncProtocol.diffOp(a, b)
        assertNotNull("growing stroke is sent as an append", op["app"])
        assertEquals(30, (op["app"] as JsonObject)["points"]!!.jsonArray.size)
        assertEquals(b, SyncProtocol.applyOp(a, op))
        val erased = stroke(80, version = 3, deleted = true)
        val del = SyncProtocol.diffOp(b, erased)
        assertTrue("erase patch is tiny: ${del.toString().length}", del.toString().length < 120)
        assertEquals(erased, SyncProtocol.applyOp(b, del))
        assertEquals(null, SyncProtocol.applyOp(a, del)) // wrong base
    }

    @Test fun deflateRoundTrip() {
        val msg = buildJsonObject { put("t", "push"); put("ops", JsonArray(listOf(SyncProtocol.diffOp(null, stroke(400))))) }
        val enc = SyncProtocol.encode(msg)
        assertTrue(enc is ByteArray && enc.size < msg.toString().length / 2)
        assertEquals(msg, SyncProtocol.decode(enc as ByteArray))
    }

    /** Talks to a running sync server (wrangler dev): Kotlin ops are applied by the TypeScript server. */
    @Test fun againstLocalServer() {
        val base = System.getProperty("syncUrl")
        val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        val up = runCatching { http.newCall(Request.Builder().url(base).build()).execute().use { it.isSuccessful } }.getOrDefault(false)
        assumeTrue("sync server not running at $base", up)
        val token = http.newCall(Request.Builder().url("$base/auth/dev?login=kotlin").build()).execute().use { (Json.parseToJsonElement(it.body!!.string()) as JsonObject)["token"]!!.let { t -> (t as JsonPrimitive).content } }
        val note = "kt" + System.nanoTime().toString(36)
        fun client(): Pair<WebSocket, LinkedBlockingQueue<JsonObject>> {
            val q = LinkedBlockingQueue<JsonObject>()
            val ws = http.newWebSocket(Request.Builder().url(base.replace("http", "ws") + "/api/notes/$note/ws?token=$token").build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { q.put(SyncProtocol.decode(text)) }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { q.put(SyncProtocol.decode(bytes.toByteArray())) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { t.printStackTrace() }
            })
            return ws to q
        }
        fun WebSocket.push(m: JsonObject) { when (val d = SyncProtocol.encode(m)) { is String -> send(d); is ByteArray -> send(d.toByteString()) } }
        fun LinkedBlockingQueue<JsonObject>.next(t: String): JsonObject { while (true) { val m = poll(5, TimeUnit.SECONDS) ?: error("no $t"); if ((m["t"] as JsonPrimitive).content == t) return m } }

        val (a, qa) = client(); val (b, qb) = client()
        a.push(buildJsonObject { put("t", "hello"); put("since", 0) }); b.push(buildJsonObject { put("t", "hello"); put("since", 0) })
        qa.next("sync"); qb.next("sync")

        val s1 = SyncProtocol.normalize(stroke(300)); val s2 = SyncProtocol.normalize(stroke(400, version = 2)); val s3 = SyncProtocol.normalize(stroke(400, version = 3, deleted = true))
        a.push(buildJsonObject { put("t", "push"); put("ops", JsonArray(listOf(SyncProtocol.diffOp(null, s1)))) }); qa.next("ack")
        a.push(buildJsonObject { put("t", "push"); put("ops", JsonArray(listOf(SyncProtocol.diffOp(s1, s2)))) }); qa.next("ack")
        a.push(buildJsonObject { put("t", "push"); put("ops", JsonArray(listOf(SyncProtocol.diffOp(s2, s3)))) }); qa.next("ack")

        // B follows along by applying the broadcast ops
        var bView: JsonObject? = null
        repeat(3) { for (o in qb.next("ops")["ops"]!!.jsonArray) bView = SyncProtocol.applyOp(bView, o.jsonObject) }
        assertEquals(s3, bView)

        val snap = http.newCall(Request.Builder().url("$base/api/notes/$note/snapshot").header("Authorization", "Bearer $token").build()).execute().use { Json.parseToJsonElement(it.body!!.string()) as JsonObject }
        val serverEl = snap["elements"]!!.jsonArray.single().jsonObject
        assertEquals(s3, serverEl)
        a.close(1000, null); b.close(1000, null)
        println("kotlin client ↔ TypeScript server: full + append + erase applied identically")
    }
}
