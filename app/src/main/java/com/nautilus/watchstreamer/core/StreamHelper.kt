package com.nautilus.watchstreamer.core

import java.net.DatagramPacket
import java.net.InetAddress

object StreamHelper {

    fun formatLSLData(state: StreamState): String {
        return state.toPayloadString()
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
