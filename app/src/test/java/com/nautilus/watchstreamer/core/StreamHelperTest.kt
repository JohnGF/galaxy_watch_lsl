package com.nautilus.watchstreamer.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class StreamHelperTest {

    @Test
    fun testCreateDatagramPacket() {
        val payload = "1.0,2.0,3.0"
        val ipAddress = InetAddress.getByName("127.0.0.1")
        val port = 5005
        val packet = StreamHelper.createDatagramPacket(payload, ipAddress, port)

        assertEquals(payload, String(packet.data, 0, packet.length))
        assertEquals(ipAddress, packet.address)
        assertEquals(port, packet.port)
    }

    @Test
    fun testConvertIMUData() {
        val input = floatArrayOf(1.0f, -2.5f, 3.14f)
        val output = StreamHelper.convertIMUData(input)
        assertArrayEquals(input, output, 0.0001f)
    }

    @Test
    fun testConvertPPGData() {
        val input = 80.5f
        val output = StreamHelper.convertPPGData(input)
        assertEquals(input, output, 0.0001f)
    }
}
