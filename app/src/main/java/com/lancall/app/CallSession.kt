package com.lancall.app

import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import java.net.URI
import java.util.UUID

open class Sdp : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

/** جلسة مكالمة: mode = "one" (فردي، طرفان) أو "group" (جماعي، حتى 4 أجهزة بنمط mesh). */
class CallSession(private val ctx: Context, val name: String, val tag: String, private val disc: Discovery) {
    val id = UUID.randomUUID().toString().take(8)
    val egl: EglBase = EglBase.create()
    val remotes = mutableStateMapOf<String, VideoTrack>()
    var local by mutableStateOf<VideoTrack?>(null)
    var status by mutableStateOf("جارٍ الاتصال...")
    var micOn by mutableStateOf(true)
    var camOn by mutableStateOf(true)
    var speaker by mutableStateOf(true)
    val startedAt = System.currentTimeMillis()
    private var closed = false

    private var server: Signaling? = null
    private var ws: WebSocketClient? = null
    private val pcs = HashMap<String, PeerConnection>()
    private val pendingIce = HashMap<String, MutableList<IceCandidate>>()
    private val factory: PeerConnectionFactory
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private lateinit var audioTrack: AudioTrack
    private lateinit var videoTrack: VideoTrack
    private val audioMgr = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        audioMgr.mode = AudioManager.MODE_IN_COMMUNICATION
        audioMgr.isSpeakerphoneOn = true

        audioTrack = factory.createAudioTrack("a", factory.createAudioSource(MediaConstraints()))
        val src = factory.createVideoSource(false)
        helper = SurfaceTextureHelper.create("cap", egl.eglBaseContext)
        val en = Camera2Enumerator(ctx)
        val cam = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull()
        if (cam != null) {
            capturer = en.createCapturer(cam, null)
            capturer?.initialize(helper, ctx, src.capturerObserver)
            capturer?.startCapture(640, 480, 30)
        }
        videoTrack = factory.createVideoTrack("v", src)
        local = videoTrack
    }

    fun host(mode: String) {
        server = Signaling(PORT, if (mode == "one") 2 else 4, tag) { join("127.0.0.1", PORT) }
        server!!.start()
        disc.advertise(name, mode, tag.take(8))
    }

    fun join(host: String, port: Int) {
        ws = object : WebSocketClient(URI("ws://$host:$port")) {
            override fun onOpen(h: ServerHandshake?) {
                send(JSONObject().put("type", "join").put("from", id).put("tag", tag).toString())
                status = "بانتظار انضمام الآخرين..."
            }
            override fun onMessage(m: String) = handle(JSONObject(m))
            override fun onClose(c: Int, r: String?, remote: Boolean) { status = "انتهى الاتصال" }
            override fun onError(e: Exception?) { status = "تعذّر الاتصال" }
        }.also { it.connect() }
    }

    private fun send(m: JSONObject) { ws?.send(m.put("from", id).toString()) }

    private fun handle(m: JSONObject) {
        when (m.getString("type")) {
            "full" -> status = "الغرفة ممتلئة"
            "denied" -> status = "رمز الخصوصية غير صحيح"
            "peers" -> m.getJSONArray("ids").let { a -> for (i in 0 until a.length()) offerTo(a.getString(i)) }
            "left" -> m.getString("id").let { pcs.remove(it)?.close(); remotes.remove(it); adapt() }
            "sdp" -> onSdp(m)
            "ice" -> {
                val from = m.getString("from")
                val c = IceCandidate(m.getString("mid"), m.getInt("idx"), m.getString("cand"))
                val pc = pcs[from]
                if (pc?.remoteDescription != null) pc.addIceCandidate(c)
                else pendingIce.getOrPut(from) { mutableListOf() }.add(c)
            }
        }
    }

    private fun offerTo(peer: String) {
        val pc = newPc(peer)
        pc.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription) {
                pc.setLocalDescription(Sdp(), d)
                send(JSONObject().put("type", "sdp").put("to", peer).put("kind", "offer").put("sdp", d.description))
            }
        }, MediaConstraints())
    }

    private fun onSdp(m: JSONObject) {
        val from = m.getString("from")
        val d = SessionDescription(SessionDescription.Type.fromCanonicalForm(m.getString("kind")), m.getString("sdp"))
        val pc = pcs[from] ?: newPc(from)
        pc.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                pendingIce.remove(from)?.forEach { pc.addIceCandidate(it) }
                if (d.type == SessionDescription.Type.OFFER) {
                    pc.createAnswer(object : Sdp() {
                        override fun onCreateSuccess(a: SessionDescription) {
                            pc.setLocalDescription(Sdp(), a)
                            send(JSONObject().put("type", "sdp").put("to", from).put("kind", "answer").put("sdp", a.description))
                        }
                    }, MediaConstraints())
                }
            }
        }, d)
    }

    private fun newPc(peer: String): PeerConnection {
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) = send(
                JSONObject().put("type", "ice").put("to", peer)
                    .put("mid", c.sdpMid).put("idx", c.sdpMLineIndex).put("cand", c.sdp))
            override fun onTrack(t: RtpTransceiver) {
                (t.receiver.track() as? VideoTrack)?.let { remotes[peer] = it }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })!!
        pc.addTrack(audioTrack, listOf("s"))
        pc.addTrack(videoTrack, listOf("s"))
        pcs[peer] = pc
        adapt()
        return pc
    }

    fun toggleMic() { micOn = !micOn; audioTrack.setEnabled(micOn) }
    fun toggleCam() { camOn = !camOn; videoTrack.setEnabled(camOn) }
    fun toggleSpeaker() { speaker = !speaker; audioMgr.isSpeakerphoneOn = speaker }

    /** جودة ذكية: كلما زاد عدد المشاركين تنخفض دقة الكاميرا تلقائيًا لتبقى المكالمة سلسة. */
    private fun adapt() {
        val (w, h, f) = when {
            pcs.size <= 1 -> Triple(1280, 720, 30)
            pcs.size == 2 -> Triple(640, 480, 30)
            else -> Triple(480, 360, 24)
        }
        runCatching { capturer?.changeCaptureFormat(w, h, f) }
    }

    fun switchCamera() { capturer?.switchCamera(null) }

    fun hangUp() {
        if (closed) return
        closed = true
        runCatching { ws?.close() }
        runCatching { server?.stop() }
        disc.stopAdvertise()
        pcs.values.forEach { it.close() }; pcs.clear(); remotes.clear()
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); helper?.dispose()
        factory.dispose(); egl.release()
        audioMgr.mode = AudioManager.MODE_NORMAL
    }
}
