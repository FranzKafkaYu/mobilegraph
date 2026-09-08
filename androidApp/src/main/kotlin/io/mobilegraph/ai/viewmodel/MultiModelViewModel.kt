package io.mobilegraph.ai.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.mobilegraph.ai.ApplicationLogger
import io.mobilegraph.ai.config.DemoChatModels
import io.mobilegraph.ai.config.DemoProviderSelector
import io.mobilegraph.ai.config.MissingApiKeyException
import io.mobilegraph.ai.utils.ImageResizer
import io.mobilegraph.core.context.SimpleExecutionContext
import io.mobilegraph.core.facade.MobileGraph
import io.mobilegraph.core.ids.RequestId
import io.mobilegraph.core.ids.TraceId
import io.mobilegraph.models.ChatPromptValue
import io.mobilegraph.models.ContentPart
import io.mobilegraph.models.ModelOutput
import io.mobilegraph.models.UserMessage
import io.mobilegraph.models.facade.models
import io.mobilegraph.models.facade.withModels
import io.mobilegraph.models.middleware.LoggingMiddleware
import io.mobilegraph.models.middleware.ToolSelectionMiddleware
import io.mobilegraph.tools.facade.withTools
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * ViewModel demonstrating Multi-Model support and Multi-Modal (Vision) content.
 */
class MultiModelViewModel : ViewModel() {
    var uiState by mutableStateOf("Ready")
    var isLoading by mutableStateOf(false)
    var responseText by mutableStateOf("")
    var selectedProvider by mutableStateOf("")
    var selectedImageBytes by mutableStateOf<ByteArray?>(null)
    var providers by mutableStateOf<Map<String, String>>(emptyMap())
    private var isInitialized = false

    fun initializeSdk(context: Context) {
        if (isInitialized) return

        val available = DemoChatModels.availableProviders()
        if (available.isEmpty()) {
            uiState =
                "No LLM API key configured. Add one of these to local.properties, then Gradle sync/rebuild: " +
                    DemoProviderSelector.priorityOrder.joinToString(", ") { it.localPropertiesKey }
            return
        }

        providers = available.associate { it.provider.displayName to it.modelName }
        selectedProvider = available.first().modelName

        MobileGraph.initialize {
            withTools {
                register(WeatherTool())
            }

            withModels {
                DemoChatModels.registerConfiguredProviders(this) {
                    middleware {
                        +LoggingMiddleware(ApplicationLogger())
                        +ToolSelectionMiddleware()
                    }
                }
            }
        }

        isInitialized = true
        uiState = "SDK Initialized with ${available.size} provider(s)"
    }

    fun runQuery(query: String) {
        viewModelScope.launch {
            if (!isInitialized) {
                uiState =
                    try {
                        DemoChatModels.requireDefaultProvider()
                        "SDK not initialized"
                    } catch (e: MissingApiKeyException) {
                        e.message ?: "Missing API key"
                    }
                return@launch
            }
            isLoading = true
            uiState = "Invoking $selectedProvider..."
            responseText = ""

            try {
                val model = MobileGraph.instance.models.chat(selectedProvider)

                // Build a Multi-Modal message if image is provided
                val parts = mutableListOf<ContentPart>(ContentPart.Text(query))

                // Priority: Selected bytes from gallery > URL
                if (selectedImageBytes != null) {
                    val downscaled = ImageResizer.downscaleToMax(selectedImageBytes!!)
                    parts.add(ContentPart.Image(bytes = downscaled))
                }

                val prompt = ChatPromptValue(listOf(UserMessage(parts)))

                val context =
                    SimpleExecutionContext(
                        traceId = TraceId("multi-model-${Random.nextInt()}"),
                        requestId = RequestId("req-${Random.nextInt()}"),
                    )

                val output = model.invoke(prompt, context = context)

                when (output) {
                    is ModelOutput.ChatOutput -> {
                        responseText = output.message.content
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
