MCP extension for obtaining Java class source code <br>

config:
```
"java_source": {
    "url": "http://localhost:6699/mcp"
}
```

## changelog:
1.0.3-SNAPSHOT:
去掉对 McpServer 插件的依赖，基于netty搭建独立的mcp server，监听端口 8833