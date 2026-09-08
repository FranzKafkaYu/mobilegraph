package io.mobilegraph.ai.config

/**
 * Providers supported by androidApp demos (ordered by default selection priority).
 */
enum class DemoProvider(
    val displayName: String,
    val localPropertiesKey: String,
    val defaultModelName: String,
    val miniModelName: String,
) {
    OPENAI(
        displayName = "OpenAI",
        localPropertiesKey = "open_ai_api",
        defaultModelName = "gpt-4o",
        miniModelName = "gpt-4o-mini",
    ),
    GEMINI(
        displayName = "Gemini",
        localPropertiesKey = "gemini_api_key",
        defaultModelName = "gemini-2.5-flash-lite",
        miniModelName = "gemini-2.5-flash-lite",
    ),
    ANTHROPIC(
        displayName = "Anthropic",
        localPropertiesKey = "anthropic_api_key",
        defaultModelName = "claude-sonnet-4-5-20250929",
        miniModelName = "claude-sonnet-4-5-20250929",
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        localPropertiesKey = "deep_seek_api_key",
        defaultModelName = "deepseek-chat",
        miniModelName = "deepseek-chat",
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        localPropertiesKey = "open_router_api",
        defaultModelName = "poolside/laguna-xs-2.1:free",
        miniModelName = "poolside/laguna-xs-2.1:free",
    ),
}

data class ProviderApiKeys(
    val deepSeek: String = "",
    val openAi: String = "",
    val gemini: String = "",
    val anthropic: String = "",
    val openRouter: String = "",
) {
    fun keyFor(provider: DemoProvider): String =
        when (provider) {
            DemoProvider.DEEPSEEK -> deepSeek
            DemoProvider.OPENAI -> openAi
            DemoProvider.GEMINI -> gemini
            DemoProvider.ANTHROPIC -> anthropic
            DemoProvider.OPENROUTER -> openRouter
        }
}

data class SelectedProvider(
    val provider: DemoProvider,
    val apiKey: String,
) {
    val modelName: String get() = provider.defaultModelName
    val miniModelName: String get() = provider.miniModelName
}

class MissingApiKeyException(
    message: String,
) : IllegalStateException(message)

/**
 * Pure selection logic over API key strings (unit-testable without BuildConfig).
 */
object DemoProviderSelector {
    val priorityOrder: List<DemoProvider> = DemoProvider.entries.toList()

    fun normalize(key: String?): String? = key?.trim()?.takeIf { it.isNotEmpty() }

    fun available(keys: ProviderApiKeys): List<SelectedProvider> =
        priorityOrder.mapNotNull { provider ->
            normalize(keys.keyFor(provider))?.let { SelectedProvider(provider, it) }
        }

    fun requireDefault(keys: ProviderApiKeys): SelectedProvider =
        available(keys).firstOrNull()
            ?: throw MissingApiKeyException(
                "No LLM API key configured. Add one of these to local.properties, then Gradle sync/rebuild: " +
                    priorityOrder.joinToString(", ") { it.localPropertiesKey },
            )

    fun isAvailable(
        keys: ProviderApiKeys,
        provider: DemoProvider,
    ): Boolean = normalize(keys.keyFor(provider)) != null
}
