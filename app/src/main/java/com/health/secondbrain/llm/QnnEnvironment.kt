package com.health.secondbrain.llm

import android.content.Context
import android.system.Os
import android.util.Log
import com.facebook.soloader.SoLoader
import java.io.File

data class QnnDiagnostics(
    val nativeLibraryDir: String,
    val adspLibraryPath: String?,
    val modelPath: String,
    val tokenizerPath: String,
    val skelReadable: Boolean,
    val modelReadable: Boolean,
    val tokenizerReadable: Boolean,
) {
    fun asText(): String =
        """
        nativeLibraryDir=$nativeLibraryDir
        ADSP_LIBRARY_PATH=${adspLibraryPath.orEmpty()}
        skelReadable=$skelReadable
        modelReadable=$modelReadable
        tokenizerReadable=$tokenizerReadable
        modelPath=$modelPath
        tokenizerPath=$tokenizerPath
        """.trimIndent()
}

object QnnEnvironment {
    const val MODEL_TYPE_QNN_LLAMA = 4
    const val MODEL_PATH = "/data/local/tmp/minibench/qwen3-1_7b/hybrid_llama_qnn.pte"
    const val TOKENIZER_PATH = "/data/local/tmp/minibench/qwen3-1_7b/tokenizer.json"

    private const val TAG = "HaloQnnEnvironment"

    @Volatile
    private var bootstrapped = false

    fun bootstrap(context: Context): QnnDiagnostics {
        val appContext = context.applicationContext
        val nativeDir = appContext.applicationInfo.nativeLibraryDir
        val adspLibraryPath = buildAdspLibraryPath(nativeDir)

        if (!bootstrapped) {
            runCatching { SoLoader.init(appContext, false) }
                .onFailure { Log.w(TAG, "SoLoader init failed", it) }
            runCatching { Os.setenv("ADSP_LIBRARY_PATH", adspLibraryPath, true) }
                .onFailure { Log.e(TAG, "Failed to set ADSP_LIBRARY_PATH", it) }
            bootstrapped = true
        }

        return diagnostics(appContext).also {
            Log.i(TAG, it.asText())
        }
    }

    fun diagnostics(context: Context): QnnDiagnostics {
        val nativeDir = context.applicationContext.applicationInfo.nativeLibraryDir
        return QnnDiagnostics(
            nativeLibraryDir = nativeDir,
            adspLibraryPath = runCatching { Os.getenv("ADSP_LIBRARY_PATH") }.getOrNull(),
            modelPath = MODEL_PATH,
            tokenizerPath = TOKENIZER_PATH,
            skelReadable = File(nativeDir, "libQnnHtpV79Skel.so").canRead(),
            modelReadable = File(MODEL_PATH).canRead(),
            tokenizerReadable = File(TOKENIZER_PATH).canRead(),
        )
    }

    private fun buildAdspLibraryPath(nativeDir: String): String =
        listOf(
            nativeDir,
            "/vendor/lib64/rfs/dsp",
            "/vendor/lib/rfsa/adsp",
            "/vendor/lib/rfsa/dsp",
            "/vendor/dsp",
        ).joinToString(";")
}
