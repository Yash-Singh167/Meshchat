package com.meshchat.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var log: TextView
    private lateinit var transport: BleMeshTransport
    private val permissionRequest = 7001

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
        log = TextView(this).apply { text = "Starting…\n"; textSize = 14f }
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val input = EditText(this).apply { hint = "Message"; singleLine = true }
        root.addView(input)
        root.addView(Button(this).apply {
            text = "SEND OVER BLUETOOTH"
            setOnClickListener {
                val message = input.text.toString().trim()
                if (message.isNotEmpty()) {
                    transport.send(message)
                    input.text.clear()
                }
            }
        })
        setContentView(root)

        transport = BleMeshTransport(this,
            onMessage = { sender, message, relayed ->
                runOnUiThread {
                    val marker = if (relayed) " · relayed" else ""
                    append("[" + sender + marker + "] " + message)
                }
            },
            onStatus = { status -> runOnUiThread { append("• " + status) } }
        )

        if (hasPermissions()) transport.start()
        else ActivityCompat.requestPermissions(this, permissions(), permissionRequest)
    }

    override fun onDestroy() {
        transport.stop()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == permissionRequest && results.all { it == PackageManager.PERMISSION_GRANTED }) transport.start()
        else append("Bluetooth permission denied")
    }

    private fun hasPermissions() = permissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissions(): Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun append(line: String) { log.append(line + "\n") }
}
