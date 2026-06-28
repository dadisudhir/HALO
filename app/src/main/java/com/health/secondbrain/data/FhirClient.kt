package com.health.secondbrain.data

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class FhirServerConfig(
    val baseUrl: String = "http://127.0.0.1:32783/csp/healthshare/demo/fhir/r4",
    val username: String = "_SYSTEM",
    val password: String = "ISCDEMO",
)

class FhirClient(private val config: FhirServerConfig = FhirServerConfig()) {

    fun fetchFirstPatientBundle(): JSONObject = getJson("${config.baseUrl}/Patient?_count=1")

    fun fetchObservationsForPatient(patientId: String): JSONObject =
        getJson("${config.baseUrl}/Observation?patient=$patientId&_count=100")

    fun fetchConditionsForPatient(patientId: String): JSONObject =
        getJson("${config.baseUrl}/Condition?patient=$patientId&_count=100")

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 10000
            setRequestProperty("Accept", "application/fhir+json")
            setRequestProperty("Authorization", basicAuth())
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        if (code !in 200..299) {
            error("FHIR request failed $code: $body")
        }
        return JSONObject(body)
    }

    private fun basicAuth(): String {
        val token = "${config.username}:${config.password}"
        return "Basic ${Base64.encodeToString(token.toByteArray(), Base64.NO_WRAP)}"
    }
}
