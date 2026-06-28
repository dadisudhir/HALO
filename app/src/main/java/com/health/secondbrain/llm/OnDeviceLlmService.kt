package com.health.secondbrain.llm

import com.health.secondbrain.model.OrganNode
import kotlinx.coroutines.delay

/**
 * On-device LLM service stub. Hackathon plan: replace [generate] with an
 * ExecuTorch-backed Llama 3.2 1B (or Phi-3-mini) call running on the
 * Snapdragon 8 Elite NPU via the QNN backend (.pte module shipped in /assets).
 *
 * For now we return canned, organ-specific strings so the UI is fully wired
 * and the swap is a single-method change.
 */
class OnDeviceLlmService {

    enum class Prompt { Week, Month, NextStep, Chat }

    suspend fun generate(organ: OrganNode, prompt: Prompt, userMessage: String? = null): String {
        // Simulate NPU inference latency so the typing indicator gets a chance to render.
        delay(450)
        return when (prompt) {
            Prompt.Week     -> organ.sentenceWeek
            Prompt.Month    -> organ.sentenceMonth
            Prompt.NextStep -> organ.sentenceNextStep
            Prompt.Chat     -> chatReply(organ, userMessage.orEmpty())
        }
    }

    private fun chatReply(organ: OrganNode, message: String): String {
        val m = message.lowercase()
        return when {
            "why" in m || "explain" in m ->
                "Looking at your last 7 days for ${organ.displayName.lowercase()} — " +
                "${organ.previewSummary.replaceFirstChar { it.lowercase() }} " +
                "The clearest signal is in your ${organ.metrics.first().label.lowercase()}."
            "safe" in m || "today" in m ->
                "Based on today's signals you can train, but keep it conversational. " +
                "Watch ${organ.metrics.first().label.lowercase()} during the session."
            "next" in m || "should" in m || "do" in m ->
                organ.sentenceNextStep
            else ->
                "Here's what I'm seeing for your ${organ.displayName.lowercase()}: " +
                "${organ.sentenceWeek} ${organ.sentenceNextStep}"
        }
    }
}
