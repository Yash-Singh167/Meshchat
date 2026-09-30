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
import java.util.UUID\nimport java.nio.ByteBuffer\nimport kotlin.math.min
import java.util.concurrent.ConcurrentHashMap\nimport java.util.concurrent.Executors\nimport java.util.concurrent.TimeUnit\nimport java.util.concurrent.ScheduledFuture

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
    private val seen = ConcurrentHashMap<String, Long>()\n    private data class Assembly(val total: Int, val originalType: Int, val pieces: MutableMap<Int, ByteArray>, var updatedAt: Long)\n    private val assemblies = ConcurrentHashMap<String, Assembly>()
    private val signingKeys = ConcurrentHashMap<String, ByteArray>()\n    private val relayExecutor = Executors.newSingleThreadScheduledExecutor()\n    private var announcementTask: ScheduledFuture<*>? = null\n    private val maxFrameBytes = 500\n    private val fragmentChunkBytes = 450
    private val identity by lazy { MeshIdentity(context) }\n    private val messageStore by lazy { MeshMessageStore(context) }\n    private val noiseSessions by lazy { NoiseSessionManager(context) }\n    private val pendingPrivate = ConcurrentHashMap<String, MutableList<String>>()
    private val localId: ByteArray get() = identity.peerId

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
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                @Suppress("DEPRECATION") gatt.requestMtu(517)
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                centrals.remove(gatt.device.address)
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
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
        sendAnnouncement()\n        announcementTask = relayExecutor.scheduleAtFixedRate({ sendAnnouncement() }, 15, 20, TimeUnit.SECONDS)
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

    fun sendPrivate(peerId: ByteArray, text: String) {
        if (noiseSessions.isEstablished(peerId)) {
            sendEncrypted(peerId, text)
            return
        }
        val key = peerId.toHex()
        pendingPrivate.computeIfAbsent(key) { mutableListOf() }.add(text)
        val handshake = noiseSessions.initiate(peerId)
        sendNoisePacket(MeshPacket.TYPE_NOISE_HANDSHAKE, peerId, handshake)
        onStatus("Starting encrypted session with " + key)
    }

    fun send(text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        if (payload.size > 60_000) {
            onStatus("Message is too large (maximum 60,000 UTF-8 bytes)")
            return
        }
        val packet = MeshPacket(
            type = MeshPacket.TYPE_MESSAGE,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = System.currentTimeMillis(),
            senderId = localId,
            recipientId = MeshPacket.BROADCAST,
            payload = payload
        )
        val signed = packet.copy(signature = identity.sign(packet.bytesForSigning()))
        val wire = signed.encode()
        remember(signed, wire)
        sendEncoded(signed, wire)
        messageStore.add(localId.toHex(), text, false)\n        onMessage(localId.toHex(), text, false)
    }

    private fun handlePacket(data: ByteArray) {
        val packet = MeshPacket.decode(data) ?: return
        val now = SystemClock.elapsedRealtime()
        pruneSeen(now)
        pruneAssemblies(now)
        val key = MeshPacket.dedupKey(packet, data)
        if (seen.putIfAbsent(key, now) != null) return

        // Fragments are themselves routable packets. Relay the fragment first,
        // then assemble locally. The original packet is only delivered after
        // all pieces are present, preventing partial application delivery.
        if (packet.type == MeshPacket.TYPE_FRAGMENT) {
            handleFragment(packet, now)
        } else {
            if (packet.type == MeshPacket.TYPE_ANNOUNCE) rememberAnnouncement(packet)
            if ((packet.type == MeshPacket.TYPE_MESSAGE || packet.type == MeshPacket.TYPE_NOISE_HANDSHAKE || packet.type == MeshPacket.TYPE_NOISE_ENCRYPTED) && !isAuthentic(packet)) return
            deliver(packet)
        }

        packet.relay()?.let { relay ->
            if (relay.type == MeshPacket.TYPE_FRAGMENT || relay.isBroadcast() ||
                relay.recipientId?.contentEquals(localId) == false) {
                sendEncoded(relay, relay.encode())
            }
        }
    }

    private fun rememberAnnouncement(packet: MeshPacket) {
        val tlvs = parseTlvs(packet.payload)
        val signingKey = tlvs[0x03] ?: return
        if (signingKey.size != 32 || packet.signature == null) return
        if (!MeshIdentity.verify(packet.bytesForSigning(), packet.signature, signingKey)) return
        signingKeys[packet.senderId.toHex()] = signingKey
        onStatus("Peer " + packet.senderId.toHex() + " authenticated")
    }

    private fun isAuthentic(packet: MeshPacket): Boolean {
        val signature = packet.signature ?: return false
        val key = signingKeys[packet.senderId.toHex()] ?: return false
        return MeshIdentity.verify(packet.bytesForSigning(), signature, key)
    }

    private fun parseTlvs(payload: ByteArray): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        var offset = 0
        while (offset + 2 <= payload.size) {
            val type = payload[offset].toInt() and 0xFF
            val length = payload[offset + 1].toInt() and 0xFF
            offset += 2
            if (offset + length > payload.size) break
            result[type] = payload.copyOfRange(offset, offset + length)
            offset += length
        }
        return result
    }

    private fun sendAnnouncement() {
        val nickname = "MeshChat"
        val nicknameBytes = nickname.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(2 + nicknameBytes.size + 2 + identity.noisePublic.size + 2 + identity.signingPublic.size).also { out ->
            var p = 0
            fun putTlv(type: Int, value: ByteArray) {
                out[p++] = type.toByte()
                out[p++] = value.size.toByte()
                value.copyInto(out, p)
                p += value.size
            }
            putTlv(0x01, nicknameBytes)
            putTlv(0x02, identity.noisePublic)
            putTlv(0x03, identity.signingPublic)
        }
        val packet = MeshPacket(
            type = MeshPacket.TYPE_ANNOUNCE,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = System.currentTimeMillis(),
            senderId = localId,
            recipientId = null,
            payload = payload
        )
        val signed = packet.copy(signature = identity.sign(packet.bytesForSigning()))
        val wire = signed.encode()
        remember(signed, wire)
        sendEncoded(signed, wire)
    }

    private fun handleNoise(packet: MeshPacket) {
        val peer = packet.senderId
        if (!packet.isFor(localId)) return
        if (packet.type == MeshPacket.TYPE_NOISE_HANDSHAKE) {
            val response = runCatching { noiseSessions.handleHandshake(peer, packet.payload) }.getOrNull()
            if (response != null) sendNoisePacket(MeshPacket.TYPE_NOISE_HANDSHAKE, peer, response)
            if (noiseSessions.isEstablished(peer)) {
                pendingPrivate.remove(peer.toHex())?.forEach { sendEncrypted(peer, it) }
                onStatus("Encrypted session established with " + peer.toHex())
            }
        } else if (packet.type == MeshPacket.TYPE_NOISE_ENCRYPTED) {
            val plaintext = noiseSessions.decrypt(peer, packet.payload) ?: return
            onMessage(peer.toHex(), plaintext.toString(Charsets.UTF_8), packet.ttl < MeshPacket.DEFAULT_TTL)
        }
    }

    private fun sendNoisePacket(type: Int, peerId: ByteArray, payload: ByteArray) {
        val packet = MeshPacket(
            type = type,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = System.currentTimeMillis(),
            senderId = localId,
            recipientId = peerId,
            payload = payload
        )
        val signed = packet.copy(signature = identity.sign(packet.bytesForSigning()))
        val wire = signed.encode()
        remember(signed, wire)
        sendEncoded(signed, wire)
    }

    private fun sendEncrypted(peerId: ByteArray, text: String) {
        val ciphertext = noiseSessions.encrypt(peerId, text.toByteArray(Charsets.UTF_8)) ?: return
        sendNoisePacket(MeshPacket.TYPE_NOISE_ENCRYPTED, peerId, ciphertext)
    }

    private fun deliver(packet: MeshPacket) {
        if (packet.type == MeshPacket.TYPE_NOISE_HANDSHAKE || packet.type == MeshPacket.TYPE_NOISE_ENCRYPTED) {
            handleNoise(packet)
            return
        }
        if (packet.type == MeshPacket.TYPE_MESSAGE && packet.isFor(localId)) {
            val text = packet.payload.toString(Charsets.UTF_8)
            val relayed = packet.ttl < MeshPacket.DEFAULT_TTL\n            messageStore.add(packet.senderId.toHex(), text, relayed)\n            onMessage(packet.senderId.toHex(), text, relayed)
        }
    }

    private fun handleFragment(packet: MeshPacket, now: Long) {
        val fragment = MeshFragment.decode(packet.payload) ?: return
        val key = packet.senderId.toHex() + ":" + fragment.id
        val assembly = assemblies.compute(key) { _, existing ->
            val a = existing ?: Assembly(fragment.total, fragment.originalType, mutableMapOf(), now)
            if (a.total != fragment.total || a.originalType != fragment.originalType) return@compute null
            if (a.pieces.size < 10_000) a.pieces.putIfAbsent(fragment.index, fragment.data)
            a.updatedAt = now
            a
        } ?: return
        if (assembly.pieces.size != assembly.total) return

        val reassembled = ByteArray(assembly.pieces.values.sumOf { it.size })
        var offset = 0
        for (i in 0 until assembly.total) {
            val piece = assembly.pieces[i] ?: run { return }
            piece.copyInto(reassembled, offset)
            offset += piece.size
        }
        assemblies.remove(key)
        val original = MeshPacket.decode(reassembled) ?: return
        if (original.type != fragment.originalType) return
        if (original.type == MeshPacket.TYPE_ANNOUNCE) rememberAnnouncement(original)
        if ((original.type == MeshPacket.TYPE_MESSAGE || original.type == MeshPacket.TYPE_NOISE_HANDSHAKE || original.type == MeshPacket.TYPE_NOISE_ENCRYPTED) && !isAuthentic(original)) return
        deliver(original)
    }

    private fun pruneAssemblies(now: Long) {
        assemblies.entries.removeIf { now - it.value.updatedAt > 30_000L }
    }

    private fun sendEncoded(packet: MeshPacket, wire: ByteArray) {
        if (wire.size <= maxFrameBytes) {
            writeToCentrals(wire)
            notifySubscribers(wire)
            return
        }
        // Fragment the complete encoded packet exactly as iOS does: each
        // fragment payload begins with 8-byte stream ID, index, total and
        // original packet type, followed by a raw slice of the encoded packet.
        val streamId = ByteBuffer.allocate(8).putLong(SecureRandom().nextLong()).array()
        val total = (wire.size + fragmentChunkBytes - 1) / fragmentChunkBytes
        require(total <= 10_000)
        for (index in 0 until total) {
            val start = index * fragmentChunkBytes
            val end = minOf(start + fragmentChunkBytes, wire.size)
            val fragment = MeshFragment(
                id = ByteBuffer.wrap(streamId).long,
                index = index,
                total = total,
                originalType = packet.type,
                data = wire.copyOfRange(start, end)
            )
            val fragmentPacket = MeshPacket(
                version = 1,
                type = MeshPacket.TYPE_FRAGMENT,
                ttl = packet.ttl,
                timestamp = packet.timestamp,
                senderId = packet.senderId,
                recipientId = packet.recipientId,
                payload = fragment.encode(),
                signature = null
            )
            val fragmentWire = fragmentPacket.encode()
            remember(fragmentPacket, fragmentWire)
            writeToCentrals(fragmentWire)
            notifySubscribers(fragmentWire)
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
            c.value = data
            @Suppress("DEPRECATION")
            server?.notifyCharacteristicChanged(device, c, false)
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
