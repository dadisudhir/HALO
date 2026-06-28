package com.health.secondbrain.llm

import android.os.Bundle
import android.util.Base64
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.health.secondbrain.data.HealthAgentContext
import com.health.secondbrain.data.HealthBackendMode
import com.health.secondbrain.model.DeltaDirection
import com.health.secondbrain.model.Metric
import com.health.secondbrain.model.OrganNode
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import kotlin.math.roundToLong

class AgentPlannerEvalActivity : ComponentActivity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(28, 40, 28, 28)
            text = "HALO planner eval starting..."
        }
        setContentView(statusView)

        val split = intent.getStringExtra("split") ?: "judge"
        val limit = intent.getIntExtra("limit", 0).takeIf { it > 0 }
        val dataset = intent.getStringExtra("dataset") ?: "evals/agent_tool_routing_dataset.jsonl"
        val mode = intent.getStringExtra("mode") ?: "planner"

        lifecycleScope.launch {
            runCatching {
                if (mode == "smoke_chat") {
                    runSmokeChat(
                        prompt = intent.getStringExtra("prompt_b64")
                            ?.let { String(Base64.decode(it, Base64.NO_WRAP)) }
                            ?: intent.getStringExtra("prompt")
                            ?: "how healthy is my heart right now"
                    )
                } else {
                    runEval(split = split, limit = limit, dataset = dataset)
                }
            }.onFailure { error ->
                statusView.text = "HALO planner eval failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private suspend fun runSmokeChat(prompt: String) = withContext(Dispatchers.IO) {
        val service = OnDeviceLlmService(applicationContext)
        val context = evalContext()
        val startNs = System.nanoTime()
        val response = service.generateChat(
            context = context,
            userMessage = prompt,
        )
        val latencyMs = ((System.nanoTime() - startNs) / 1_000_000.0).roundToLong()
        val result = JSONObject()
            .put("prompt", prompt)
            .put("latency_ms", latencyMs)
            .put("text", response.text)
            .put("status_line", response.statusLine)
            .put("used_tool", response.usedTool)
            .put(
                "visual",
                response.visual?.let {
                    JSONObject()
                        .put("domain_id", it.domainId)
                        .put("title", it.title)
                        .put("caption", it.caption)
                }
            )
            .put(
                "decision",
                JSONObject()
                    .put("action", response.decision.action)
                    .put("route", response.decision.route)
                    .put("needs_health_context", response.decision.needsHealthContext)
                    .put("tool_name", response.decision.toolName)
                    .put("query", response.decision.query)
                    .put("answer_source", response.decision.answerSource)
            )
        val output = File(getExternalFilesDir(null), "halo_chat_smoke_result.json")
        output.writeText(result.toString(2))

        withContext(Dispatchers.Main) {
            statusView.text = "HALO chat smoke complete\nlatency=${latencyMs}ms\n${response.statusLine}\n${response.text}\n$output"
        }
    }

    private suspend fun runEval(
        split: String,
        limit: Int?,
        dataset: String,
    ) = withContext(Dispatchers.IO) {
        val rows = loadDataset(dataset)
            .filter { split == "all" || it.split == split }
            .let { if (limit != null) it.take(limit) else it }
        val generator = QwenLocalGenerator(applicationContext)
        val context = evalContext()
        val outputStem = dataset.substringAfterLast('/').removeSuffix(".jsonl")
        val output = File(getExternalFilesDir(null), "halo_agent_eval_${outputStem}_${split}_predictions.jsonl")
        val metrics = File(getExternalFilesDir(null), "halo_agent_eval_${outputStem}_${split}_metrics.json")
        var totalLatencyMs = 0L
        val latencies = mutableListOf<Long>()

        output.bufferedWriter().use { writer ->
            rows.forEachIndexed { index, row ->
                val prompt = HealthAgentPromptBuilder.buildPlanPrompt(
                    context = context,
                    userMessage = row.prompt,
                )
                val startNs = System.nanoTime()
                val raw = generator.generate(
                    prompt = prompt,
                    sequenceLength = 192,
                    visibleCharLimit = 24,
                    onToken = null,
                )
                val latencyMs = ((System.nanoTime() - startNs) / 1_000_000.0).roundToLong()
                totalLatencyMs += latencyMs
                latencies += latencyMs
                val plan = OnDeviceLlmService.canonicalPlanForTesting(raw, context, row.prompt)
                val record = AgentPlanner.toPredictionJson(plan)
                    .put("id", row.id)
                    .put("latency_ms", latencyMs)
                    .put("raw", raw.take(900))
                writer.write(record.toString())
                writer.newLine()
                writer.flush()

                withContext(Dispatchers.Main) {
                    statusView.text = "HALO planner eval ${index + 1}/${rows.size}\nlast=${row.id}\nlatency=${latencyMs}ms\noutput=${output.absolutePath}"
                }
            }
        }

        val metricJson = JSONObject()
            .put("dataset", dataset)
            .put("split", split)
            .put("count", rows.size)
            .put("warm_module", false)
            .put("avg_latency_ms", if (rows.isEmpty()) 0.0 else totalLatencyMs.toDouble() / rows.size)
            .put("median_latency_ms", percentile(latencies, 0.50))
            .put("p90_latency_ms", percentile(latencies, 0.90))
            .put("min_latency_ms", latencies.minOrNull() ?: 0)
            .put("max_latency_ms", latencies.maxOrNull() ?: 0)
            .put("predictions_path", output.absolutePath)
        metrics.writeText(metricJson.toString(2))

        withContext(Dispatchers.Main) {
            statusView.text = "HALO planner eval complete\n${rows.size} prompts\navg latency=${metricJson.optDouble("avg_latency_ms")}ms\n$output\n$metrics"
        }
    }

    private fun percentile(values: List<Long>, fraction: Double): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * fraction).roundToLong().toInt()
            .coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun loadDataset(dataset: String): List<EvalRow> =
        assets.open(dataset).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }
                .map { JSONObject(it) }
                .map {
                    EvalRow(
                        id = it.getString("id"),
                        split = it.getString("split"),
                        prompt = it.getString("prompt"),
                    )
                }
                .toList()
        }

    private fun evalContext(): HealthAgentContext =
        HealthAgentContext(
            generatedAt = Instant.now(),
            mode = HealthBackendMode.Fake,
            organ = OrganNode(
                id = "heart",
                displayName = "Heart",
                systemLabel = "Cardiovascular system",
                iconAsset = "organs/heart.svg",
                componentType = "organ",
                accent = Color(0xFFFF5D79),
                tint = Color(0x33FF5D79),
                attentionScore = 0.55f,
                metrics = listOf(
                    Metric("Resting HR", "65", "+4bpm", DeltaDirection.UpBad),
                    Metric("HRV", "44", "-9", DeltaDirection.DownBad),
                    Metric("Sleep", "6h34m", "", DeltaDirection.Neutral),
                ),
                chart7Day = listOf(0.2f, 0.75f, 0.7f, 0.66f, 0.62f, 0.61f, 0.63f),
                activeZones = listOf(0.45f, 0.9f, 0.52f, 0.48f, 0.47f, 0.5f, 0.55f),
                sentenceWeek = "Cardiac risk is 55% with resting HR +4 bpm from baseline.",
                sentenceMonth = "The backend stores baseline/current windows and writes every risk run into SQLite.",
                sentenceNextStep = "Keep today easy and prioritize sleep until resting HR returns toward baseline.",
                statusGood = false,
                previewSummary = "Resting HR is above baseline and HRV is lower.",
                chatChips = emptyList(),
            ),
            recordedDailySummaries = emptyList(),
            demoDailySummaryCount = 30,
            recordedRisk = null,
            clinicalObservations = emptyList(),
        )

    private data class EvalRow(
        val id: String,
        val split: String,
        val prompt: String,
    )
}
