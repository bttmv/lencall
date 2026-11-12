package com.lancall.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ServiceCompat

/** يحتفظ بالمكالمة الحالية حتى لو أُغلقت الواجهة أو انتقل التطبيق للخلفية. */
object Hub { var session by mutableStateOf<CallSession?>(null) }

class CallService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("call", "المكالمات", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, CallService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "call")
            .setContentTitle("TMT — مكالمة جارية")
            .setContentText("اضغط للعودة إلى المكالمة")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(0, "إنهاء", stop).build())
            .build()
        ServiceCompat.startForeground(this, 1, n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }

    override fun onDestroy() {
        Hub.session?.hangUp()
        Hub.session = null
        super.onDestroy()
    }
}
