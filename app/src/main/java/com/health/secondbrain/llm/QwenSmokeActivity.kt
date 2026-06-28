package com.health.secondbrain.llm

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class QwenSmokeActivity : Activity() {
    private val activityJob = SupervisorJob()
    private val activityScope = CoroutineScope(activityJob + Dispatchers.Main.immediate)

    private lateinit var runButton: Button
    private lateinit var outputView: TextView
    private lateinit var generator: QwenLocalGenerator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Qwen Smoke"
        QnnEnvironment.bootstrap(this)
        generator = QwenLocalGenerator(context = applicationContext)

        runButton = Button(this).apply {
            text = "Run Qwen smoke"
            setOnClickListener { runSmoke() }
        }
        outputView = TextView(this).apply {
            text = statusText("Ready. Tap the button to load and generate.")
            setTextIsSelectable(true)
            setTextColor(Color.rgb(28, 28, 28))
            setBackgroundColor(Color.rgb(248, 248, 248))
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val scrollView = ScrollView(this).apply {
            addView(
                outputView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
            addView(
                runButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                scrollView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        setContentView(root)
    }

    override fun onDestroy() {
        activityJob.cancel()
        super.onDestroy()
    }

    private fun runSmoke() {
        runButton.isEnabled = false
        outputView.text = statusText("Loading Qwen/QNN model and generating...")
        val startedAtMs = SystemClock.elapsedRealtime()

        activityScope.launch {
            runCatching {
                generator.generate(SMOKE_PROMPT, sequenceLength = 128, visibleCharLimit = 80)
            }.onSuccess { text ->
                val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                outputView.text = buildString {
                    appendLine(statusText("SUCCESS in ${elapsedMs}ms."))
                    appendLine()
                    appendLine("Generated text:")
                    appendLine(text)
                }
            }.onFailure { error ->
                val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                outputView.text = buildString {
                    appendLine(statusText("FAILURE after ${elapsedMs}ms."))
                    appendLine()
                    appendLine("Full error:")
                    appendLine(error.toString())
                    appendLine(Log.getStackTraceString(error))
                }
            }
            runButton.isEnabled = true
        }
    }

    private fun statusText(state: String): String {
        val model = File(QnnEnvironment.MODEL_PATH)
        val tokenizer = File(QnnEnvironment.TOKENIZER_PATH)
        val diagnostics = QnnEnvironment.diagnostics(this)
        return buildString {
            appendLine("Qwen/QNN smoke test")
            appendLine("State: $state")
            appendLine("Model: ${QnnEnvironment.MODEL_PATH}")
            appendLine("Model readable: ${model.canRead()} (${model.length()} bytes)")
            appendLine("Tokenizer: ${QnnEnvironment.TOKENIZER_PATH}")
            appendLine("Tokenizer readable: ${tokenizer.canRead()} (${tokenizer.length()} bytes)")
            appendLine("Native libs: ${applicationInfo.nativeLibraryDir}")
            appendLine("ADSP_LIBRARY_PATH: ${diagnostics.adspLibraryPath.orEmpty()}")
            appendLine("QNN skel readable: ${diagnostics.skelReadable}")
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SMOKE_PROMPT =
            "This is an Android software runtime check, not a smoke chamber. Reply exactly: HALO Qwen runtime passed."
    }
}
