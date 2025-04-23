package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Sinks;

/**
 * 表示服务器端的模型控制协议(MCP)会话。它管理与客户端的双向JSON-RPC通信。
 */
public class McpServerSession implements McpSession {

	private static final Logger logger = LoggerFactory.getLogger(McpServerSession.class);

	private final ConcurrentHashMap<Object, MonoSink<McpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<>();

	private final String id;

	/** 等待请求响应超时的持续时间 */
	private final Duration requestTimeout;

	private final AtomicLong requestCounter = new AtomicLong(0);

	private final InitRequestHandler initRequestHandler;

	private final InitNotificationHandler initNotificationHandler;

	private final Map<String, RequestHandler<?>> requestHandlers;

	private final Map<String, NotificationHandler> notificationHandlers;

	private final McpServerTransport transport;

	private final Sinks.One<McpAsyncServerExchange> exchangeSink = Sinks.one();

	private final AtomicReference<McpSchema.ClientCapabilities> clientCapabilities = new AtomicReference<>();

	private final AtomicReference<McpSchema.Implementation> clientInfo = new AtomicReference<>();

	private static final int STATE_UNINITIALIZED = 0;

	private static final int STATE_INITIALIZING = 1;

	private static final int STATE_INITIALIZED = 2;

	private final AtomicInteger state = new AtomicInteger(STATE_UNINITIALIZED);

	/**
	 * 使用给定的参数和传输创建新的服务器会话。
	 * @param id 会话ID
	 * @param transport 要使用的传输
	 * @param initHandler 当服务器收到
	 * {@link io.modelcontextprotocol.spec.McpSchema.InitializeRequest} 时调用
	 * @param initNotificationHandler 当收到
	 * {@link McpSchema.METHOD_NOTIFICATION_INITIALIZED} 时调用
	 * @param requestHandlers 要使用的请求处理器映射
	 * @param notificationHandlers 要使用的通知处理器映射
	 */
	public McpServerSession(String id, Duration requestTimeout, McpServerTransport transport,
			InitRequestHandler initHandler, InitNotificationHandler initNotificationHandler,
			Map<String, RequestHandler<?>> requestHandlers, Map<String, NotificationHandler> notificationHandlers) {
		this.id = id;
		this.requestTimeout = requestTimeout;
		this.transport = transport;
		this.initRequestHandler = initHandler;
		this.initNotificationHandler = initNotificationHandler;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
	}

	/**
	 * 获取会话ID。
	 * @return 会话ID
	 */
	public String getId() {
		return this.id;
	}

	/**
	 * 在客户端和服务器之间的初始化序列成功完成时调用，包含客户端的功能和信息。
	 *
	 * <a href=
	 * "https://github.com/modelcontextprotocol/specification/blob/main/docs/specification/basic/lifecycle.md#initialization">初始化
	 * 规范</a>
	 * @param clientCapabilities 已连接客户端提供的功能
	 * @param clientInfo 关于已连接客户端的信息
	 */
	public void init(McpSchema.ClientCapabilities clientCapabilities, McpSchema.Implementation clientInfo) {
		this.clientCapabilities.lazySet(clientCapabilities);
		this.clientInfo.lazySet(clientInfo);
	}

