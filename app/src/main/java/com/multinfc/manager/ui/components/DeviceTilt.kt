package com.multinfc.manager.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Smoothed, screen-relative device tilt used to give the detail card a subtle
 * parallax. Values are degrees relative to the pose the phone had when the
 * sensor was attached, so holding the phone at any comfortable angle starts
 * from a level card instead of snapping to an absolute orientation.
 */
@Stable
class DeviceTilt internal constructor() {

    /** Card rotation around the horizontal screen axis, in degrees. */
    val rotateX = mutableFloatStateOf(0f)

    /** Card rotation around the vertical screen axis, in degrees. */
    val rotateY = mutableFloatStateOf(0f)

    /** Normalised x of the moving specular highlight, in [0, 1]. */
    val lightX = mutableFloatStateOf(0.5f)

    /** Normalised y of the moving specular highlight, in [0, 1]. */
    val lightY = mutableFloatStateOf(0.36f)

    /** True when a usable rotation/accelerometer sensor was found. */
    var available by mutableStateOf(false)
        internal set

    internal fun reset() {
        rotateX.floatValue = 0f
        rotateY.floatValue = 0f
        lightX.floatValue = 0.5f
        lightY.floatValue = 0.36f
    }
}

/** Whether this device exposes enough motion data for [rememberDeviceTilt]. */
fun hasTiltSensor(context: Context): Boolean {
    val manager = context.getSystemService(SensorManager::class.java) ?: return false
    return manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null ||
        manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
        manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
}

/**
 * Registers a game-rotation-vector sensor while [enabled] is true. That sensor
 * fuses the gyroscope with the accelerometer, which is exactly the relative
 * tilt we want; a plain rotation vector (adds the magnetometer) and finally the
 * accelerometer are used as fallbacks.
 */
@Composable
fun rememberDeviceTilt(
    enabled: Boolean,
    maxDegrees: Float = 9f,
): DeviceTilt {
    val context = LocalContext.current
    val tilt = remember { DeviceTilt() }

    DisposableEffect(enabled, context) {
        if (!enabled) {
            tilt.available = false
            tilt.reset()
            return@DisposableEffect onDispose { }
        }

        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.let {
            it.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: it.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        if (manager == null || sensor == null) {
            tilt.available = false
            tilt.reset()
            return@DisposableEffect onDispose { }
        }

        tilt.available = true
        val screenRotation = screenRotation(context)
        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        var basePitch: Float? = null
        var baseRoll: Float? = null
        var smoothX = 0f
        var smoothY = 0f
        var gravityX = 0f
        var gravityY = 0f
        var gravityZ = 0f

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val pitchDeg: Float
                val rollDeg: Float

                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    // Low-pass the gravity vector, then derive tilt angles.
                    val alpha = GRAVITY_LOW_PASS
                    gravityX += (event.values[0] - gravityX) * alpha
                    gravityY += (event.values[1] - gravityY) * alpha
                    gravityZ += (event.values[2] - gravityZ) * alpha
                    if (sqrt(gravityX * gravityX + gravityY * gravityY) < 0.5f) return
                    rollDeg = Math.toDegrees(
                        atan2(gravityX.toDouble(), gravityY.toDouble())
                    ).toFloat()
                    pitchDeg = Math.toDegrees(
                        atan2(
                            -gravityZ.toDouble(),
                            sqrt((gravityX * gravityX + gravityY * gravityY).toDouble()),
                        )
                    ).toFloat()
                } else {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    remapForScreen(rotationMatrix, remappedMatrix, screenRotation)
                    SensorManager.getOrientation(remappedMatrix, orientation)
                    pitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
                    rollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()
                }

                // The first sample becomes the neutral pose for this screen.
                val baseP = basePitch
                val baseR = baseRoll
                if (baseP == null || baseR == null) {
                    basePitch = pitchDeg
                    baseRoll = rollDeg
                    return
                }

                val deltaPitch = normalizeDegrees(pitchDeg - baseP)
                val deltaRoll = normalizeDegrees(rollDeg - baseR)
                // Leaning the phone top-to-bottom maps to rotationX, leaning it
                // left-to-right maps to rotationY. The sensor is more sensitive
                // than the visual effect should be, hence the 0.65 gain.
                val targetX = (-deltaPitch * TILT_GAIN).coerceIn(-maxDegrees, maxDegrees)
                val targetY = (deltaRoll * TILT_GAIN).coerceIn(-maxDegrees, maxDegrees)

                smoothX += (targetX - smoothX) * SMOOTHING
                smoothY += (targetY - smoothY) * SMOOTHING

                tilt.rotateX.floatValue = smoothX
                tilt.rotateY.floatValue = smoothY
                tilt.lightX.floatValue =
                    (0.5f + (smoothY / maxDegrees) * 0.34f).coerceIn(0.12f, 0.88f)
                tilt.lightY.floatValue =
                    (0.36f - (smoothX / maxDegrees) * 0.26f).coerceIn(0.10f, 0.78f)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            manager.unregisterListener(listener)
            tilt.available = false
            tilt.reset()
        }
    }

    return tilt
}

private fun remapForScreen(
    input: FloatArray,
    output: FloatArray,
    screenRotation: Int,
) {
    val axisX: Int
    val axisY: Int
    when (screenRotation) {
        Surface.ROTATION_90 -> {
            axisX = SensorManager.AXIS_Y
            axisY = SensorManager.AXIS_MINUS_X
        }

        Surface.ROTATION_180 -> {
            axisX = SensorManager.AXIS_MINUS_X
            axisY = SensorManager.AXIS_MINUS_Y
        }

        Surface.ROTATION_270 -> {
            axisX = SensorManager.AXIS_MINUS_Y
            axisY = SensorManager.AXIS_X
        }

        else -> {
            axisX = SensorManager.AXIS_X
            axisY = SensorManager.AXIS_Y
        }
    }
    SensorManager.remapCoordinateSystem(input, axisX, axisY, output)
}

private fun screenRotation(context: Context): Int {
    val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display.rotation
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.rotation
    }
    return rotation ?: Surface.ROTATION_0
}

private fun normalizeDegrees(value: Float): Float {
    var normalized = value % 360f
    if (normalized > 180f) normalized -= 360f
    if (normalized < -180f) normalized += 360f
    return normalized
}

private const val TILT_GAIN = 0.65f
private const val SMOOTHING = 0.18f
private const val GRAVITY_LOW_PASS = 0.15f
