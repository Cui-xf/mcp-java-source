package org.cc.tool

import com.intellij.ide.AppLifecycleListener
import org.cc.tool.mcp.McpServer

class PluginEntryPoint : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String?>) {
        McpServer.start()
    }

    override fun appClosing() {
        McpServer.stop()
    }
}