	private String generateRequestId() {
		return this.id + "-" + this.requestCounter.getAndIncrement();
	}

	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeReference<T> typeRef) {
		String requestId = this.generateRequestId();

		return Mono.<McpSchema.JSONRPCResponse>create(sink -> {
			this.pendingResponses.put(requestId, sink);
			McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method,
					requestId, requestParams);
			this.transport.sendMessage(jsonrpcRequest).subscribe(v -> {
			}, error -> {
				this.pendingResponses.remove(requestId);
				sink.error(error);
			});
		}).timeout(requestTimeout).handle((jsonRpcResponse, sink) -> {
			if (jsonRpcResponse.error() != null) {
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

	@Override
	public Mono<Void> sendNotification(String method, Object params) {
		McpSchema.JSONRPCNotification jsonrpcNotification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				method, params);
		return this.transport.sendMessage(jsonrpcNotification);
	}

	/**
	 * 当会话确定后由{@link McpServerTransportProvider}调用。
	 * 此方法的目的是将消息分发给适当的处理器，这些处理器由MCP服务器实现
	 * ({@link io.modelcontextprotocol.server.McpAsyncServer}或
	 * {@link io.modelcontextprotocol.server.McpSyncServer})通过
	 * 服务器创建的{@link McpServerSession.Factory}指定。
	 * @param message 传入的JSON-RPC消息
	 * @return 当消息处理完成时完成的Mono
	 */
	public Mono<Void> handle(McpSchema.JSONRPCMessage message) {
		return Mono.defer(() -> {
			// TODO handle errors for communication to without initialization happening
			// first
			if (message instanceof McpSchema.JSONRPCResponse response) {
				logger.debug("Received Response: {}", response);
				var sink = pendingResponses.remove(response.id());
				if (sink == null) {
					logger.warn("Unexpected response for unknown id {}", response.id());
				}
				else {
					sink.success(response);
				}
				return Mono.empty();
			}
			else if (message instanceof McpSchema.JSONRPCRequest request) {
				logger.debug("Received request: {}", request);
				return handleIncomingRequest(request).onErrorResume(error -> {
					var errorResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
							new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
									error.getMessage(), null));
					// TODO: Should the error go to SSE or back as POST return?
					return this.transport.sendMessage(errorResponse).then(Mono.empty());
				}).flatMap(this.transport::sendMessage);
			}
			else if (message instanceof McpSchema.JSONRPCNotification notification) {
				// TODO handle errors for communication to without initialization
				// happening first
				logger.debug("Received notification: {}", notification);
				// TODO: in case of error, should the POST request be signalled?
				return handleIncomingNotification(notification)
					.doOnError(error -> logger.error("Error handling notification: {}", error.getMessage()));
			}
			else {
				logger.warn("Received unknown message type: {}", message);
				return Mono.empty();
			}
		});
	}

	/**
	 * 通过路由到适当的处理器来处理传入的JSON-RPC请求。
	 * @param request 传入的JSON-RPC请求
	 * @return 包含JSON-RPC响应的Mono
	 */
	private Mono<McpSchema.JSONRPCResponse> handleIncomingRequest(McpSchema.JSONRPCRequest request) {
		return Mono.defer(() -> {
			Mono<?> resultMono;
			if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
				// TODO handle situation where already initialized!
				McpSchema.InitializeRequest initializeRequest = transport.unmarshalFrom(request.params(),
						new TypeReference<McpSchema.InitializeRequest>() {
						});

				this.state.lazySet(STATE_INITIALIZING);
				this.init(initializeRequest.capabilities(), initializeRequest.clientInfo());
				resultMono = this.initRequestHandler.handle(initializeRequest);
			}
			else {
				// TODO handle errors for communication to this session without
				// initialization happening first
				var handler = this.requestHandlers.get(request.method());
				if (handler == null) {
					MethodNotFoundError error = getMethodNotFoundError(request.method());
					return Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
							new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND,
									error.message(), error.data())));
				}

				resultMono = this.exchangeSink.asMono().flatMap(exchange -> handler.handle(exchange, request.params()));
			}
			return resultMono
				.map(result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null))
				.onErrorResume(error -> Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
						null, new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
								error.getMessage(), null)))); // TODO: add error message
																// through the data field
		});
	}

	/**
	 * 通过路由到适当的处理器来处理传入的JSON-RPC通知。
	 * @param notification 传入的JSON-RPC通知
	 * @return 当通知处理完成时完成的Mono
	 */
	private Mono<Void> handleIncomingNotification(McpSchema.JSONRPCNotification notification) {
		return Mono.defer(() -> {
			if (McpSchema.METHOD_NOTIFICATION_INITIALIZED.equals(notification.method())) {
				this.state.lazySet(STATE_INITIALIZED);
				exchangeSink.tryEmitValue(new McpAsyncServerExchange(this, clientCapabilities.get(), clientInfo.get()));
				return this.initNotificationHandler.handle();
			}

			var handler = notificationHandlers.get(notification.method());
			if (handler == null) {
				logger.error("No handler registered for notification method: {}", notification.method());
				return Mono.empty();
			}
			return this.exchangeSink.asMono().flatMap(exchange -> handler.handle(exchange, notification.params()));
		});
	}

	record MethodNotFoundError(String method, String message, Object data) {
	}

	private MethodNotFoundError getMethodNotFoundError(String method) {
		return new MethodNotFoundError(method, "Method not found: " + method, null);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return this.transport.closeGracefully();
	}

	@Override
	public void close() {
		this.transport.close();
	}

	/**
	 * 初始化请求的请求处理器。
	 */
	public interface InitRequestHandler {

		/**
		 * 处理初始化请求。
		 * @param initializeRequest 客户端的初始化请求
		 * @return 将发出初始化结果的Mono
		 */
		Mono<McpSchema.InitializeResult> handle(McpSchema.InitializeRequest initializeRequest);

	}

	/**
	 * 客户端初始化通知的通知处理器。
	 */
	public interface InitNotificationHandler {

		/**
		 * 指定初始化成功时要采取的操作。
		 * @return 当初始化操作完成时完成的Mono。
		 */
		Mono<Void> handle();

	}

	/**
	 * 客户端发起的通知的处理器。
	 */
	public interface NotificationHandler {

		/**
		 * 处理来自客户端的通知。
		 * @param exchange 与客户端关联的交换对象，允许回调已连接的客户端或检查其功能。
		 * @param params 通知的参数。
		 * @return 当通知处理完成时完成的Mono。
		 */
		Mono<Void> handle(McpAsyncServerExchange exchange, Object params);

	}

	/**
	 * 客户端发起的请求的处理器。
	 *
	 * @param <T> 处理请求后预期的响应类型。
	 */
	public interface RequestHandler<T> {

		/**
		 * 处理来自客户端的请求。
		 * @param exchange 与客户端关联的交换对象，允许回调已连接的客户端或检查其功能。
		 * @param params 请求的参数。
		 * @return 将发出请求响应的Mono。
		 */
		Mono<T> handle(McpAsyncServerExchange exchange, Object params);

	}

	/**
	 * 用于创建服务器会话的工厂，这些会话委托给与已连接客户端的1:1传输。
	 */
	@FunctionalInterface
	public interface Factory {

		/**
		 * 创建客户端-服务器交互的新的1:1表示。
		 * @param sessionTransport 用于与客户端通信的传输。
		 * @return 一个新的服务器会话。
		 */
		McpServerSession create(McpServerTransport sessionTransport);

	}

}
