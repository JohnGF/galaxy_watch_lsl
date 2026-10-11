package com.nautilus.watchstreamer.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class StreamHelperTest {

    @Test
    fun testFormatLSLData() {
        val state = StreamState(
            accelX = 1.0f, accelY = 2.0f, accelZ = 3.0f,
            gyroX = 4.0f, gyroY = 5.0f, gyroZ = 6.0f,
            heartRate = 75.0f,
            touchX = 100.0f, touchY = 200.0f, isTouched = 1
        )
        val expected = "1.0,2.0,3.0,4.0,5.0,6.0,75.0,100.0,200.0,1"
        assertEquals(expected, StreamHelper.formatLSLData(state))
    }
    
    @Test
    fun testFormatLSLData_Default() {
        val state = StreamState()
        val expected = "0.0,0.0,0.0,0.0,0.0,0.0,0.0,-1.0,-1.0,0"
        assertEquals(expected, StreamHelper.formatLSLData(state))
    }

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
