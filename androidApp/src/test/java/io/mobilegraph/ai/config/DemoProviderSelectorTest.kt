package io.mobilegraph.ai.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoProviderSelectorTest {
    @Test
    fun onlyDeepSeek_selectsDeepSeek() {
        val selected =
            DemoProviderSelector.requireDefault(
                ProviderApiKeys(deepSeek = "sk-deepseek"),
            )
        assertEquals(DemoProvider.DEEPSEEK, selected.provider)
        assertEquals("sk-deepseek", selected.apiKey)
    }

    @Test
    fun onlyOpenAi_selectsOpenAi() {
        val selected =
            DemoProviderSelector.requireDefault(
                ProviderApiKeys(openAi = "sk-openai"),
            )
        assertEquals(DemoProvider.OPENAI, selected.provider)
    }

    @Test
    fun deepSeekAndOpenAi_prefersDeepSeek() {
        val selected =
            DemoProviderSelector.requireDefault(
                ProviderApiKeys(deepSeek = "sk-ds", openAi = "sk-oai"),
            )
        assertEquals(DemoProvider.DEEPSEEK, selected.provider)
    }

    @Test
    fun blankKeysTreatedAsMissing() {
        assertThrows(MissingApiKeyException::class.java) {
            DemoProviderSelector.requireDefault(
                ProviderApiKeys(deepSeek = "  ", openAi = ""),
            )
        }
    }

    @Test
    fun noneConfigured_throwsWithLocalPropertiesHint() {
        val error =
            assertThrows(MissingApiKeyException::class.java) {
                DemoProviderSelector.requireDefault(ProviderApiKeys())
            }
        assertTrue(error.message!!.contains("deep_seek_api_key"))
        assertTrue(error.message!!.contains("open_ai_api"))
        assertTrue(error.message!!.contains("local.properties"))
    }

    @Test
    fun available_listsOnlyConfiguredInPriorityOrder() {
        val available =
            DemoProviderSelector.available(
                ProviderApiKeys(
                    openAi = "sk-oai",
                    gemini = "sk-gem",
                    deepSeek = "sk-ds",
                ),
            )
        assertEquals(
            listOf(DemoProvider.DEEPSEEK, DemoProvider.OPENAI, DemoProvider.GEMINI),
            available.map { it.provider },
        )
    }
}
