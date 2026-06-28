package com.health.secondbrain.model

import androidx.compose.ui.graphics.Color
import com.health.secondbrain.ui.theme.Palette

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
    val accent: Color,
    val tint: Color,                  // hero background tint
    val attentionScore: Float,        // 0..1 — drives bubble radius
    val metrics: List<Metric>,
    val chart7Day: List<Float>,       // line chart values
    val activeZones: List<Float>,     // 7 bars M..S
    val sentenceWeek: String,
    val sentenceMonth: String,
    val sentenceNextStep: String,
    val statusGood: Boolean,
    val previewSummary: String,
    val chatChips: List<String>,
)

object OrganRegistry {

    /** Tint helper — accent * alpha over dark background for hero card bg. */
    private fun tint(c: Color): Color = c.copy(alpha = 0.18f)

    val all: List<OrganNode> = listOf(
        OrganNode(
            id = "heart",
            displayName = "Heart",
            systemLabel = "Cardiovascular system",
            accent = Palette.Heart,
            tint = tint(Palette.Heart),
            attentionScore = 1.0f,
            metrics = listOf(
                Metric("Resting HR", "64", "▲6", DeltaDirection.UpBad),
                Metric("HRV", "42", "▼", DeltaDirection.DownBad),
            ),
            chart7Day = listOf(58f, 60f, 59f, 62f, 61f, 64f, 64f),
            activeZones = listOf(20f, 35f, 28f, 45f, 30f, 18f, 22f),
            sentenceWeek = "You skipped recovery days — your heart is running hot.",
            sentenceMonth = "Slow upward drift in resting HR — trending the wrong way.",
            sentenceNextStep = "Take two easy days this week and aim for 7+ hrs sleep.",
            statusGood = false,
            previewSummary = "Resting HR up 6 bpm vs last month — recovery is lagging.",
            chatChips = listOf("Why is my HR up?", "Safe to run today?"),
        ),
        OrganNode(
            id = "liver",
            displayName = "Liver",
            systemLabel = "Metabolic load",
            accent = Palette.Liver,
            tint = tint(Palette.Liver),
            attentionScore = 0.72f,
            metrics = listOf(
                Metric("Alcohol units", "11", "▲", DeltaDirection.UpBad),
                Metric("Hydration", "61%", "–", DeltaDirection.Neutral),
            ),
            chart7Day = listOf(1f, 0f, 3f, 2f, 0f, 4f, 1f),
            activeZones = listOf(10f, 20f, 18f, 12f, 25f, 30f, 15f),
            sentenceWeek = "Two heavy nights pushed alcohol load above your usual baseline.",
            sentenceMonth = "Weekend intake is creeping up — hydration isn't keeping pace.",
            sentenceNextStep = "Bank three alcohol-free days and add 500ml water at breakfast.",
            statusGood = false,
            previewSummary = "Alcohol load above baseline; hydration flat.",
            chatChips = listOf("What's hurting my liver?", "Am I dehydrated?"),
        ),
        OrganNode(
            id = "gut",
            displayName = "Gut",
            systemLabel = "Digestive system",
            accent = Palette.Gut,
            tint = tint(Palette.Gut),
            attentionScore = 0.55f,
            metrics = listOf(
                Metric("Fiber", "22g", "▲", DeltaDirection.UpGood),
                Metric("Bloat", "low", "▼", DeltaDirection.DownGood),
            ),
            chart7Day = listOf(15f, 18f, 20f, 22f, 21f, 24f, 22f),
            activeZones = listOf(15f, 22f, 18f, 24f, 20f, 26f, 23f),
            sentenceWeek = "Fiber intake is steady and bloat scores are low — solid week.",
            sentenceMonth = "Trending healthier — consistent fiber for the last three weeks.",
            sentenceNextStep = "Keep variety high — add a fermented food twice this week.",
            statusGood = true,
            previewSummary = "Fiber steady, bloat low — gut is in good shape.",
            chatChips = listOf("How's my fiber?", "Best food right now?"),
        ),
        OrganNode(
            id = "sleep",
            displayName = "Sleep",
            systemLabel = "Rest & recovery",
            accent = Palette.Sleep,
            tint = tint(Palette.Sleep),
            attentionScore = 0.6f,
            metrics = listOf(
                Metric("Score", "78", "▼", DeltaDirection.DownBad),
                Metric("Deep", "1.1h", "▼", DeltaDirection.DownBad),
            ),
            chart7Day = listOf(82f, 80f, 76f, 70f, 75f, 78f, 78f),
            activeZones = listOf(40f, 36f, 28f, 22f, 30f, 34f, 32f),
            sentenceWeek = "Late screen time cost you about 40 minutes of deep sleep.",
            sentenceMonth = "Sleep efficiency has slipped 4% over the last month.",
            sentenceNextStep = "Lights down by 10:30pm tonight — no screens in bed.",
            statusGood = false,
            previewSummary = "Deep sleep down — efficiency slipping.",
            chatChips = listOf("Why am I tired?", "Best wind-down for me?"),
        ),
        OrganNode(
            id = "lungs",
            displayName = "Lungs",
            systemLabel = "Respiratory",
            accent = Palette.Lungs,
            tint = tint(Palette.Lungs),
            attentionScore = 0.5f,
            metrics = listOf(
                Metric("VO2 max", "46", "–", DeltaDirection.Neutral),
                Metric("Resp rate", "14", "–", DeltaDirection.Neutral),
            ),
            chart7Day = listOf(45f, 46f, 46f, 47f, 46f, 46f, 46f),
            activeZones = listOf(20f, 24f, 22f, 28f, 30f, 18f, 22f),
            sentenceWeek = "Aerobic capacity is holding steady — no red flags this week.",
            sentenceMonth = "VO2 max plateaued — time to introduce one harder session.",
            sentenceNextStep = "Add one 20-min zone-4 interval block this week.",
            statusGood = true,
            previewSummary = "Holding steady — aerobic base is healthy.",
            chatChips = listOf("How fit am I?", "Add an interval day?"),
        ),
        OrganNode(
            id = "kidney",
            displayName = "Kidney",
            systemLabel = "Renal",
            accent = Palette.Kidney,
            tint = tint(Palette.Kidney),
            attentionScore = 0.45f,
            metrics = listOf(
                Metric("Hydration", "61%", "–", DeltaDirection.Neutral),
                Metric("Creatinine", "0.9", "–", DeltaDirection.Neutral),
            ),
            chart7Day = listOf(60f, 62f, 58f, 61f, 63f, 60f, 61f),
            activeZones = listOf(18f, 22f, 20f, 24f, 18f, 22f, 20f),
            sentenceWeek = "Hydration consistent — kidney markers within normal range.",
            sentenceMonth = "No drift over 30 days — keep current intake patterns.",
            sentenceNextStep = "Continue your morning glass-of-water habit.",
            statusGood = true,
            previewSummary = "Markers in range — kidneys are happy.",
            chatChips = listOf("Drink more water?", "Any kidney red flags?"),
        ),
        OrganNode(
            id = "brain",
            displayName = "Brain",
            systemLabel = "Cognitive",
            accent = Palette.Brain,
            tint = tint(Palette.Brain),
            attentionScore = 0.4f,
            metrics = listOf(
                Metric("Focus", "72", "▲", DeltaDirection.UpGood),
                Metric("Stress HRV", "low", "▼", DeltaDirection.DownGood),
            ),
            chart7Day = listOf(68f, 70f, 72f, 71f, 73f, 72f, 72f),
            activeZones = listOf(22f, 25f, 28f, 30f, 26f, 24f, 27f),
            sentenceWeek = "Focus sessions are landing — stress signals are quieter.",
            sentenceMonth = "Cognitive load is well balanced — keep this rhythm.",
            sentenceNextStep = "Protect one deep-work block per day this week.",
            statusGood = true,
            previewSummary = "Focus up, stress down — cognitive state is good.",
            chatChips = listOf("Mental load high?", "How to focus better?"),
        ),
    )

    fun byId(id: String): OrganNode = all.first { it.id == id }
}
