package org.cc.tool.mcp

import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.cc.tool.tool.ClassSourceToolset
import org.cc.tool.utils.parse
import org.cc.tool.utils.toJson
import org.cc.tool.utils.toJsonElement

/**
 * MCP 服务器核心处理逻辑
 */
object McpServerHandler {
    private val logger by lazy { logger<McpServerHandler>() }
    private val tools = mutableMapOf<McpTool, AbstractTool>()

    init {
        tools[ClassSourceToolset.toolInfo()] = ClassSourceToolset
    }

    /**
     * 处理 MCP 请求
     */
    fun handleRequest(requestJson: String): String? {
        return try {
            val request = requestJson.parse<McpRequest>()
            logger.info("处理 MCP 请求: ${request.method}")

            val response = when (request.method) {
                "initialize" -> handleInitialize(request)
                "tools/list" -> handleToolsList(request)
                "tools/call" -> handleToolsCall(request)
                "notifications/cancelled" -> null // 通知，不需要响应
                else -> createErrorResponse(request.id, McpErrorCodes.METHOD_NOT_FOUND, "方法未找到: ${request.method}")
            }
            response?.toJson()
        } catch (e: Exception) {
            logger.error("处理 MCP 请求时发生错误", e)
            val errorResponse = McpResponse(
                id = null,
                error = McpError(
                    code = McpErrorCodes.PARSE_ERROR,
                    message = "解析请求时发生错误: ${e.message}"
                )
            )
            errorResponse.toJson()
        }
    }

    /**
     * 处理初始化请求
     */
    private fun handleInitialize(request: McpRequest): McpResponse {
        try {
            val initRequest = (request.params ?: JsonObject(emptyMap())).parse<McpInitializeRequest>()
            logger.info("客户端初始化: ${initRequest.clientInfo.name} v${initRequest.clientInfo.version}")
            val response = McpInitializeResponse(
                protocolVersion = "2024-11-05",
                capabilities = McpServerCapabilities(
                    tools = McpToolsCapability(listChanged = true),
                    logging = McpLoggingCapability(level = "info")
                ),
                serverInfo = McpServerInfo(
                    name = "MCP Java Source Server",
                    version = "1.0.2-SNAPSHOT"
                )
            )
            return McpResponse(
                id = request.id,
                result = response.toJsonElement()
            )
        } catch (e: Exception) {
            logger.error("初始化失败", e)
            return createErrorResponse(request.id, McpErrorCodes.INVALID_PARAMS, "初始化参数无效: ${e.message}")
        }
    }

    /**
     * 处理工具列表请求
     */
    private fun handleToolsList(request: McpRequest): McpResponse {
        val toolsList = McpToolsListResponse(tools = tools.keys.toList())
        return McpResponse(
            id = request.id,
            result = toolsList.toJsonElement()
        )
    }

    /**
     * 处理工具调用请求
     */
    private fun handleToolsCall(request: McpRequest): McpResponse {
        try {
            val callRequest = (request.params ?: JsonObject(emptyMap())).parse<McpCallToolRequest>()

            val tool = tools.entries.firstOrNull { it.key.name == callRequest.name }?.value
                ?: return createErrorResponse(
                    request.id,
                    McpErrorCodes.TOOL_NOT_FOUND,
                    "工具未找到: ${callRequest.name}"
                )

            // 调用工具
            val result = tool.callTool(callRequest.arguments)

            return McpResponse(
                id = request.id,
                result = result.toJsonElement()
            )
        } catch (e: Exception) {
            logger.error("调用工具时发生错误", e)
            return createErrorResponse(request.id, McpErrorCodes.INTERNAL_ERROR, "调用工具时发生错误: ${e.message}")
        }
    }

    /**
     * 调用具体的工具
     */
    private fun AbstractTool.callTool(arguments: JsonElement?): McpCallToolResponse {
        try {
            val result = execute(arguments)
            return McpCallToolResponse(content = listOf(McpContent(type = "text", text = result)), isError = false)
        } catch (e: Exception) {
            logger.error("获取类源码失败", e)
            return McpCallToolResponse(
                content = listOf(McpContent(type = "text", text = "获取类源码失败: ${e.message}")),
                isError = true
            )
        }
    }

    /**
     * 创建错误响应
     */
    private fun createErrorResponse(id: Int?, code: Int, message: String): McpResponse {
        return McpResponse(
            id = id,
            error = McpError(code = code, message = message)
        )
    }
}
