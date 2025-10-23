package org.cc.tool.mcp

import kotlinx.serialization.json.JsonElement

interface AbstractTool {
     fun toolInfo(): McpTool
     fun execute(arguments: JsonElement?): String
}