package com.health.secondbrain.features

object HealthInterpolator {

    fun sevenDay(values: List<Double>, fallback: Double): List<Float> {
        if (values.isEmpty()) return List(7) { fallback.toFloat() }
        val tail = values.takeLast(7)
        if (tail.size == 7) return tail.map { it.toFloat() }

        val padded = MutableList(7 - tail.size) { tail.first() }
        padded.addAll(tail)
        return padded.map { it.toFloat() }
    }

    fun clamp01(value: Double): Double = value.coerceIn(0.0, 1.0)
}
