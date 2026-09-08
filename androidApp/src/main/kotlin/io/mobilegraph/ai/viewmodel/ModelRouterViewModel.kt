package io.mobilegraph.ai.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.mobilegraph.ai.config.DemoChatModels
import io.mobilegraph.ai.config.DemoProvider
import io.mobilegraph.ai.config.MissingApiKeyException
import io.mobilegraph.core.context.SimpleExecutionContext
import io.mobilegraph.core.facade.MobileGraph
import io.mobilegraph.core.facade.initialize
import io.mobilegraph.core.ids.RequestId
import io.mobilegraph.core.ids.TraceId
import io.mobilegraph.core.lifecycle.LifecycleRegistry
import io.mobilegraph.models.ChatPromptValue
import io.mobilegraph.models.ContentPart
import io.mobilegraph.models.ModelOutput
import io.mobilegraph.models.UserMessage
import io.mobilegraph.models.facade.models
import io.mobilegraph.models.facade.router
import io.mobilegraph.models.facade.withModels
import io.mobilegraph.models.routing.hasImages
import io.mobilegraph.models.routing.promptLength
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * ViewModel demonstrating Intelligent Model Routing.
 */
class ModelRouterViewModel : ViewModel() {
    var uiState by mutableStateOf("Ready")
    var isLoading by mutableStateOf(false)
    var responseText by mutableStateOf("")
    var routedModelName by mutableStateOf("")
    var lifecycleState by mutableStateOf("Unknown")

    private var isInitialized = false

    fun initializeSdk(context: Context) {
        if (isInitialized) return

        val available = DemoChatModels.availableProviders()
        if (available.size < 2) {
            uiState =
                "Model routing needs at least 2 API keys configured in local.properties " +
                    "(currently ${available.size})."
            return
        }

        val defaultModel = available.first().modelName
        val visionModel =
            available.find { it.provider == DemoProvider.OPENAI }?.modelName
                ?: available.first().modelName
        val longContextModel =
            available.find { it.provider == DemoProvider.GEMINI }?.modelName
                ?: available.getOrNull(1)?.modelName
                ?: defaultModel
        val preferredDefault =
            available.find { it.provider == DemoProvider.ANTHROPIC }?.modelName
                ?: defaultModel

        MobileGraph.initialize(context) {
            withModels {
                DemoChatModels.registerConfiguredProviders(this)

                router("smart-assistant") {
                    policy {
                        condition { it.hasImages }
                        use(visionModel)
                    }

                    if (longContextModel != preferredDefault) {
                        policy {
                            condition { it.promptLength > 500 }
                            use(longContextModel)
                        }
                    }

                    default(preferredDefault)
                }
            }
        }

        isInitialized = true
        uiState = "Router ready with ${available.size} providers"

        // Observe lifecycle state
        viewModelScope.launch {
            val registry = MobileGraph.instance.getComponent(LifecycleRegistry::class)
            registry?.currentState?.collect { state ->
                lifecycleState = state.name
            }
        }
    }

    fun runSmartQuery(
        query: String,
        imageUrl: String? = null,
    ) {
        viewModelScope.launch {
            if (!isInitialized) {
                uiState =
                    try {
                        val available = DemoChatModels.availableProviders()
                        if (available.size < 2) {
                            "Model routing needs at least 2 API keys configured in local.properties " +
                                "(currently ${available.size})."
                        } else {
                            "SDK not initialized"
                        }
                    } catch (e: MissingApiKeyException) {
                        e.message ?: "Missing API key"
                    }
                return@launch
            }
            isLoading = true
            uiState = "Routing query..."
            responseText = ""
            routedModelName = ""

            try {
                // We invoke the ROUTER, not a specific model
                val router = MobileGraph.instance.models.chat("smart-assistant")

                val parts = mutableListOf<ContentPart>(ContentPart.Text(query))
                if (!imageUrl.isNullOrBlank()) {
                    parts.add(ContentPart.Image(data = imageUrl))
                }

                val prompt = ChatPromptValue(listOf(UserMessage(parts)))
                val context =
                    SimpleExecutionContext(
                        traceId = TraceId("router-test-${Random.nextInt()}"),
                        requestId = RequestId("req-${Random.nextInt()}"),
                    )

                // The router internally decides which model to call
                val output = router.invoke(prompt, context = context)

                when (output) {
                    is ModelOutput.ChatOutput -> {
                        responseText = output.message.content
                        // In a real app, we might want to expose which model was used in metadata
                        uiState = "Success"
                    }

                    is ModelOutput.ErrorOutput -> {
                        responseText = "Error: ${output.error.message}"
                        uiState = "Failed"
                    }
                }
            } catch (e: Exception) {
                responseText = "Exception: ${e.message}"
                uiState = "Error"
            } finally {
                isLoading = false
            }
        }
    }
}
