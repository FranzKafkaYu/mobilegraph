package io.mobilegraph.ai.config

import io.mobilegraph.ai.BuildConfig
import io.mobilegraph.core.configuration.ModelsConfiguration
import io.mobilegraph.models.ChatModel
import io.mobilegraph.models.anthropic.ClaudeChatModel
import io.mobilegraph.models.deepseek.DeepSeekChatModel
import io.mobilegraph.models.facade.ChatModelBuilder
import io.mobilegraph.models.facade.claude
import io.mobilegraph.models.facade.deepseek
import io.mobilegraph.models.facade.gemini
import io.mobilegraph.models.facade.openai
import io.mobilegraph.models.facade.openrouter
import io.mobilegraph.models.google.GeminiChatModel
import io.mobilegraph.models.openai.OpenAIChatModel
import io.mobilegraph.models.openrouter.OpenRouterChatModel

enum class DemoModelSize {
    DEFAULT,
    MINI,
}

/**
 * androidApp helpers that map BuildConfig keys (from local.properties) to ChatModels.
 */
object DemoChatModels {
    fun keysFromBuildConfig(): ProviderApiKeys =
        ProviderApiKeys(
            deepSeek = BuildConfig.DEEP_SEEK_API_KEY,
            openAi = BuildConfig.OPEN_AI_API_KEY,
            gemini = BuildConfig.GEMINI_API_KEY,
            anthropic = BuildConfig.ANTHROPIC_API_KEY,
            openRouter = BuildConfig.OPEN_ROUTER_API_KEY,
        )

    fun availableProviders(): List<SelectedProvider> = DemoProviderSelector.available(keysFromBuildConfig())

    fun requireDefaultProvider(): SelectedProvider = DemoProviderSelector.requireDefault(keysFromBuildConfig())

    fun createChatModel(
        selected: SelectedProvider,
        size: DemoModelSize = DemoModelSize.DEFAULT,
    ): ChatModel {
        val name =
            when (size) {
                DemoModelSize.DEFAULT -> selected.modelName
                DemoModelSize.MINI -> selected.miniModelName
            }
        return when (selected.provider) {
            DemoProvider.DEEPSEEK -> DeepSeekChatModel(apiKey = selected.apiKey, name = name)
            DemoProvider.OPENAI -> OpenAIChatModel(apiKey = selected.apiKey, name = name)
            DemoProvider.GEMINI -> GeminiChatModel(apiKey = selected.apiKey, name = name)
            DemoProvider.ANTHROPIC -> ClaudeChatModel(apiKey = selected.apiKey, name = name)
            DemoProvider.OPENROUTER -> OpenRouterChatModel(apiKey = selected.apiKey, name = name)
        }
    }

    fun createDefaultChatModel(size: DemoModelSize = DemoModelSize.DEFAULT): ChatModel =
        createChatModel(requireDefaultProvider(), size)

    /**
     * Registers every provider that has a non-blank key.
     * Marks the priority-default provider as default when [markDefault] is true.
     */
    fun registerConfiguredProviders(
        models: ModelsConfiguration,
        markDefault: Boolean = true,
        modelNameOverride: Map<DemoProvider, String> = emptyMap(),
        block: ChatModelBuilder.() -> Unit = {},
    ): List<SelectedProvider> {
        val available = availableProviders()
        if (available.isEmpty()) {
            throw MissingApiKeyException(
                "No LLM API key configured. Add one of these to local.properties, then Gradle sync/rebuild: " +
                    DemoProviderSelector.priorityOrder.joinToString(", ") { it.localPropertiesKey },
            )
        }
        val defaultProvider = available.first().provider
        available.forEach { selected ->
            val name = modelNameOverride[selected.provider] ?: selected.modelName
            val isDefault = markDefault && selected.provider == defaultProvider
            with(models) {
                when (selected.provider) {
                    DemoProvider.DEEPSEEK ->
                        deepseek(apiKey = selected.apiKey, name = name) {
                            this.isDefault = isDefault
                            block()
                        }
                    DemoProvider.OPENAI ->
                        openai(apiKey = selected.apiKey, name = name) {
                            this.isDefault = isDefault
                            block()
                        }
                    DemoProvider.GEMINI ->
                        gemini(apiKey = selected.apiKey, name = name) {
                            this.isDefault = isDefault
                            block()
                        }
                    DemoProvider.ANTHROPIC ->
                        claude(apiKey = selected.apiKey, name = name) {
                            this.isDefault = isDefault
                            block()
                        }
                    DemoProvider.OPENROUTER ->
                        openrouter(apiKey = selected.apiKey, name = name) {
                            this.isDefault = isDefault
                            block()
                        }
                }
            }
        }
        return available
    }
}
