package com.meshchat.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

class MeshChatService : Service() {
    companion object {
        private const val CHANNEL_ID = "meshchat_mesh"
        private const val NOTIFICATION_ID = 42
    }

    private val binder = LocalBinder()
    private var transport: BleMeshTransport? = null
    var onEvent: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun service(): MeshChatService = this@MeshChatService
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Bluetooth mesh active"))
        transport = BleMeshTransport(
            context = this,
            onMessage = { sender, text, relayed ->
                val marker = if (relayed) " · relayed" else ""
                onEvent?.invoke("[" + sender + marker + "] " + text)
            },
            onStatus = { status -> onEvent?.invoke("• " + status) }
        )
        transport?.start()
    }

    fun recentMessages(): List<String> = MeshMessageStore(this).recent()

    fun sendPrivate(peerHex: String, text: String) {
        val peer = peerHex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        if (peer.size == 8) transport?.sendPrivate(peer, text)
        else onEvent?.invoke("• Invalid peer ID; use 16 hex characters")
    }

    fun sendMessage(text: String) {
        transport?.send(text)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        transport?.stop()
        transport = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MeshChat Bluetooth Mesh", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshChat")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
}
