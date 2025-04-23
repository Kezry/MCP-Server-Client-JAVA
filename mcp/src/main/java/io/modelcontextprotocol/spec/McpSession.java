/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import reactor.core.publisher.Mono;

/**
 * 表示处理客户端和服务器之间通信的模型控制协议(MCP)会话。
 * 此接口提供了发送请求和通知的方法，以及管理会话生命周期。
 *
 * <p>
 * 会话使用Project Reactor的{@link Mono}类型进行异步操作。
 * 它支持请求-响应模式和单向通知。
 * </p>
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public interface McpSession {

	/**
	 * 向模型对方发送请求并期望类型为T的响应。
	 *
	 * <p>
	 * 此方法处理请求-响应模式，其中期望从客户端或服务器获得响应。
	 * 响应类型由提供的TypeReference确定。
	 * </p>
	 * @param <T> 预期响应的类型
	 * @param method 要在对方调用的方法名称
	 * @param requestParams 要与请求一起发送的参数
	 * @param typeRef 描述预期响应类型的TypeReference
	 * @return 在收到响应时发出响应的Mono
	 */
	<T> Mono<T> sendRequest(String method, Object requestParams, TypeReference<T> typeRef);

	/**
	 * 向模型客户端或服务器发送不带参数的通知。
	 *
	 * <p>
	 * 此方法实现了通知模式，其中不期望从对方获得响应。
	 * 它适用于发送后不需要关注结果的场景。
	 * </p>
	 * @param method 要在服务器上调用的通知方法的名称
	 * @return 当通知发送完成时完成的Mono
	 */
	default Mono<Void> sendNotification(String method) {
		return sendNotification(method, null);
	}

	/**
	 * 向模型客户端或服务器发送带参数的通知。
	 *
	 * <p>
	 * 类似于{@link #sendNotification(String)}，但允许随通知发送额外的参数。
	 * </p>
	 * @param method 要发送给对方的通知方法的名称
	 * @param params 要随通知一起发送的参数
	 * @return 当通知发送完成时完成的Mono
	 */
	Mono<Void> sendNotification(String method, Object params);

	/**
	 * 异步关闭会话并释放任何相关资源。
	 * @return 当会话关闭时完成的{@link Mono<Void>}。
	 */
	Mono<Void> closeGracefully();

	/**
	 * 关闭会话并释放任何相关资源。
	 */
	void close();

}
