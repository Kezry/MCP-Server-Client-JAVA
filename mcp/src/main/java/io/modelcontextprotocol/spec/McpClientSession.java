/*
 * Copyright 2024-2024 原始作者保留所有权利。
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * MCP（模型上下文协议）会话的默认实现，用于管理客户端和服务器之间的双向JSON-RPC通信。
 * 此实现遵循MCP规范进行消息交换和传输处理。
 *
 * <p>
 * 该会话管理：
 * <ul>
 * <li>使用唯一消息ID的请求/响应处理</li>
 * <li>通知处理</li>
 * <li>消息超时管理</li>
 * <li>传输层抽象</li>
 * </ul>
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public class McpClientSession implements McpSession {

	/** 该类的日志记录器 */
	private static final Logger logger = LoggerFactory.getLogger(McpClientSession.class);

	/** 等待请求响应超时的持续时间 */
	private final Duration requestTimeout;

	/** 用于消息交换的传输层实现 */
	private final McpClientTransport transport;

	/** 以请求ID为键的待处理响应映射 */
	private final ConcurrentHashMap<Object, MonoSink<McpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<>();

	/** 以方法名为键的请求处理器映射 */
	private final ConcurrentHashMap<String, RequestHandler<?>> requestHandlers = new ConcurrentHashMap<>();

	/** 以方法名为键的通知处理器映射 */
	private final ConcurrentHashMap<String, NotificationHandler> notificationHandlers = new ConcurrentHashMap<>();

	/** 请求ID的会话特定前缀 */
	private final String sessionPrefix = UUID.randomUUID().toString().substring(0, 8);

	/** 用于生成唯一请求ID的原子计数器 */
	private final AtomicLong requestCounter = new AtomicLong(0);

	private final Disposable connection;

	/**
	 * 用于处理传入JSON-RPC请求的函数式接口。实现类应处理请求参数并返回响应。
	 *
	 * @param <T> 响应类型
	 */
	@FunctionalInterface
	public interface RequestHandler<T> {

		/**
		 * 处理具有给定参数的传入请求。
		 * @param params 请求参数
		 * @return 包含响应对象的Mono
		 */
		Mono<T> handle(Object params);

	}

	/**
	 * 用于处理传入JSON-RPC通知的函数式接口。实现类应处理通知参数而不返回响应。
	 */
	@FunctionalInterface
	public interface NotificationHandler {

		/**
		 * 处理具有给定参数的传入通知。
		 * @param params 通知参数
		 * @return 当通知处理完成时完成的Mono
		 */
		Mono<Void> handle(Object params);

	}

	/**
	 * 使用指定的配置和处理器创建新的McpClientSession。
	 * @param requestTimeout 等待响应的持续时间
	 * @param transport 用于消息交换的传输实现
	 * @param requestHandlers 方法名到请求处理器的映射
	 * @param notificationHandlers 方法名到通知处理器的映射
	 */
	public McpClientSession(Duration requestTimeout, McpClientTransport transport,
			Map<String, RequestHandler<?>> requestHandlers, Map<String, NotificationHandler> notificationHandlers) {

		Assert.notNull(requestTimeout, "The requestTimeout can not be null");
		Assert.notNull(transport, "The transport can not be null");
		Assert.notNull(requestHandlers, "The requestHandlers can not be null");
		Assert.notNull(notificationHandlers, "The notificationHandlers can not be null");

		this.requestTimeout = requestTimeout;
		this.transport = transport;
		this.requestHandlers.putAll(requestHandlers);
		this.notificationHandlers.putAll(notificationHandlers);

		// TODO: consider mono.transformDeferredContextual where the Context contains
		// the
		// Observation associated with the individual message - it can be used to
		// create child Observation and emit it together with the message to the
		// consumer
		this.connection = this.transport.connect(mono -> mono.doOnNext(message -> {
			if (message instanceof McpSchema.JSONRPCResponse response) {
				logger.debug("Received Response: {}", response);
				var sink = pendingResponses.remove(response.id());
				if (sink == null) {
					logger.warn("Unexpected response for unknown id {}", response.id());
				}
				else {
					sink.success(response);
				}
			}
			else if (message instanceof McpSchema.JSONRPCRequest request) {
				logger.debug("Received request: {}", request);
				handleIncomingRequest(request).subscribe(response -> transport.sendMessage(response).subscribe(),
						error -> {
							var errorResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
									null, new McpSchema.JSONRPCResponse.JSONRPCError(
											McpSchema.ErrorCodes.INTERNAL_ERROR, error.getMessage(), null));
							transport.sendMessage(errorResponse).subscribe();
						});
			}
			else if (message instanceof McpSchema.JSONRPCNotification notification) {
				logger.debug("Received notification: {}", notification);
				handleIncomingNotification(notification).subscribe(null,
						error -> logger.error("Error handling notification: {}", error.getMessage()));
			}
		})).subscribe();
	}

	/**
	 * 通过将传入的JSON-RPC请求路由到适当的处理器来处理它。
	 * @param request 传入的JSON-RPC请求
	 * @return 包含JSON-RPC响应的Mono
	 */
	private Mono<McpSchema.JSONRPCResponse> handleIncomingRequest(McpSchema.JSONRPCRequest request) {
		return Mono.defer(() -> {
			var handler = this.requestHandlers.get(request.method());
			if (handler == null) {
				MethodNotFoundError error = getMethodNotFoundError(request.method());
				return Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
						new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND,
								error.message(), error.data())));
			}

			return handler.handle(request.params())
				.map(result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null))
				.onErrorResume(error -> Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
						null, new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
								error.getMessage(), null)))); // TODO: add error message
																// through the data field
		});
	}

	record MethodNotFoundError(String method, String message, Object data) {
	}

	private MethodNotFoundError getMethodNotFoundError(String method) {
		switch (method) {
			case McpSchema.METHOD_ROOTS_LIST:
				return new MethodNotFoundError(method, "Roots not supported",
						Map.of("reason", "Client does not have roots capability"));
			default:
				return new MethodNotFoundError(method, "Method not found: " + method, null);
		}
	}

	/**
	 * 通过将传入的JSON-RPC通知路由到适当的处理器来处理它。
	 * @param notification 传入的JSON-RPC通知
	 * @return 当通知处理完成时完成的Mono
	 */
	private Mono<Void> handleIncomingNotification(McpSchema.JSONRPCNotification notification) {
		return Mono.defer(() -> {
			var handler = notificationHandlers.get(notification.method());
			if (handler == null) {
				logger.error("No handler registered for notification method: {}", notification.method());
				return Mono.empty();
			}
			return handler.handle(notification.params());
		});
	}

	/**
	 * 以非阻塞方式生成唯一的请求ID。将会话特定的前缀与原子计数器组合以确保唯一性。
	 * @return 唯一的请求ID字符串
	 */
	private String generateRequestId() {
		return this.sessionPrefix + "-" + this.requestCounter.getAndIncrement();
	}

	/**
	 * 发送JSON-RPC请求并返回响应。
	 * @param <T> 预期的响应类型
	 * @param method 要调用的方法名
	 * @param requestParams 请求参数
	 * @param typeRef 用于响应反序列化的类型引用
	 * @return 包含响应的Mono
	 */
	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeReference<T> typeRef) {
		String requestId = this.generateRequestId();

		return Mono.<McpSchema.JSONRPCResponse>create(sink -> {
			this.pendingResponses.put(requestId, sink);
			McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method,
					requestId, requestParams);
			this.transport.sendMessage(jsonrpcRequest)
				// TODO: It's most efficient to create a dedicated Subscriber here
				.subscribe(v -> {
				}, error -> {
					this.pendingResponses.remove(requestId);
					sink.error(error);
				});
		}).timeout(this.requestTimeout).handle((jsonRpcResponse, sink) -> {
			if (jsonRpcResponse.error() != null) {
				logger.error("Error handling request: {}", jsonRpcResponse.error());
				sink.error(new McpError(jsonRpcResponse.error()));
			}
			else {
				if (typeRef.getType().equals(Void.class)) {
					sink.complete();
				}
				else {
					sink.next(this.transport.unmarshalFrom(jsonRpcResponse.result(), typeRef));
				}
			}
		});
	}

	/**
	 * 发送JSON-RPC通知。
	 * @param method 通知的方法名
	 * @param params 通知参数
	 * @return 当通知发送完成时完成的Mono
	 */
	@Override
	public Mono<Void> sendNotification(String method, Object params) {
		McpSchema.JSONRPCNotification jsonrpcNotification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				method, params);
		return this.transport.sendMessage(jsonrpcNotification);
	}

	/**
	 * 优雅地关闭会话，允许待处理的操作完成。
	 * @return 当会话关闭时完成的Mono
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			this.connection.dispose();
			return transport.closeGracefully();
		});
	}

	/**
	 * 立即关闭会话，可能会中断待处理的操作。
	 */
	@Override
	public void close() {
		this.connection.dispose();
		transport.close();
	}

}
