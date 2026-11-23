package dev.travelto

import kotlin.math.exp
import kotlin.math.roundToLong

internal val MOTION_SPEED = 25..250

internal enum class MotionCurve(val key: String) {
    SMOOTH("smooth"), LINEAR("linear"), BOUNCE("bounce"), EASE_OUT("ease_out");

    fun ease(progress: Float): Float {
        val time = progress.coerceIn(0f, 1f)
        if (time == 0f || time == 1f) return time
        return when (this) {
            SMOOTH -> time * time * (3f - 2f * time)
            LINEAR -> time
            EASE_OUT -> 1f - (1f - time) * (1f - time) * (1f - time)
            BOUNCE -> {
                val bounce = when {
                    time < 4f / 11f -> 7.5625f * time * time
                    time < 8f / 11f -> (time - 6f / 11f).let { .75f + 7.5625f * it * it }
                    time < 10f / 11f -> (time - 9f / 11f).let { .9375f + 7.5625f * it * it }
                    else -> (time - 21f / 22f).let { .984375f + 7.5625f * it * it }
                }
                (bounce * (.92f + .08f * time)).coerceIn(0f, 1f)
            }
        }
    }
}

internal enum class MotionScope {
    UI, ARRAY_LIST, WATERMARK, TARGET, EFFECTS;

    fun speed(state: ClientState): Float {
        val local = when (this) {
            UI -> 100
            ARRAY_LIST -> state.hud.arrayList.motionSpeed
            WATERMARK -> state.hud.watermarkPart().motionSpeed
            TARGET -> state.hud.target.motionSpeed
            EFFECTS -> state.hud.effects.motionSpeed
        }
        return state.motion.speed * local / 10_000f
    }
}

internal data class MotionOptions(var curve: MotionCurve = MotionCurve.SMOOTH, var speed: Int = 100)

internal object MotionSettings {
    fun speed(scope: MotionScope = MotionScope.UI) = scope.speed(Travelto.state)
    fun scaled(elapsedNanos: Long, scope: MotionScope = MotionScope.UI) = elapsedNanos * speed(scope).toDouble()
    fun duration(millis: Long, scope: MotionScope = MotionScope.UI) = (millis / speed(scope).toDouble()).roundToLong().coerceAtLeast(1L)
    fun raw(elapsedNanos: Long, millis: Int, scope: MotionScope = MotionScope.UI) =
        if (Travelto.state.reducedMotion) 1f else (scaled(elapsedNanos, scope) / (millis * 1_000_000.0)).toFloat()
    fun progress(elapsedNanos: Long, millis: Int, scope: MotionScope = MotionScope.UI) =
        Travelto.state.motion.curve.ease(raw(elapsedNanos, millis, scope))
    fun phase(now: Long, millis: Int, scope: MotionScope = MotionScope.UI) =
        (scaled(now, scope) / (millis * 1_000_000.0)).mod(1.0).toFloat()
    fun follow(from: Float, to: Float, rate: Float, elapsed: Float, scope: MotionScope): Float =
        if (Travelto.state.reducedMotion || kotlin.math.abs(to - from) < .0001f) to
        else from + (to - from) * (1f - exp(-rate * elapsed * speed(scope)))
}

internal class Motion(initial: Float, private val millis: Int = 210, private val scope: MotionScope = MotionScope.UI) {
    private var start = initial
    private var target = initial
    private var phase = 1f
    private var sampled = 0L
    private var curve = MotionCurve.SMOOTH

    fun reset(value: Float) { start = value; target = value; phase = 1f }

    fun value(now: Long): Float {
        if (Travelto.state.reducedMotion) reset(target)
        if (phase >= 1f) return target
        val elapsed = (now - sampled).coerceAtLeast(0L)
        if (elapsed > 0L) {
            sampled = now
            phase = (phase + elapsed * MotionSettings.speed(scope) / (millis * 1_000_000f)).coerceAtMost(1f)
        }
        return if (phase >= 1f) target else start + (target - start) * curve.ease(phase)
    }

    fun finished(now: Long): Boolean {
        value(now)
        return phase >= 1f
    }

    fun toward(next: Float, now: Long): Float {
        if (Travelto.state.reducedMotion) { reset(next); return next }
        val current = value(now)
        if (next != target) {
            start = current
            target = next
            phase = if (current == next) 1f else 0f
            sampled = now
            curve = Travelto.state.motion.curve
        }
        return current
    }
}
