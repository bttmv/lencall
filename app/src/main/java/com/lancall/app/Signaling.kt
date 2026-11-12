package com.lancall.app

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress

/** سيرفر إشارات داخل التطبيق: يمرّر SDP/ICE بين الأطراف فقط، والصوت والفيديو يذهبان مباشرة بين الأجهزة. */
class Signaling(port: Int, private val max: Int, private val tag: String, private val onReady: () -> Unit) :
    WebSocketServer(InetSocketAddress(port)) {

    private val peers = HashMap<String, WebSocket>()

    override fun onStart() { isReuseAddr = true; onReady() }
    override fun onOpen(c: WebSocket, h: ClientHandshake) {}
    override fun onError(c: WebSocket?, e: Exception?) {}

    override fun onMessage(c: WebSocket, msg: String) {
        val m = JSONObject(msg)
        if (m.getString("type") == "join") {
            if (m.optString("tag") != tag) { c.send("""{"type":"denied"}"""); c.close(); return }
            synchronized(peers) {
                if (peers.size >= max) { c.send("""{"type":"full"}"""); return }
                val id = m.getString("from")
                c.setAttachment(id)
                c.send(JSONObject().put("type", "peers").put("ids", JSONArray(peers.keys.toList())).toString())
                peers[id] = c
            }
        } else if (m.has("to") && c.getAttachment<String>() != null) {
            synchronized(peers) { peers[m.getString("to")]?.send(msg) }
        }
    }

    override fun onClose(c: WebSocket, code: Int, reason: String?, remote: Boolean) {
        val id = c.getAttachment<String>() ?: return
        synchronized(peers) {
            peers.remove(id)
            val left = JSONObject().put("type", "left").put("id", id).toString()
            peers.values.forEach { it.send(left) }
        }
    }
}
