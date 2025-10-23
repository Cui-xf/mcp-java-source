package org.cc.tool.mcp

import com.intellij.openapi.diagnostic.logger
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.cors.CorsConfigBuilder
import io.netty.handler.codec.http.cors.CorsHandler
import org.cc.tool.PluginEntryPoint
import java.nio.charset.StandardCharsets

object McpServer {
    private val logger by lazy { logger<PluginEntryPoint>() }
    private lateinit var serverChannel: Channel

    fun start() {
        val group = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())

        // 配置 CORS
        val corsConfig = CorsConfigBuilder.forAnyOrigin()
            .allowNullOrigin()
            .allowCredentials()
            .allowedRequestMethods(HttpMethod.GET, HttpMethod.POST, HttpMethod.OPTIONS)
            .allowedRequestHeaders("*")
            .maxAge(3600)
            .build()

        serverChannel = ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel::class.java)
            .option(ChannelOption.SO_REUSEADDR, true)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast(HttpServerCodec())
                        .addLast(HttpObjectAggregator(65536)) // 增加缓冲区大小
                        .addLast(CorsHandler(corsConfig))
                        .addLast(object : SimpleChannelInboundHandler<FullHttpRequest>() {
                            override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
                                handleHttpRequest(ctx, request)
                            }

                            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                                logger.error("处理请求时发生异常", cause)
                                ctx.close()
                            }
                        })
                }
            })
            .bind(6699)
            .sync()
            .addListener { future ->
                if (future.isSuccess) {
                    logger.warn("MCP Server 已启动，监听端口 6699")
                } else {
                    logger.error("MCP Server 启动失败", future.cause())
                }
            }
            .channel()

        serverChannel
            .closeFuture()
            .addListener {
                group.shutdownGracefully()
                logger.warn("MCP Server 关闭成功")
            }

        Runtime.getRuntime().addShutdownHook(Thread({
            group.shutdownGracefully()
            serverChannel.close()

        }))
    }

    fun stop() {
        if (::serverChannel.isInitialized) {
            serverChannel.close()
        }
    }

    private fun handleHttpRequest(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        try {
            when (request.method()) {
                HttpMethod.OPTIONS -> {
                    // 处理 CORS 预检请求
                    val response = DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.OK)
                    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS")
                    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type")
                    ctx.writeAndFlush(response)
                }

                HttpMethod.POST -> {
                    handleMcpRequest(ctx, request)
                }

                HttpMethod.GET -> {
                    // 返回服务器信息
                    val response = DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.OK)
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")

                    val serverInfo = """
                        {
                            "name": "MCP Java Source Server",
                            "version": "1.0.2-SNAPSHOT",
                            "protocol": "MCP",
                            "endpoints": {
                                "mcp": "/mcp"
                            }
                        }
                    """.trimIndent()

                    val content = response.content()
                    content.writeCharSequence(serverInfo, StandardCharsets.UTF_8)
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())

                    ctx.writeAndFlush(response)
                }

                else -> {
                    val response =
                        DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.METHOD_NOT_ALLOWED)
                    ctx.writeAndFlush(response)
                }
            }
        } catch (e: Exception) {
            logger.error("处理 HTTP 请求时发生错误", e)
            val response = DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.INTERNAL_SERVER_ERROR)
            ctx.writeAndFlush(response)
        }
    }

    private fun handleMcpRequest(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        try {
            val content = request.content()
            val requestBody = content.toString(StandardCharsets.UTF_8)

            logger.info("收到 MCP 请求: $requestBody")

            // 处理 MCP 请求
            val responseBody = McpServerHandler.handleRequest(requestBody)

            val response = DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.OK)
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json")
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")

            if (responseBody != null) {
                val responseContent = response.content()
                responseContent.writeCharSequence(responseBody, StandardCharsets.UTF_8)
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseContent.readableBytes())
                logger.info("MCP 响应: $responseBody")
            } else {
                // 通知消息，不需要响应
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
            }

            ctx.writeAndFlush(response)
        } catch (e: Exception) {
            logger.error("处理 MCP 请求时发生错误", e)
            val response = DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.INTERNAL_SERVER_ERROR)
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json")
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")

            val errorResponse = """
                {
                    "jsonrpc": "2.0",
                    "id": null,
                    "error": {
                        "code": -32603,
                        "message": "内部服务器错误: ${e.message}"
                    }
                }
            """.trimIndent()

            val content = response.content()
            content.writeCharSequence(errorResponse, StandardCharsets.UTF_8)
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())

            ctx.writeAndFlush(response)
        }
    }
}