package com.dynedge

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Damped spring with unit mass, integrated with the exact closed-form solution
 * (under-, critically- and over-damped). Units: px and px/s. No allocations.
 */
class Spring(
    var value: Float = 0f,
    var stiffness: Float = 400f,
    var dampingRatio: Float = 0.8f
) {
    var velocity: Float = 0f
    var target: Float = value

    fun snapTo(v: Float) {
        value = v
        target = v
        velocity = 0f
    }

    fun isSettled(posEps: Float, velEps: Float): Boolean =
        abs(value - target) < posEps && abs(velocity) < velEps

    fun step(dtSec: Float) {
        if (dtSec <= 0f) return
        val t = dtSec.toDouble()
        val w = sqrt(stiffness.toDouble().coerceAtLeast(1e-6))
        val z = dampingRatio.toDouble().coerceAtLeast(0.0)
        val x0 = (value - target).toDouble()
        val v0 = velocity.toDouble()
        val x: Double
        val v: Double
        if (z < 0.999) {
            val a = z * w
            val wd = w * sqrt(1.0 - z * z)
            val b = (v0 + a * x0) / wd
            val e = exp(-a * t)
            val c = cos(wd * t)
            val s = sin(wd * t)
            x = e * (x0 * c + b * s)
            v = e * ((-a * x0 + b * wd) * c + (-a * b - x0 * wd) * s)
        } else if (z <= 1.001) {
            val b = v0 + w * x0
            val e = exp(-w * t)
            x = (x0 + b * t) * e
            v = (b - w * (x0 + b * t)) * e
        } else {
            val sq = sqrt(z * z - 1.0)
            val r1 = -w * (z - sq)
            val r2 = -w * (z + sq)
            val c2 = (v0 - r1 * x0) / (r2 - r1)
            val c1 = x0 - c2
            val e1 = exp(r1 * t)
            val e2 = exp(r2 * t)
            x = c1 * e1 + c2 * e2
            v = c1 * r1 * e1 + c2 * r2 * e2
        }
        value = (target + x).toFloat()
        velocity = v.toFloat()
    }
}
