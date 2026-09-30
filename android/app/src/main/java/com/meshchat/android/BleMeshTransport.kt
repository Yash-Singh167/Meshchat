package com.meshchat.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BleMeshTransport(
    private val context: Context,
    private val onMessage: (senderId: String, text: String, relayed: Boolean) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var server: BluetoothGattServer? = null
    private val centrals = ConcurrentHashMap<String, BluetoothGatt>()
    private val subscribers = ConcurrentHashMap<String, BluetoothDevice>()
    private val seen = ConcurrentHashMap<String, Long>()
    private val localId by lazy { loadPeerId() }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { onStatus("Advertising") }
        override fun onStartFailure(errorCode: Int) { onStatus("Advertising failed: $errorCode") }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasConnectPermission()) return
            val device = result.device
            if (!centrals.containsKey(device.address)) {
                centrals[device.address] = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }
        override fun onScanFailed(errorCode: Int) { onStatus("Scan failed: $errorCode") }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            ) subscribers[device.address] = device
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) handlePacket(value)
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) subscribers.remove(device.address)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                centrals.remove(gatt.device.address)
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val c = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID) ?: return
            gatt.setCharacteristicNotification(c, true)
            c.getDescriptor(CCCD_UUID)?.let { d ->
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION") gatt.writeDescriptor(d)
            }
            onStatus("Connected " + gatt.device.address.takeLast(5))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) handlePacket(characteristic.value)
        }
    }

    fun start() {
        if (!hasScanPermission()) { onStatus("Bluetooth permission required"); return }
        if (!adapter.isEnabled) { onStatus("Bluetooth is disabled"); return }
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner
        openServer()
        startAdvertising()
        startScanning()
    }

    fun stop() {
        if (hasScanPermission()) scanner?.stopScan(scanCallback)
        if (hasAdvertisePermission()) advertiser?.stopAdvertising(advertiseCallback)
        centrals.values.forEach { it.close() }
        centrals.clear()
        subscribers.clear()
        server?.close()
        server = null
    }

    fun send(text: String) {
        val packet = MeshPacket(
            type = MeshPacket.TYPE_MESSAGE,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = System.currentTimeMillis(),
            senderId = localId,
            recipientId = MeshPacket.BROADCAST,
            payload = text.toByteArray(Charsets.UTF_8)
        )
        val wire = packet.encode()
        remember(packet, wire)
        writeToCentrals(wire)
        notifySubscribers(wire)
        onMessage(localId.toHex(), text, false)
    }

    private fun handlePacket(data: ByteArray) {
        val packet = MeshPacket.decode(data) ?: return
        val now = SystemClock.elapsedRealtime()
        pruneSeen(now)
        val key = packetKey(packet, data)
        if (seen.putIfAbsent(key, now) != null) return

        if (packet.type == MeshPacket.TYPE_MESSAGE &&
            (packet.recipientId == null || packet.recipientId.contentEquals(MeshPacket.BROADCAST))
        ) {
            onMessage(packet.senderId.toHex(), packet.payload.toString(Charsets.UTF_8),
                packet.ttl < MeshPacket.DEFAULT_TTL)
        }

        packet.relay()?.let { relay ->
            val wire = relay.encode()
            writeToCentrals(wire)
            notifySubscribers(wire)
        }
    }

    private fun remember(packet: MeshPacket, wire: ByteArray) {
        seen[packetKey(packet, wire)] = SystemClock.elapsedRealtime()
    }

    private fun packetKey(packet: MeshPacket, wire: ByteArray): String =
        packet.senderId.toHex() + ":" + packet.timestamp + ":" + packet.type + ":" + wire.sha256()

    private fun pruneSeen(now: Long) {
        if (seen.size < 1200) return
        seen.entries.removeIf { now - it.value > 5 * 60_000L }
    }

    @SuppressLint("MissingPermission")
    private fun openServer() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        server = manager.openGattServer(context, serverCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        characteristic.addDescriptor(BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        ))
        service.addCharacteristic(characteristic)
        server?.addService(service)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun writeToCentrals(data: ByteArray) {
        centrals.values.forEach { gatt ->
            val c = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID) ?: return@forEach
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            c.value = data
            gatt.writeCharacteristic(c)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifySubscribers(data: ByteArray) {
        val c = server?.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID) ?: return
        subscribers.values.forEach { device ->
            server?.notifyCharacteristicChanged(device, c, false, data)
        }
    }

    private fun loadPeerId(): ByteArray {
        val prefs = context.getSharedPreferences("meshchat", Context.MODE_PRIVATE)
        val saved = prefs.getString("peer_id", null)
        if (saved != null) return saved.hexToBytes()
        return ByteArray(8).also {
            SecureRandom().nextBytes(it)
            prefs.edit().putString("peer_id", it.toHex()).apply()
        }
    }

    private fun hasScanPermission() =
        android.os.Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    private fun hasConnectPermission() =
        android.os.Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun hasAdvertisePermission() =
        android.os.Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).toHex()
}
