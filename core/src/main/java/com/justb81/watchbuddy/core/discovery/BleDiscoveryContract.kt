package com.justb81.watchbuddy.core.discovery

import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID

/**
 * Shared constants and payload encoding for the WatchBuddy BLE discovery
 * channel. Used by the phone's [android.bluetooth.le.BluetoothLeAdvertiser]
 * (via `CompanionBleAdvertiser`) and by the TV's
 * [android.bluetooth.le.BluetoothLeScanner] (via `PhoneBleScanner`) as a
 * non-mDNS fallback for networks that block multicast / isolate clients
 * (hotel and guest Wi-Fi, mesh routers with VLAN segmentation, aggressive
 * IGMP snooping).
 *
 * Wire format (service data attached to [SERVICE_UUID]): 9 bytes, packed big-endian.
 *
 *   | offset | bytes | field                                              |
 *   |--------|-------|----------------------------------------------------|
 *   | 0      | 1     | schema version (current: [PAYLOAD_SCHEMA_VERSION]) |
 *   | 1..4   | 4     | IPv4 address (network byte order)                  |
 *   | 5..6   | 2     | TCP port (big-endian, unsigned)                    |
 *   | 7      | 1     | modelQuality (0..255 clamped; semantic range 0..150) |
 *   | 8      | 1     | llmBackend ordinal (0..255)                        |
 *
 * Total: 9 bytes payload + 16 byte UUID = 27 bytes, well inside the 31-byte
 * legacy advertising envelope. `version` (app versionName) is intentionally
 * omitted from the BLE payload; the TV fetches it via `/capability` once it
 * has a routable `(ip, port)` pair.
 *
 * Schema evolution: additive changes must bump [PAYLOAD_SCHEMA_VERSION];
 * decoders reject unknown versions to avoid misinterpreting future fields.
 */
object BleDiscoveryContract {

    /**
     * Custom 128-bit service UUID the phone advertises and the TV filters on.
     * Random UUIDv4 — not a SIG-assigned short ID; WatchBuddy-specific.
     */
    val SERVICE_UUID: UUID = UUID.fromString("5e4b4d3a-9f7c-4b7e-8e6b-6c0e5f27e4a0")

    const val PAYLOAD_SCHEMA_VERSION: Byte = 1
    const val PAYLOAD_SIZE_BYTES: Int = 9

    data class Payload(
        val ipv4: Inet4Address,
        val port: Int,
        val modelQuality: Int,
        val llmBackendOrdinal: Int,
    )

    /**
     * Packs [payload] into its 9-byte wire form.
     *
     * @throws IllegalArgumentException if port is outside `0..65535` or
     *   `modelQuality` / `llmBackendOrdinal` cannot be represented as an
     *   unsigned byte.
     */
    fun encode(payload: Payload): ByteArray {
        require(payload.port in 0..0xFFFF) { "port out of range: ${payload.port}" }
        require(payload.modelQuality in 0..0xFF) {
            "modelQuality out of range: ${payload.modelQuality}"
        }
        require(payload.llmBackendOrdinal in 0..0xFF) {
            "llmBackendOrdinal out of range: ${payload.llmBackendOrdinal}"
        }
        val ipBytes = payload.ipv4.address
        check(ipBytes.size == 4) { "IPv4 address must be 4 bytes" }
        return ByteArray(PAYLOAD_SIZE_BYTES).apply {
            this[0] = PAYLOAD_SCHEMA_VERSION
            System.arraycopy(ipBytes, 0, this, 1, 4)
            this[5] = (payload.port ushr 8 and 0xFF).toByte()
            this[6] = (payload.port and 0xFF).toByte()
            this[7] = (payload.modelQuality and 0xFF).toByte()
            this[8] = (payload.llmBackendOrdinal and 0xFF).toByte()
        }
    }

    /**
     * Reads a payload emitted by [encode]. Returns `null` for any malformed or
     * unknown-version input — never throws, since this runs on every scan
     * callback and bad data is a normal network condition (other apps using
     * adjacent UUID space, radio corruption, schema drift from a newer phone
     * build).
     */
    fun decode(bytes: ByteArray?): Payload? {
        if (bytes == null || bytes.size < PAYLOAD_SIZE_BYTES) return null
        if (bytes[0] != PAYLOAD_SCHEMA_VERSION) return null
        val ipv4 = runCatching {
            InetAddress.getByAddress(bytes.copyOfRange(1, 5)) as? Inet4Address
        }.getOrNull() ?: return null
        val port = ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
        val modelQuality = bytes[7].toInt() and 0xFF
        val llmBackendOrdinal = bytes[8].toInt() and 0xFF
        return Payload(
            ipv4 = ipv4,
            port = port,
            modelQuality = modelQuality,
            llmBackendOrdinal = llmBackendOrdinal,
        )
    }
}
