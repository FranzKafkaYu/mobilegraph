package io.mobilegraph.ai.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.mobilegraph.agents.Agent
import io.mobilegraph.agents.AgentNode
import io.mobilegraph.agents.DefaultAgentRuntime
import io.mobilegraph.ai.config.DemoChatModels
import io.mobilegraph.ai.config.DemoModelSize
import io.mobilegraph.ai.config.DemoProvider
import io.mobilegraph.ai.config.MissingApiKeyException
import io.mobilegraph.checkpoint.InMemoryCheckpointStore
import io.mobilegraph.core.context.SimpleExecutionContext
import io.mobilegraph.core.facade.MobileGraph
import io.mobilegraph.core.ids.RequestId
import io.mobilegraph.core.ids.TraceId
import io.mobilegraph.core.tools.ToolRegistry
import io.mobilegraph.graph.DefaultExecutionEngine
import io.mobilegraph.graph.EndNode
import io.mobilegraph.graph.ExecutionResult
import io.mobilegraph.graph.StateGraph
import io.mobilegraph.graph.stateGraph
import io.mobilegraph.models.ChatModel
import io.mobilegraph.models.ModelOutput
import io.mobilegraph.models.SystemMessage
import io.mobilegraph.models.UserMessage
import io.mobilegraph.models.facade.chat
import io.mobilegraph.models.facade.models
import io.mobilegraph.models.facade.router
import io.mobilegraph.models.facade.withModels
import io.mobilegraph.parsers.asText
import io.mobilegraph.state.GraphState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * ViewModel demonstrating Agents utilizing a Model Router for
 * intelligent brain selection (Manager vs Worker models).
 *
 * GRAPH STRUCTURE:
 *
 * [ROUTED WORKFLOW]
 *       (manager)  <-- Routed to preferred high-reasoning model
 *          |
 *       (worker)   <-- Routed to preferred fast/cheap model
 *          |
 *        (end)
 */
class AgentRouterViewModel : ViewModel() {
    var uiState by mutableStateOf("Ready")
    var isLoading by mutableStateOf(false)
    var finalResult by mutableStateOf("")

    private val _eventLog = MutableStateFlow<List<String>>(emptyList())
    val eventLog: StateFlow<List<String>> = _eventLog

    private val checkpointStore = InMemoryCheckpointStore()
    private val executionEngine = DefaultExecutionEngine(checkpointStore)
    private val agentRuntime = DefaultAgentRuntime(executionEngine, checkpointStore)
    private var isInitialized = false

    private lateinit var workflow: StateGraph

    fun initializeSdk(context: Context) {
        if (isInitialized) return

        val available = DemoChatModels.availableProviders()
        if (available.size < 2) {
            uiState =
                "Agent routing needs at least 2 API keys for manager and worker models " +
                    "(currently ${available.size})."
            return
        }

        val manager =
            available.find { it.provider == DemoProvider.ANTHROPIC }
                ?: available.first()
        val worker =
            available
                .find { it.provider == DemoProvider.OPENAI }
                ?.takeIf { it.provider != manager.provider }
                ?: available.first { it.provider != manager.provider }

        val managerName = manager.modelName
        val useWorkerMini = worker.provider == DemoProvider.OPENAI
        val workerName = if (useWorkerMini) worker.miniModelName else worker.modelName
        val workerSize = if (useWorkerMini) DemoModelSize.MINI else DemoModelSize.DEFAULT

        MobileGraph.initialize {
            withModels {
                chat(managerName, DemoChatModels.createChatModel(manager)) {
                    isDefault = true
                }
                chat(workerName, DemoChatModels.createChatModel(worker, workerSize)) {}

                // Setup the "Brain Router"
                router("smart-brain") {
                    // Rule: If the prompt comes from a "Manager", use the manager model
                    policy {
                        condition { it.prompt.messages.any { msg -> msg is SystemMessage && msg.content.contains("Manager") } }
                        use(managerName)
                    }
                    // Default for all other agents (Workers)
                    default(workerName)
                }
            }
        }

        val router = MobileGraph.instance.models.chat("smart-brain")

        // Define Agents that all use the SAME router instance
        val managerAgent = RouterManagerAgent(router)
        val workerAgent = RouterWorkerAgent(router)

        // Build the workflow
        workflow =
            stateGraph {
                start("manager")
                node(AgentNode("manager", managerAgent, agentRuntime))
                node(AgentNode("worker", workerAgent, agentRuntime))
                node(EndNode("end"))

                edge("manager", "worker")
                edge("worker", "end")
            }

        isInitialized = true
        uiState = "Router ready (manager=$managerName, worker=$workerName)"
    }

    fun runRoutedWorkflow(query: String) {
        viewModelScope.launch {
            if (!isInitialized) {
                uiState =
                    try {
                        val available = DemoChatModels.availableProviders()
                        if (available.size < 2) {
                            "Agent routing needs at least 2 API keys for manager and worker models " +
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
            uiState = "Executing with Routed Brains..."
            finalResult = ""

            try {
                val initialState =
                    SimpleGraphState(
                        executionContext =
                            SimpleExecutionContext(
                                traceId = TraceId("router-agent-${Random.nextInt()}"),
                                requestId = RequestId("req-${Random.nextInt()}"),
                            ),
                        userQuery = query,
                    )

                val result = agentRuntime.run(workflow, initialState)
                if (result is ExecutionResult.Success) {
                    uiState = "Completed"
                    finalResult = result.state.variables["final_output"] as? String ?: "No output"
                }
            } catch (e: Exception) {
                uiState = "Error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    private fun addEvent(event: String) {
        _eventLog.value = _eventLog.value + event
    }
}

/**
 * High-reasoning agent that identifies as "Manager".
 * The router will detect this and switch to the manager model.
 */
class RouterManagerAgent(
    override val model: ChatModel,
) : Agent {
    override val name = "Manager"
    override val description = "Orchestrator"
    override val tools: ToolRegistry? = null
    override val rolePrompt = SystemMessage("You are a High-Level Manager. Create a 3-step strategy for the user's query.")
    override val graph: StateGraph? = null

    override fun formatAgentInstruction(state: GraphState) = UserMessage(state.userQuery)

    override suspend fun handleLlmOutput(
        output: ModelOutput,
        state: GraphState,
    ): GraphState = state.copy(variables = state.variables + ("strategy" to output.asText()))
}

/**
 * Fast worker agent.
 * The router will use the default worker model.
 */
class RouterWorkerAgent(
    override val model: ChatModel,
) : Agent {
    override val name = "Worker"
    override val description = "Executor"
    override val tools: ToolRegistry? = null
    override val rolePrompt = SystemMessage("You are a Fast Worker. Turn the provided strategy into a short summary.")
    override val graph: StateGraph? = null

    override fun formatAgentInstruction(state: GraphState): UserMessage {
        val strategy = state.variables["strategy"] as? String ?: "No strategy"
        return UserMessage("Summarize this: $strategy")
    }

    override suspend fun handleLlmOutput(
        output: ModelOutput,
        state: GraphState,
    ): GraphState = state.copy(variables = state.variables + ("final_output" to output.asText()))
}
