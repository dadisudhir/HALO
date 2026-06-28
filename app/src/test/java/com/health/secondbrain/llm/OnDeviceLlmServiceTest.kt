package com.health.secondbrain.llm

import androidx.compose.ui.graphics.Color
import com.health.secondbrain.data.HealthAgentContext
import com.health.secondbrain.data.HealthBackendMode
import com.health.secondbrain.model.DeltaDirection
import com.health.secondbrain.model.Metric
import com.health.secondbrain.model.OrganNode
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnDeviceLlmServiceTest {
    @Test
    fun doesNotCallToolUnlessPlannerRequestsIt() = runBlocking {
        val generator = FakeGenerator("HALO_RESPONSE: Hey, I can help with HALO.")
        val tools = FakeTools()
        val service = OnDeviceLlmService(generator = generator, toolClient = tools)

        val response = service.generateChat(testContext(), "what is up my dude")

        assertEquals("Hey, I can help with HALO.", response.text)
        assertEquals("classified smalltalk - local - Qwen final", response.statusLine)
        assertEquals("no_action", response.decision.action)
        assertEquals("smalltalk", response.decision.route)
        assertEquals("qwen_final", response.decision.answerSource)
        assertFalse(response.usedTool)
        assertEquals(emptyList(), tools.webQueries)
        assertEquals(1, generator.prompts.size)
        assertTrue("HALO_SIGNAL_CONTEXT: omitted_for_this_route" in generator.prompts.single())
    }

    @Test
    fun executesWebSearchOnlyWhenPlannerRequestsIt() = runBlocking {
        val generator = FakeGenerator(
            """{"type":"tool_request","tool":"web_search","route":"web_research","query":"heart recovery evidence","reason":"needs source-backed evidence"}""",
            "HALO_RESPONSE: Source-backed recovery guidance is available.",
        )
        val tools = FakeTools()
        val service = OnDeviceLlmService(generator = generator, toolClient = tools)

        val response = service.generateChat(testContext(), "find current evidence for heart recovery")

        assertEquals(listOf("heart recovery evidence"), tools.webQueries)
        assertEquals("classified web_research - web_search - Qwen final", response.statusLine)
        assertEquals("web_search", response.decision.action)
        assertEquals("web_research", response.decision.route)
        assertEquals("web_search", response.decision.toolName)
        assertTrue(response.usedTool)
        assertTrue("Source results:" in generator.prompts.last())
        assertFalse("https://" in response.text)
    }

    @Test
    fun invalidPlannerOutputDoesNotAutoCallTool() = runBlocking {
        val generator = FakeGenerator("HALO_RESPONSE: Hello, I can help with HALO.")
        val tools = FakeTools()
        val service = OnDeviceLlmService(generator = generator, toolClient = tools)

        val response = service.generateChat(testContext(), "hello today")

        assertEquals(emptyList(), tools.webQueries)
        assertFalse(response.usedTool)
        assertEquals("Hello, I can help with HALO.", response.text)
        assertEquals("qwen_final", response.decision.answerSource)
        assertEquals(1, generator.prompts.size)
    }

    @Test
    fun smalltalkRouteRendersFinalAnswerWithModel() = runBlocking {
        val generator = FakeGenerator("HALO_RESPONSE: Hi, I am here.")
        val service = OnDeviceLlmService(generator = generator, toolClient = FakeTools())

        val response = service.generateChat(testContext(), "what is up my dude")

        assertEquals("Hi, I am here.", response.text)
        assertEquals("qwen_final", response.decision.answerSource)
        assertFalse(response.decision.needsHealthContext)
        assertEquals(1, generator.prompts.size)
    }

    @Test
    fun localContextRouteAttachesBackendGraphWithoutWeb() = runBlocking {
        val generator = FakeGenerator(
            "HALO_RESPONSE: Recorded biometrics are not available yet, so I can explain the visible heart graph only as HALO card context: Resting HR 65 (+4bpm) and HRV 44 (-9).",
        )
        val tools = FakeTools()
        val service = OnDeviceLlmService(generator = generator, toolClient = tools)

        val response = service.generateChat(testContext(), "what am i looking at")

        assertEquals(emptyList(), tools.webQueries)
        assertFalse(response.usedTool)
        assertEquals("data_recall", response.decision.action)
        assertEquals("local_context", response.decision.route)
        assertTrue(response.decision.needsHealthContext)
        assertEquals("qwen_final", response.decision.answerSource)
        assertEquals("classified local_context - health_context - Qwen final", response.statusLine)
        assertEquals("heart", response.visual?.domainId)
        assertTrue("visible heart graph" in response.text)
        assertTrue("Resting HR 65 (+4bpm)" in response.text)
        assertFalse("MVP demo" in response.text)
        assertFalse("I cannot infer" in response.text)
        assertEquals(1, generator.prompts.size)
    }

    @Test
    fun healthStatusWithNoRecordedDataRendersFromModelWithVisibleSignals() = runBlocking {
        val generator = FakeGenerator(
            "HALO_RESPONSE: Recorded biometrics are not available yet, so I can only describe the visible HALO heart card: Resting HR 65 (+4bpm) and HRV 44 (-9).",
        )
        val service = OnDeviceLlmService(generator = generator, toolClient = FakeTools())

        val response = service.generateChat(testContext(), "how healthy is my heart right now")

        assertEquals("health_status", response.decision.route)
        assertEquals("qwen_final", response.decision.answerSource)
        assertEquals("classified health_status - health_context - Qwen final", response.statusLine)
        assertTrue("visible HALO heart card" in response.text)
        assertTrue("Resting HR 65 (+4bpm)" in response.text)
        assertTrue("HRV 44 (-9)" in response.text)
        assertFalse("MVP demo" in response.text)
        assertFalse("I cannot infer" in response.text)
        assertEquals(1, generator.prompts.size)
    }

    @Test
    fun currentHeartStatusPromptRendersAnswerWithModelButDoesNotCallTool() = runBlocking {
        val generator = FakeGenerator(
            "HALO_RESPONSE: The visible heart card shows Resting HR 65 (+4bpm) and HRV 44 (-9).",
        )
        val tools = FakeTools()
        val service = OnDeviceLlmService(generator = generator, toolClient = tools)

        val response = service.generateChat(testContext(), "tell me current status for my heart")

        assertEquals(emptyList(), tools.webQueries)
        assertFalse(response.usedTool)
        assertEquals("data_recall", response.decision.action)
        assertEquals("health_status", response.decision.route)
        assertEquals("qwen_final", response.decision.answerSource)
        assertEquals("classified health_status - health_context - Qwen final", response.statusLine)
        assertTrue("visible heart card" in response.text)
        assertTrue("Resting HR 65 (+4bpm)" in response.text)
        assertFalse("MVP demo" in response.text)
        assertFalse("I cannot infer" in response.text)
        assertEquals(1, generator.prompts.size)
    }

    @Test
    fun healthPromptIncludesCompactSignalContextWhenModelPathIsUsed() {
        val prompt = HealthAgentPromptBuilder.buildChatPrompt(
            context = testContext(),
            userMessage = "how healthy is my heart right now",
            plan = AgentPlan(
                type = AgentPlanType.Final,
                route = AgentRoute.HealthStatus,
                needsHealthContext = true,
            ),
        )

        assertTrue("HALO_SIGNAL_CONTEXT:" in prompt)
        assertTrue("visible_card_context=true" in prompt)
        assertTrue("status_card=needs_attention" in prompt)
        assertTrue("Resting HR" in prompt)
        assertTrue("visible_metrics=" in prompt)
    }

    @Test
    fun canonicalPlannerRoutesHealthyHeartPromptAsHealthStatus() {
        val plan = OnDeviceLlmService.canonicalPlanForTesting(
            raw = "B",
            context = testContext(),
            userMessage = "how healthy is my heart right now",
        )

        assertEquals(AgentPlanType.Final, plan.type)
        assertEquals(AgentRoute.HealthStatus, plan.route)
        assertTrue(plan.needsHealthContext)
    }

    @Test
    fun failedWebToolIsPreservedAsToolFailure() = runBlocking {
        val generator = FakeGenerator(
            """{"type":"tool_request","tool":"web_search","route":"web_research","query":"heart recovery evidence"}""",
            "HALO_RESPONSE: The search context is unavailable right now.",
        )
        val tools = FakeTools(failWebSearch = true)
        val service = OnDeviceLlmService(generator = generator, toolClient = tools)

        val response = service.generateChat(testContext(), "search the web for heart recovery evidence")

        assertEquals(listOf("heart recovery evidence"), tools.webQueries)
        assertTrue(response.usedTool)
        assertEquals("web_search_failed", response.decision.action)
        assertEquals("classified web_research - web_search failed - Qwen final", response.statusLine)
        assertTrue("Tool execution failed:" in generator.prompts.last())
    }

    @Test
    fun rawModelTokensAreNotStreamedIntoUiBeforeCleanup() = runBlocking {
        val generator = FakeGenerator(
            """{"type":"final","route":"smalltalk","needs_health_context":false}""",
            "HALO_RESPONSE: Hello.",
        )
        val streamed = mutableListOf<String>()
        val service = OnDeviceLlmService(generator = generator, toolClient = FakeTools())

        service.generateChat(testContext(), "hi", onToken = { streamed += it })

        assertEquals(emptyList(), streamed)
        assertEquals(0, generator.streamCallbackCount)
    }

    @Test
    fun canonicalPlannerRepairsMalformedWebToolSchema() {
        val plan = OnDeviceLlmService.canonicalPlanForTesting(
            raw = """{"type":"tool__request","tool":"web__search","route":"web_research","query":"latest sleep evidence"}""",
            context = testContext(),
            userMessage = "Search latest sleep evidence",
        )

        assertEquals(AgentPlanType.ToolRequest, plan.type)
        assertEquals("web_search", plan.tool)
        assertEquals(AgentRoute.WebResearch, plan.route)
        assertFalse(plan.needsHealthContext)
    }

    @Test
    fun canonicalPlannerUsesSingleLetterLabel() {
        val plan = OnDeviceLlmService.canonicalPlanForTesting(
            raw = "C",
            context = testContext(),
            userMessage = "Search latest sleep evidence",
        )

        assertEquals(AgentPlanType.ToolRequest, plan.type)
        assertEquals("web_search", plan.tool)
        assertEquals(AgentRoute.WebResearch, plan.route)
        assertFalse(plan.needsHealthContext)
    }

    @Test
    fun routingLabelStopsOnStandaloneLabel() {
        assertEquals('B', AgentPlanner.firstRoutingLabel("B"))
        assertEquals('C', AgentPlanner.firstRoutingLabel("Answer: C"))
        assertEquals(null, AgentPlanner.firstRoutingLabel("because"))
    }

    @Test
    fun canonicalPlannerHandlesEnumLiteralSchemaDriftForExternalRequest() {
        val plan = OnDeviceLlmService.canonicalPlanForTesting(
            raw = """{"action":"no_action|clarify|data_recall|web_search","route":"smalltalk|clarify|local_context|health_status|next_step|general_health|web_research","needs_health_context":true,"query":"safe VO2 max guidance"}""",
            context = testContext(),
            userMessage = "Find source-backed guidance for improving VO2 max safely.",
        )

        assertEquals(AgentPlanType.ToolRequest, plan.type)
        assertEquals("web_search", plan.tool)
        assertEquals(AgentRoute.WebResearch, plan.route)
        assertFalse(plan.needsHealthContext)
    }

    @Test
    fun canonicalPlannerDemotesLocalDataPromptFromWeb() {
        val plan = OnDeviceLlmService.canonicalPlanForTesting(
            raw = """{"type":"tool_request","tool":"web_search","route":"web_research","query":"kidney metrics this week"}""",
            context = testContext(),
            userMessage = "What changed in my kidney metrics this week?",
        )

        assertEquals(AgentPlanType.Final, plan.type)
        assertEquals(AgentRoute.LocalContext, plan.route)
        assertTrue(plan.needsHealthContext)
    }

    @Test
    fun canonicalPlannerKeepsCasualInputOutOfHealthContext() {
        val plan = OnDeviceLlmService.canonicalPlanForTesting(
            raw = """{"type":"general_health","needs_health_context":true}""",
            context = testContext(),
            userMessage = "what is up my dude",
        )

        assertEquals(AgentPlanType.Final, plan.type)
        assertEquals(AgentRoute.Smalltalk, plan.route)
        assertFalse(plan.needsHealthContext)
    }

    @Test
    fun orphanThinkCloseDoesNotLeak() {
        val visible = OnDeviceLlmService.extractVisibleAnswerForTesting(
            "的能力是提供健康信息，但根据规则，我不能提供任何医疗建议或诊断。</think> I'm here to help."
        )

        assertFalse("</think>" in visible)
        assertFalse("的能力" in visible)
        assertTrue("I'm here to help." in visible)
    }

    private class FakeGenerator(
        vararg outputs: String,
    ) : LocalTextGenerator {
        private val outputs = ArrayDeque(outputs.toList())
        val prompts = mutableListOf<String>()
        var streamCallbackCount = 0

        override suspend fun generate(
            prompt: String,
            sequenceLength: Int,
            visibleCharLimit: Int,
            onToken: ((String) -> Unit)?,
        ): String {
            prompts += prompt
            if (onToken != null) {
                streamCallbackCount += 1
                onToken.invoke("<think>raw unsafe partial")
            }
            return outputs.removeFirst()
        }
    }

    private class FakeTools(
        private val failWebSearch: Boolean = false,
    ) : AgentToolClient {
        val webQueries = mutableListOf<String>()

        override suspend fun webSearch(query: String): ToolCallResult {
            webQueries += query
            if (failWebSearch) error("bridge offline")
            return ToolCallResult(
                tool = "web_search",
                content = "Result: CDC heart recovery guidance (https://www.cdc.gov/heart-disease/)",
            )
        }

        override suspend fun fetch(url: String): ToolCallResult {
            error("fetch should not be called")
        }
    }

    private fun testContext(): HealthAgentContext =
        HealthAgentContext(
            generatedAt = Instant.parse("2026-06-28T00:00:00Z"),
            mode = HealthBackendMode.Fake,
            organ = OrganNode(
                id = "heart",
                displayName = "Heart",
                systemLabel = "Cardiovascular system",
                iconAsset = "organs/heart.svg",
                componentType = "organ",
                accent = Color.Red,
                tint = Color.Red.copy(alpha = 0.2f),
                attentionScore = 0.5f,
                metrics = listOf(
                    Metric("Resting HR", "65", "+4bpm", DeltaDirection.UpBad),
                    Metric("HRV", "44", "-9", DeltaDirection.DownBad),
                ),
                chart7Day = listOf(0.1f, 0.2f),
                activeZones = listOf(0.3f, 0.4f),
                sentenceWeek = "Heart weekly summary.",
                sentenceMonth = "Heart monthly summary.",
                sentenceNextStep = "Prioritize recovery.",
                statusGood = false,
                previewSummary = "Heart preview.",
                chatChips = emptyList(),
            ),
            recordedDailySummaries = emptyList(),
            demoDailySummaryCount = 30,
            recordedRisk = null,
            clinicalObservations = emptyList(),
        )
}
