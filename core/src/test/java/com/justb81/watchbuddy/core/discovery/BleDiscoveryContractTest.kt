package com.justb81.watchbuddy.core.discovery

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress

@DisplayName("BleDiscoveryContract")
class BleDiscoveryContractTest {

    private fun ipv4(address: String) =
        InetAddress.getByName(address) as Inet4Address

    private fun decodeOk(bytes: ByteArray): BleDiscoveryContract.Payload {
        val result = BleDiscoveryContract.decode(bytes)
        assertTrue(result is BleDiscoveryContract.DecodeResult.Ok, "Expected Ok but got $result")
        return (result as BleDiscoveryContract.DecodeResult.Ok).payload
    }

    @Test
    fun `round trip preserves all fields`() {
        val payload = BleDiscoveryContract.Payload(
            ipv4 = ipv4("192.168.42.17"),
            port = 8765,
            modelQuality = 90,
            llmBackendOrdinal = 2,
        )

        val decoded = decodeOk(BleDiscoveryContract.encode(payload))

        assertEquals(payload, decoded)
    }

    @Test
    fun `encode produces exactly 9 bytes`() {
        val bytes = BleDiscoveryContract.encode(
            BleDiscoveryContract.Payload(ipv4("10.0.0.1"), 1, 0, 0)
        )
        assertEquals(BleDiscoveryContract.PAYLOAD_SIZE_BYTES, bytes.size)
    }

    @Test
    fun `encode writes schema version in first byte`() {
        val bytes = BleDiscoveryContract.encode(
            BleDiscoveryContract.Payload(ipv4("10.0.0.1"), 1, 0, 0)
        )
        assertEquals(BleDiscoveryContract.PAYLOAD_SCHEMA_VERSION, bytes[0])
    }

    @Test
    fun `edge values round trip`() {
        listOf(
            BleDiscoveryContract.Payload(ipv4("0.0.0.0"), 0, 0, 0),
            BleDiscoveryContract.Payload(ipv4("255.255.255.255"), 0xFFFF, 255, 255),
            BleDiscoveryContract.Payload(ipv4("127.0.0.1"), 65535, 150, 1),
        ).forEach { payload ->
            val decoded = decodeOk(BleDiscoveryContract.encode(payload))
            assertEquals(payload, decoded, "round trip failed for $payload")
        }
    }

    @Test
    fun `ipv4 bytes are written in network byte order`() {
        val bytes = BleDiscoveryContract.encode(
            BleDiscoveryContract.Payload(ipv4("192.168.1.2"), 0, 0, 0)
        )
        assertArrayEquals(
            byteArrayOf(192.toByte(), 168.toByte(), 1, 2),
            bytes.copyOfRange(1, 5)
        )
    }

    @Test
    fun `port is written big endian`() {
        val bytes = BleDiscoveryContract.encode(
            BleDiscoveryContract.Payload(ipv4("10.0.0.1"), 0x1234, 0, 0)
        )
        assertEquals(0x12.toByte(), bytes[5])
        assertEquals(0x34.toByte(), bytes[6])
    }

    @Test
    fun `decode null returns Truncated`() {
        assertEquals(BleDiscoveryContract.DecodeResult.Truncated, BleDiscoveryContract.decode(null))
    }

    @Test
    fun `decode short buffer returns Truncated`() {
        assertEquals(
            BleDiscoveryContract.DecodeResult.Truncated,
            BleDiscoveryContract.decode(ByteArray(BleDiscoveryContract.PAYLOAD_SIZE_BYTES - 1))
        )
    }

    @Test
    fun `decode empty buffer returns Truncated`() {
        assertEquals(
            BleDiscoveryContract.DecodeResult.Truncated,
            BleDiscoveryContract.decode(ByteArray(0))
        )
    }

    @Test
    fun `decode unknown schema version returns WrongVersion with correct fields`() {
        val valid = BleDiscoveryContract.encode(
            BleDiscoveryContract.Payload(ipv4("192.168.1.1"), 8765, 70, 1)
        )
        valid[0] = 99
        val result = BleDiscoveryContract.decode(valid)
        assertEquals(
            BleDiscoveryContract.DecodeResult.WrongVersion(
                found = 99,
                expected = BleDiscoveryContract.PAYLOAD_SCHEMA_VERSION,
            ),
            result,
        )
    }

    @Test
    fun `decode tolerates trailing bytes`() {
        val valid = BleDiscoveryContract.encode(
            BleDiscoveryContract.Payload(ipv4("192.168.1.1"), 8765, 70, 1)
        )
        val padded = valid + byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        assertEquals(8765, decodeOk(padded).port)
    }

    @Test
    fun `encode rejects out-of-range port`() {
        assertThrows(IllegalArgumentException::class.java) {
            BleDiscoveryContract.encode(
                BleDiscoveryContract.Payload(ipv4("10.0.0.1"), -1, 0, 0)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BleDiscoveryContract.encode(
                BleDiscoveryContract.Payload(ipv4("10.0.0.1"), 0x10000, 0, 0)
            )
        }
    }

    @Test
    fun `encode rejects out-of-range modelQuality`() {
        assertThrows(IllegalArgumentException::class.java) {
            BleDiscoveryContract.encode(
                BleDiscoveryContract.Payload(ipv4("10.0.0.1"), 8765, -1, 0)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BleDiscoveryContract.encode(
                BleDiscoveryContract.Payload(ipv4("10.0.0.1"), 8765, 256, 0)
            )
        }
    }

    @Test
    fun `WrongVersion carries found and expected version bytes`() {
        val valid = BleDiscoveryContract.encode(
            BleDiscoveryContract.Payload(ipv4("10.0.0.1"), 8765, 50, 0)
        )
        valid[0] = 42
        val result = BleDiscoveryContract.decode(valid)
        assertTrue(result is BleDiscoveryContract.DecodeResult.WrongVersion)
        result as BleDiscoveryContract.DecodeResult.WrongVersion
        assertEquals(42.toByte(), result.found)
        assertEquals(BleDiscoveryContract.PAYLOAD_SCHEMA_VERSION, result.expected)
    }

    @Test
    fun `advertisement fits within legacy 31-byte envelope`() {
        // BLE legacy advertising envelope budget — strict stacks (Android 16
        // / Nothing) reject anything larger with DATA_TOO_LARGE (#345).
        // Fields we currently emit:
        //   Flags AD        (stack-added)                                3 bytes
        //   Service Data 128-bit (0x21): 1 len + 1 type + 16 UUID + N    18 + N
        //
        // Any future growth in the service-data payload must keep this
        // assertion green, or scanners on strict stacks won't see us.
        val envelopeBudget = 31
        val flagsBytes = 3
        val serviceDataAdOverhead = 1 /* length */ + 1 /* AD type 0x21 */ + 16 /* UUID */
        val total = flagsBytes + serviceDataAdOverhead + BleDiscoveryContract.PAYLOAD_SIZE_BYTES
        assertEquals(30, total, "expected 30-byte emission; recalculate if fields changed")
        assertEquals(
            true,
            total <= envelopeBudget,
            "emission $total bytes exceeds 31-byte legacy envelope — BLE discovery will fail",
        )
    }
}
