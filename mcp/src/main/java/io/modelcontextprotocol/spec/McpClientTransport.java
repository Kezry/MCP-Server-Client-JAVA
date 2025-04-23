/*
 * Copyright 2024 - 2024 原始作者保留所有权利。
 */
package io.modelcontextprotocol.spec;

import java.util.function.Function;

import reactor.core.publisher.Mono;

/**
 * MCP 客户端传输层的标记接口。
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public interface McpClientTransport extends McpTransport {

	Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler);

}
