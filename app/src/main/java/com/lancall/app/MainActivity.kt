package com.lancall.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

val Bg = Color(0xFF0B1020)
val CardC = Color(0xFF141B34)
val Cyan = Color(0xFF22D3EE)
val Violet = Color(0xFF8B5CF6)
val Muted = Color(0xFF8A94B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Violet, background = Bg, surface = CardC, onPrimary = Bg)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { App() }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("tmt", 0) }
    val disc = remember { Discovery(ctx) }
    var name by remember { mutableStateOf(prefs.getString("name", "") ?: "") }
    var pin by remember { mutableStateOf(prefs.getString("pin", "") ?: "") }
    var mode by remember { mutableStateOf("group") }
    var ip by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val tag = remember(pin) { keyTag(pin) }
    val session = Hub.session

    val perms = buildList {
        add(Manifest.permission.CAMERA); add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) { add(Manifest.permission.NEARBY_WIFI_DEVICES); add(Manifest.permission.POST_NOTIFICATIONS) }
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] == true && r[Manifest.permission.RECORD_AUDIO] == true) pending?.invoke()
        pending = null
    }
    fun withPerms(a: () -> Unit) { pending = a; launcher.launch(perms) }
    fun startCall(make: CallSession.() -> Unit) = withPerms {
        prefs.edit().putString("name", name).putString("pin", pin).apply()
        val s = CallSession(ctx, name.ifBlank { "TMT" }, tag, disc)
        Hub.session = s
        s.make()
        ContextCompat.startForegroundService(ctx, Intent(ctx, CallService::class.java))
    }

    DisposableEffect(session == null) {
        if (session == null) disc.startDiscovery()
        onDispose { disc.stopDiscovery() }
    }
    val visible = disc.rooms.filter { it.tag == tag.take(8) }
    LaunchedEffect(visible.size, auto, session == null) {
        if (auto && session == null && pin.isNotBlank() && visible.isNotEmpty()) {
            val r = visible.first(); auto = false
            startCall { join(r.host, r.port) }
        }
    }

    if (session != null) {
        CallScreen(session) { ctx.stopService(Intent(ctx, CallService::class.java)); session.hangUp(); Hub.session = null }
        return
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF16204A), Bg)))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_tmt_logo), "TMT")
                Spacer(Modifier.weight(1f))
                Text("🔒 خاص", color = Violet, fontWeight = FontWeight.Bold)
            }
            Text("اتصل بأجهزتك بدون إنترنت", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)

            Column(Modifier.clip(RoundedCornerShape(24.dp)).background(CardC).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("اسمك") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pin, { pin = it }, label = { Text("رمز الخصوصية (سرّي)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("فردي", mode == "one", Modifier.weight(1f)) { mode = "one" }
                    Pill("جماعي", mode == "group", Modifier.weight(1f)) { mode = "group" }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("انضمام تلقائي عند ظهور غرفتي", Modifier.weight(1f), color = Muted)
                    Switch(auto, { auto = it }, enabled = pin.isNotBlank())
                }
            }

            Button({ startCall { host(mode) } }, Modifier.fillMaxWidth().height(54.dp),
                enabled = pin.isNotBlank(), shape = RoundedCornerShape(18.dp)) {
                Text("إنشاء غرفة", fontWeight = FontWeight.Bold)
            }
            if (pin.isBlank()) Text("أدخل رمز الخصوصية أولًا — لن يراك أو يدخل عليك أحد بدونه.", color = Muted, fontSize = 12.sp)

            Text("أجهزتي القريبة", color = Color.White, fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (visible.isEmpty()) item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("جارٍ البحث...", color = Muted)
                    }
                }
                items(visible) { r ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardC)
                        .clickable(enabled = pin.isNotBlank()) { startCall { join(r.host, r.port) } }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF22C55E)))
                        Spacer(Modifier.width(12.dp))
                        Text(r.name, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Medium)
                        Text(if (r.mode == "one") "فردي" else "جماعي", color = Cyan)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ip, { ip = it }, label = { Text("أو IP المضيف") }, singleLine = true, modifier = Modifier.weight(1f))
                Button({ startCall { join(ip.trim(), PORT) } }, enabled = ip.isNotBlank() && pin.isNotBlank()) { Text("انضمام") }
            }
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) Cyan else Color(0xFF1E2748))
        .clickable { onClick() }.padding(12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) Bg else Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
fun CircleBtn(label: String, on: Boolean = true, red: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(54.dp).clip(CircleShape)
        .background(if (red) Color(0xFFEF4444) else if (on) Color(0xFF2A3560) else Color(0xFF4A2A3F))
        .clickable { onClick() }, contentAlignment = Alignment.Center) { Text(label, fontSize = 22.sp) }
}

@Composable
fun CallScreen(s: CallSession, onEnd: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val sec = ((now - s.startedAt) / 1000).toInt()
    val tiles = s.remotes.entries.map { it.key to it.value }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (tiles.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(painterResource(R.drawable.ic_tmt_logo), "TMT")
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                Text(s.status, color = Muted)
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 44.dp, bottom = 100.dp)) {
                tiles.chunked(2).forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        row.forEach { (k, t) ->
                            Video(t, s, Modifier.weight(1f).fillMaxHeight().padding(3.dp).clip(RoundedCornerShape(20.dp)), k)
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("%02d:%02d".format(sec / 60, sec % 60), color = Color.White, fontWeight = FontWeight.Bold)
            Text("${tiles.size + 1} مشارك", color = Muted)
        }
        s.local?.let {
            Video(it, s, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 110.dp)
                .size(96.dp, 136.dp).clip(RoundedCornerShape(16.dp)), "me", mirror = true)
        }
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)
            .clip(RoundedCornerShape(36.dp)).background(Color(0xE6141B34)).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircleBtn(if (s.micOn) "🎤" else "🔇", s.micOn) { s.toggleMic() }
            CircleBtn(if (s.camOn) "📷" else "🚫", s.camOn) { s.toggleCam() }
            CircleBtn("🔄") { s.switchCamera() }
            CircleBtn(if (s.speaker) "🔊" else "📱", s.speaker) { s.toggleSpeaker() }
            CircleBtn("📞", red = true) { onEnd() }
        }
    }
}

@Composable
fun Video(track: VideoTrack, s: CallSession, modifier: Modifier, key: String, mirror: Boolean = false) {
    key(key) {
        AndroidView(
            factory = { c ->
                SurfaceViewRenderer(c).apply {
                    init(s.egl.eglBaseContext, null)
                    setEnableHardwareScaler(true)
                    setMirror(mirror)
                    setZOrderMediaOverlay(mirror)
                    track.addSink(this)
                }
            },
            modifier = modifier,
            onRelease = { track.removeSink(it); it.release() }
        )
    }
}
