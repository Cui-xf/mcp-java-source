package org.cc.tool.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * MCP (Model Context Protocol) 数据模型
 * 基于 JSON-RPC 2.0 规范
 */

@Serializable
data class McpRequest(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class McpResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: McpError? = null
)

@Serializable
data class McpError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class McpNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null
)

// MCP 特定的数据模型
@Serializable
data class McpInitializeRequest(
    val protocolVersion: String = "2024-11-05",
    val capabilities: McpClientCapabilities,
    val clientInfo: McpClientInfo
)

@Serializable
data class McpClientCapabilities(
    val experimental: JsonElement? = null,
    val sampling: JsonElement? = null
)

@Serializable
data class McpClientInfo(
    val name: String,
    val version: String
)

@Serializable
data class McpInitializeResponse(
    val protocolVersion: String,
    val capabilities: McpServerCapabilities,
    val serverInfo: McpServerInfo
)

@Serializable
data class McpServerCapabilities(
    val tools: McpToolsCapability? = null,
    val resources: McpResourcesCapability? = null,
    val prompts: McpPromptsCapability? = null,
    val logging: McpLoggingCapability? = null
)

@Serializable
data class McpToolsCapability(
    val listChanged: Boolean = false
)

@Serializable
data class McpResourcesCapability(
    val subscribe: Boolean = false,
    val listChanged: Boolean = false
)

@Serializable
data class McpPromptsCapability(
    val listChanged: Boolean = false
)

@Serializable
data class McpLoggingCapability(
    val level: String = "info"
)

@Serializable
data class McpServerInfo(
    val name: String,
    val version: String
)

// Tools 相关模型
@Serializable
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonElement
)

@Serializable
data class McpToolsListResponse(
    val tools: List<McpTool>
)

@Serializable
data class McpCallToolRequest(
    val name: String,
    val arguments: JsonElement? = null
)

@Serializable
data class McpCallToolResponse(
    val content: List<McpContent>,
    val isError: Boolean = false
)

@Serializable
data class McpContent(
    val type: String, // "text" | "image" | "resource"
    val text: String? = null,
    val data: String? = null, // base64 encoded for images
    val mimeType: String? = null
)

// 错误代码常量
object McpErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // MCP 特定错误代码
    const val INVALID_PROTOCOL_VERSION = -32001
    const val CAPABILITY_NOT_SUPPORTED = -32002
    const val TOOL_NOT_FOUND = -32003
    const val RESOURCE_NOT_FOUND = -32004
    const val PROMPT_NOT_FOUND = -32005
}
