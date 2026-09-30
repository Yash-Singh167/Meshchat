# MeshChat Android

Native Bluetooth-only Android foundation for MeshChat.

## Implemented

- Same 128-bit BLE service and characteristic UUIDs as the existing iOS transport.
- BLE advertising + scanning.
- GATT central + peripheral roles.
- Bitchat-compatible v1 packet envelope.
- Public broadcast messages.
- TTL decrement and multi-hop relay.
- Duplicate suppression with bounded five-minute retention.
- No Nostr, Tor, Wi-Fi Aware, HTTP, or Internet transport.

## Test

Open `android/` in Android Studio and install on two or three physical Android phones. Grant Bluetooth permissions and keep the app open. Send from A; B should receive it. With B physically between A and C, C should receive the packet with the `relayed` marker.

## Current boundary

This is a transport milestone, not the final security layer. The temporary Android identity is a locally generated 8-byte ID and public messages are unsigned. The next milestone is the shared Noise identity, signed announcements, private encrypted messages, fragmentation, and a foreground service.
