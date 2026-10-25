package com.nautilus.watchstreamer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WireProtocolTest {

    @Test
    fun testImuBatch() {
        val samples = listOf(
            WireProtocol.Sample(1000L, floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)),
            WireProtocol.Sample(21000L, floatArrayOf(0f, 0f, 9.81f, 0f, 0f, 0f))
        )
        val expected = "W2|wear-ab12cd|L_wrist|7|IMU@50|1000,1.0,2.0,3.0,4.0,5.0,6.0;21000,0.0,0.0,9.81,0.0,0.0,0.0"
        assertEquals(expected, WireProtocol.formatPacket("wear-ab12cd", "L_wrist", 7L, WireProtocol.IMU, samples, 50))
    }

    @Test
    fun testNoRateOmitsSuffix() {
        val samples = listOf(WireProtocol.Sample(5L, floatArrayOf(1200f, 3f, 87f)))
        assertEquals(
            "W2|wear-ab12cd|R_ankle|0|HB|5,1200.0,3.0,87.0",
            WireProtocol.formatPacket("wear-ab12cd", "R_ankle", 0L, WireProtocol.HEARTBEAT, samples)
        )
    }

    @Test
    fun testSeqWrapsAt32Bits() {
        val samples = listOf(WireProtocol.Sample(0L, floatArrayOf(72f)))
        val packet = WireProtocol.formatPacket("d", "L_wrist", (1L shl 32) + 5, WireProtocol.HR, samples)
        assertEquals("5", packet.split("|")[3])
    }
}
