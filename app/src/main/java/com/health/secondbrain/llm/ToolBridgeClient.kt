package com.health.secondbrain.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ToolCallResult(
    val tool: String,
    val content: String,
)

class ToolBridgeClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    suspend fun webSearch(query: String): ToolCallResult = withContext(Dispatchers.IO) {
        postTool(
            name = "web_search",
            payload = JSONObject().put("query", query),
        )
    }

    suspend fun fetch(url: String): ToolCallResult = withContext(Dispatchers.IO) {
        postTool(
            name = "fetch",
            payload = JSONObject().put("url", url),
        )
    }

    private fun postTool(name: String, payload: JSONObject): ToolCallResult {
        val connection = (URL("$baseUrl/$name").openConnection() as HttpURLConnection).apply {
            connectTimeout = 900
            readTimeout = 3500
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doOutput = true
        }

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
        }

        val statusCode = connection.responseCode
        val body = readStream(
            if (statusCode in 200..299) connection.inputStream else connection.errorStream
        )
        val response = JSONObject(body)
        if (!response.optBoolean("ok")) {
            throw IllegalStateException(response.optString("error", "Tool bridge failed"))
        }
        return ToolCallResult(
            tool = response.optString("tool", name),
            content = response.getString("content"),
        )
    }

    private fun readStream(stream: InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            buildString {
                var line = reader.readLine()
                while (line != null) {
                    append(line).append('\n')
                    line = reader.readLine()
                }
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8765"
    }
}
