package com.lancall.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.runtime.mutableStateListOf
import java.security.MessageDigest

class Room(val name: String, val host: String, val port: Int, val mode: String, val tag: String)

/** بصمة رمز الخصوصية: لا يظهر لك إلا من يملك نفس الرمز، ولا يدخل غرفتك إلا من يعرفه. */
fun keyTag(pin: String) = MessageDigest.getInstance("SHA-256").digest(("tmt:" + pin).toByteArray())
    .joinToString("") { "%02x".format(it) }.take(32)

const val SERVICE = "_lancall._tcp."
const val PORT = 8765

class Discovery(ctx: Context) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    val rooms = mutableStateListOf<Room>()
    private var regL: NsdManager.RegistrationListener? = null
    private var discL: NsdManager.DiscoveryListener? = null

    fun advertise(name: String, mode: String, tag: String) {
        val info = NsdServiceInfo().apply {
            serviceName = "lancall-$name"; serviceType = SERVICE; port = PORT
            setAttribute("mode", mode); setAttribute("t", tag)
        }
        regL = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(i: NsdServiceInfo?, e: Int) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo?, e: Int) {}
            override fun onServiceRegistered(i: NsdServiceInfo?) {}
            override fun onServiceUnregistered(i: NsdServiceInfo?) {}
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regL)
    }

    fun stopAdvertise() { regL?.let { runCatching { nsd.unregisterService(it) } }; regL = null }

    fun startDiscovery() {
        if (discL != null) return
        discL = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) {}
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo?, e: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val mode = i.attributes["mode"]?.let { String(it) } ?: "group"
                        val tg = i.attributes["t"]?.let { String(it) } ?: ""
                        val r = Room(i.serviceName.removePrefix("lancall-"), i.host.hostAddress ?: return, i.port, mode, tg)
                        rooms.removeAll { it.host == r.host }
                        rooms.add(r)
                    }
                })
            }
            override fun onServiceLost(s: NsdServiceInfo) {
                rooms.removeAll { "lancall-" + it.name == s.serviceName }
            }
        }
        nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discL)
    }

    fun stopDiscovery() { discL?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discL = null }
}
