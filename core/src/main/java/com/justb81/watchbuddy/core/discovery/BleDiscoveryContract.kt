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
 * ## Primary advertisement (service data attached to [SERVICE_UUID]): 9 bytes, big-endian.
 *
 *   | offset | bytes | field                                              |
 *   |--------|-------|----------------------------------------------------|
 *   | 0      | 1     | schema version (2 = current, bearer-auth capable)  |
 *   | 1..4   | 4     | IPv4 address (network byte order)                  |
 *   | 5..6   | 2     | TCP port (big-endian, unsigned)                    |
 *   | 7      | 1     | modelQuality (0..255 clamped; semantic range 0..150) |
 *   | 8      | 1     | llmBackend ordinal (0..255)                        |
 *
 * ## Scan response (service data attached to [TOKEN_SERVICE_UUID]): 13 bytes.
 *
 * Phones emit a BLE scan response carrying a 13-byte bearer token for HTTP
 * authentication. Token bytes are raw random material; callers encode to
 * Base64url for use in `Authorization: Bearer` headers.
 *
 *   | offset | bytes | field               |
 *   |--------|-------|---------------------|
 *   | 0..12  | 13    | bearer token bytes  |
 *
 * Budget: scan response has no mandatory FLAGS AD structure, so the full
 * 31 bytes are available. Service data header is 1 (len) + 1 (type 0x21) +
 * 16 (UUID) = 18 bytes. Remaining: 31 − 18 = **13 bytes** of token data.
 *
 * On the wire we emit **only** the Service Data 128-bit AD field — no
 * separate Complete-List-of-128-bit-UUIDs AD. Carrying the UUID in both
 * fields would push the envelope to 48 bytes (3 flags + 18 UUID list + 27
 * service data), which the legacy 31-byte advertising envelope rejects on
 * strict stacks (Android 16 / Nothing returns `DATA_TOO_LARGE`). With only
 * the service-data AD, total emission is 3 (flags) + 27 (1 len + 1 type +
 * 16 UUID + 9 payload) = **30 bytes**, inside the envelope on every tested
 * chipset (#345). Scanners must therefore filter on `setServiceData`, not
 * `setServiceUuid`.
 *
 * `version` (app versionName) is intentionally omitted from the BLE
 * payload; the TV fetches it via `/capability` once it has a routable
 * `(ip, port)` pair.
 *
 * Schema evolution: additive changes must bump [PAYLOAD_SCHEMA_VERSION];
 * unknown versions are rejected.
 */
object BleDiscoveryContract {

    /**
     * Custom 128-bit service UUID the phone advertises and the TV filters on.
     * Random UUIDv4 — not a SIG-assigned short ID; WatchBuddy-specific.
     */
    val SERVICE_UUID: UUID = UUID.fromString("5e4b4d3a-9f7c-4b7e-8e6b-6c0e5f27e4a0")

    /**
     * UUID for the scan-response service data carrying the 13-byte bearer token.
     * Separate from [SERVICE_UUID] to avoid collision in Android's merged ScanRecord map.
     */
    val TOKEN_SERVICE_UUID: UUID = UUID.fromString("7a2c1f8b-3e5d-4c9a-b0e7-8d4f2a6c0b3e")

    /** Current schema — phone emits scan response with [TOKEN_SERVICE_UUID] bearer token. */
    const val PAYLOAD_SCHEMA_VERSION: Byte = 2

    /** Size of the primary advertisement service data payload (both v1 and v2). */
    const val PAYLOAD_SIZE_BYTES: Int = 9

    /** Size of the bearer-token scan-response payload (schema v2 only). */
    const val TOKEN_PAYLOAD_SIZE_BYTES: Int = 13

    data class Payload(
        val ipv4: Inet4Address,
        val port: Int,
        val modelQuality: Int,
        val llmBackendOrdinal: Int,
    )

    /** Typed result returned by [decode] so callers can distinguish failure modes. */
    sealed class DecodeResult {
        /** Successfully decoded payload. */
        data class Ok(val payload: Payload) : DecodeResult()

        /** Schema version is not 2 — unknown schema or garbled advert. */
        data class WrongVersion(val found: Byte, val expected: Byte) : DecodeResult()

        /** Payload is null or shorter than [PAYLOAD_SIZE_BYTES]. */
        data object Truncated : DecodeResult()

        /** IPv4 field could not be parsed into an [Inet4Address]. */
        data object MalformedIpv4 : DecodeResult()
    }

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
     * Reads a payload emitted by [encode]. Returns a [DecodeResult] that distinguishes
     * the failure mode so callers can log unexpected cases separately from expected
     * schema-version mismatches (other apps using adjacent UUID space, radio corruption).
     *
     * Only accepts [PAYLOAD_SCHEMA_VERSION] (v2). Never throws — this runs on every
     * scan callback and bad data is a normal network condition.
     */
    fun decode(bytes: ByteArray?): DecodeResult {
        if (bytes == null || bytes.size < PAYLOAD_SIZE_BYTES) return DecodeResult.Truncated
        val version = bytes[0]
        if (version != PAYLOAD_SCHEMA_VERSION) {
            return DecodeResult.WrongVersion(found = version, expected = PAYLOAD_SCHEMA_VERSION)
        }
        val ipv4 = runCatching {
            InetAddress.getByAddress(bytes.copyOfRange(1, 5)) as? Inet4Address
        }.getOrNull() ?: return DecodeResult.MalformedIpv4
        val port = ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
        val modelQuality = bytes[7].toInt() and 0xFF
        val llmBackendOrdinal = bytes[8].toInt() and 0xFF
        return DecodeResult.Ok(
            payload = Payload(
                ipv4 = ipv4,
                port = port,
                modelQuality = modelQuality,
                llmBackendOrdinal = llmBackendOrdinal,
            ),
        )
    }

    /**
     * Returns [tokenBytes] unchanged after validating length equals [TOKEN_PAYLOAD_SIZE_BYTES].
     *
     * @throws IllegalArgumentException if length != [TOKEN_PAYLOAD_SIZE_BYTES].
     */
    fun encodeTokenPayload(tokenBytes: ByteArray): ByteArray {
        require(tokenBytes.size == TOKEN_PAYLOAD_SIZE_BYTES) {
            "token must be exactly $TOKEN_PAYLOAD_SIZE_BYTES bytes; got ${tokenBytes.size}"
        }
        return tokenBytes.copyOf()
    }

    /**
     * Validates and returns the bearer-token bytes from a [TOKEN_SERVICE_UUID] scan-response
     * payload, or null if [bytes] is null or has the wrong length.
     * Never throws.
     */
    fun decodeTokenPayload(bytes: ByteArray?): ByteArray? {
        if (bytes == null || bytes.size < TOKEN_PAYLOAD_SIZE_BYTES) return null
        return bytes.copyOf(TOKEN_PAYLOAD_SIZE_BYTES)
    }
}
