/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import reactor.core.publisher.Mono;

/**
 * 定义模型上下文协议(MCP)的异步传输层。
 *
 * <p>
 * McpTransport接口为在模型上下文协议中实现自定义传输机制提供了基础。
 * 它处理客户端和服务器组件之间的双向通信，使用JSON-RPC格式支持异步消息交换。
 * </p>
 *
 * <p>
 * 此接口的实现负责：
 * </p>
 * <ul>
 * <li>管理传输连接的生命周期</li>
 * <li>处理来自服务器的传入消息和错误</li>
 * <li>向服务器发送出站消息</li>
 * </ul>
 *
 * <p>
 * 传输层设计为协议无关的，允许各种实现，如WebSocket、HTTP或自定义协议。
 * </p>
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public interface McpTransport {

	/**
	 * 关闭传输连接并释放任何相关资源。
	 *
	 * <p>
	 * 当不再需要传输时，此方法确保正确清理资源。
	 * 它应该处理任何活动连接的优雅关闭。
	 * </p>
	 */
	default void close() {
		this.closeGracefully().subscribe();
	}

	/**
	 * 异步关闭传输连接并释放任何相关资源。
	 * @return 当连接关闭时完成的 {@link Mono<Void>}。
	 */
	Mono<Void> closeGracefully();

	/**
	 * 异步向对等方发送消息。
	 *
	 * <p>
	 * 此方法以异步方式处理向服务器发送消息。
	 * 消息按照MCP协议的规定以JSON-RPC格式发送。
	 * </p>
	 * @param message 要发送到服务器的 {@link JSONRPCMessage}
	 * @return 当消息发送完成时完成的 {@link Mono<Void>}
	 */
	Mono<Void> sendMessage(JSONRPCMessage message);

	/**
	 * 将给定数据解组为指定类型的对象。
	 * @param <T> 要解组的对象的类型
	 * @param data 要解组的数据
	 * @param typeRef 要解组的对象的类型引用
	 * @return 解组后的对象
	 */
	<T> T unmarshalFrom(Object data, TypeReference<T> typeRef);

}
