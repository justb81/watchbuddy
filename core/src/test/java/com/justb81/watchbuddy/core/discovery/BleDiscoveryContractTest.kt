package com.justb81.watchbuddy.core.discovery

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress

@DisplayName("BleDiscoveryContract")
class BleDiscoveryContractTest {

    private fun ipv4(address: String) =
        InetAddress.getByName(address) as Inet4Address

    @Test
    fun `round trip preserves all fields`() {
        val payload = BleDiscoveryContract.Payload(
            ipv4 = ipv4("192.168.42.17"),
            port = 8765,
            modelQuality = 90,
            llmBackendOrdinal = 2,
        )

        val decoded = BleDiscoveryContract.decode(BleDiscoveryContract.encode(payload))

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
            val decoded = BleDiscoveryContract.decode(BleDiscoveryContract.encode(payload))
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
    fun `decode rejects null`() {
        assertNull(BleDiscoveryContract.decode(null))
    }

    @Test
    fun `decode rejects short buffer`() {
        assertNull(
            BleDiscoveryContract.decode(ByteArray(BleDiscoveryContract.PAYLOAD_SIZE_BYTES - 1))
        )
    }

    @Test
    fun `decode rejects unknown schema version`() {
        val valid = BleDiscoveryContract.encode(
            BleDiscoveryContract.Payload(ipv4("192.168.1.1"), 8765, 70, 1)
        )
        valid[0] = 99
        assertNull(BleDiscoveryContract.decode(valid))
    }

    @Test
    fun `decode tolerates trailing bytes`() {
        val valid = BleDiscoveryContract.encode(
            BleDiscoveryContract.Payload(ipv4("192.168.1.1"), 8765, 70, 1)
        )
        val padded = valid + byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val decoded = BleDiscoveryContract.decode(padded)
        assertNotNull(decoded)
        assertEquals(8765, decoded!!.port)
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
}
