package com.meshchat.android

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService

class MainActivity : AppCompatActivity() {
    private lateinit var log: TextView
    private lateinit var input: EditText
    private lateinit var peerInput: EditText
    private var meshService: MeshChatService? = null
    private val permissionRequest = 7001

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            meshService = (binder as MeshChatService.LocalBinder).service()
            meshService?.onEvent = { event -> runOnUiThread { append(event) } }
            append("• Mesh service connected")
            meshService?.recentMessages()?.forEach { append(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "MeshChat — Bluetooth Mesh"
            textSize = 24f
        })

        log = TextView(this).apply {
            text = "Starting…\n"
            textSize = 14f
        }
        root.addView(
            ScrollView(this).apply { addView(log) },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        input = EditText(this).apply {
            hint = "Message"
            setSingleLine(true)
        }
        root.addView(input)

        peerInput = EditText(this).apply {
            hint = "Peer ID for private chat (16 hex)"
            setSingleLine(true)
        }
        root.addView(peerInput)

        root.addView(Button(this).apply {
            text = "SEND PUBLIC"
            setOnClickListener {
                val message = input.text.toString().trim()
                if (message.isNotEmpty()) {
                    meshService?.sendMessage(message)
                    input.text.clear()
                }
            }
        })

        root.addView(Button(this).apply {
            text = "SEND PRIVATE (NOISE)"
            setOnClickListener {
                val message = input.text.toString().trim()
                val peer = peerInput.text.toString().trim()
                if (message.isNotEmpty() && peer.isNotEmpty()) {
                    meshService?.sendPrivate(peer, message)
                    input.text.clear()
                }
            }
        })

        setContentView(root)

        if (hasPermissions()) startMeshService()
        else ActivityCompat.requestPermissions(this, permissions(), permissionRequest)
    }

    private fun startMeshService() {
        val intent = Intent(this, MeshChatService::class.java)
        startForegroundService(this, intent)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        meshService?.onEvent = null
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == permissionRequest &&
            results.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startMeshService()
        } else {
            append("Bluetooth permission denied")
        }
    }

    private fun hasPermissions(): Boolean =
        permissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun permissions(): Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun append(line: String) {
        log.append(line + "\n")
    }
}
