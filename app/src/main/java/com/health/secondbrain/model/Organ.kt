package com.health.secondbrain.model

import androidx.compose.ui.graphics.Color

enum class DeltaDirection { UpBad, UpGood, DownGood, DownBad, Neutral }

data class Metric(
    val label: String,
    val value: String,
    val deltaText: String,
    val direction: DeltaDirection,
)

data class OrganNode(
    val id: String,
    val displayName: String,
    val systemLabel: String,
    val iconAsset: String,
    val componentType: String,
    val accent: Color,
    val tint: Color,
    val attentionScore: Float,
    val metrics: List<Metric>,
    val chart7Day: List<Float>,
    val activeZones: List<Float>,
    val sentenceWeek: String,
    val sentenceMonth: String,
    val sentenceNextStep: String,
    val statusGood: Boolean,
    val previewSummary: String,
    val chatChips: List<String>,
)
