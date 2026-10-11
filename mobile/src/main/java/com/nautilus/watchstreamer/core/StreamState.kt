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
    var isTouched: Int = 0
) {
    fun toPayloadString(): String {
        return "$accelX,$accelY,$accelZ,$gyroX,$gyroY,$gyroZ,$heartRate,$touchX,$touchY,$isTouched"
    }
}
