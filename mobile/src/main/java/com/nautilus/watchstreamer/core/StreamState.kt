package com.nautilus.watchstreamer.core

data class StreamState(
    var accelX: Float = 0f,
    var accelY: Float = 0f,
    var accelZ: Float = 0f,
    var gyroX: Float = 0f,
    var gyroY: Float = 0f,
    var gyroZ: Float = 0f,
    var heartRate: Float = 0f,
    var touchX: Float = -1f,
    var touchY: Float = -1f,
    var isTouched: Int = 0,
    // elapsedRealtimeNanos clock (same base as SensorEvent.timestamp); the PC bridge
    // maps it onto LSL local_clock so samples carry acquisition time, not arrival time.
    var timestampNs: Long = 0L
) {
    fun toPayloadString(): String {
        return "$accelX,$accelY,$accelZ,$gyroX,$gyroY,$gyroZ,$heartRate,$touchX,$touchY,$isTouched,$timestampNs"
    }
}
