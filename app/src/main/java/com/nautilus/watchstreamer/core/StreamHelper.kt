package com.nautilus.watchstreamer.core

import java.net.DatagramPacket
import java.net.InetAddress

object StreamHelper {

    fun formatLSLData(state: StreamState): String {
        return state.toPayloadString()
    }

    /**
     * Liveness packet sent once a second, even when the wrist is still, so the bridge can tell
     * a dropped device from a quiet one. Only 5 fields, so bridges that parse 6+ field IMU
     * packets ignore it.
     */
    fun formatHeartbeat(sent: Long, dropped: Long, batteryPercent: Int, timestampNs: Long): String {
        return "HB,$sent,$dropped,$batteryPercent,$timestampNs"
    }

    fun createDatagramPacket(payload: String, ipAddress: InetAddress, port: Int): DatagramPacket {
        val bytes = payload.toByteArray()
        return DatagramPacket(bytes, bytes.size, ipAddress, port)
    }
    
    fun convertIMUData(values: FloatArray): FloatArray {
        // Just an identity function here to represent conversion logic
        // If there were scaling or unit conversions, they'd go here.
        return values.clone()
    }
    
    fun convertPPGData(value: Float): Float {
        // Just an identity function here to represent conversion logic
        return value
    }
}
