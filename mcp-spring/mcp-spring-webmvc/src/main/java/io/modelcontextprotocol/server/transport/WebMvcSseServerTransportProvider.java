/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.ServerResponse.SseBuilder;

/**
 * 模型上下文协议(MCP)传输层的服务器端实现，使用Spring WebMVC通过HTTP和服务器发送事件(SSE)。
 * 该实现提供了同步WebMVC操作和响应式编程模式之间的桥接，以保持与响应式传输接口的兼容性。
 *
 * <p>
 * 主要特性：
 * <ul>
 * <li>使用HTTP POST实现客户端到服务器的消息传输，使用SSE实现服务器到客户端的消息传输，
 * 从而实现双向通信</li>
 * <li>使用唯一ID管理客户端会话，确保消息可靠传递</li>
 * <li>支持优雅关闭，确保会话正确清理</li>
 * <li>通过配置的端点提供JSON-RPC消息处理</li>
 * <li>包含内置的错误处理和日志记录</li>
 * </ul>
 *
 * <p>
 * 传输层在两个主要端点上运行：
 * <ul>
 * <li>{@code /sse} - 客户端建立事件流连接的SSE端点</li>
 * <li>一个可配置的消息端点，客户端通过HTTP POST发送JSON-RPC消息</li>
 * </ul>
 *
 * <p>
 * 该实现使用{@link ConcurrentHashMap}以线程安全的方式管理多个客户端会话。
 * 每个客户端会话都被分配一个唯一ID并维护自己的SSE连接。
 *
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @see McpServerTransportProvider
 * @see RouterFunction
 */
public class WebMvcSseServerTransportProvider implements McpServerTransportProvider {

	private static final Logger logger = LoggerFactory.getLogger(WebMvcSseServerTransportProvider.class);

	/**
	 * 通过SSE连接发送的JSON-RPC消息的事件类型。
	 */
	public static final String MESSAGE_EVENT_TYPE = "message";

	/**
	 * 用于向客户端发送消息端点URI的事件类型。
	 */
	public static final String ENDPOINT_EVENT_TYPE = "endpoint";

	/**
	 * MCP传输规范中指定的默认SSE端点路径。
	 */
	public static final String DEFAULT_SSE_ENDPOINT = "/sse";

	private final ObjectMapper objectMapper;

	private final String messageEndpoint;

	private final String sseEndpoint;

	private final String baseUrl;

	private final RouterFunction<ServerResponse> routerFunction;

	private McpServerSession.Factory sessionFactory;

	/**
	 * 活动客户端会话的映射，以会话ID为键。
	 */
	private final ConcurrentHashMap<String, McpServerSession> sessions = new ConcurrentHashMap<>();

	/**
	 * 指示传输是否正在关闭的标志。
	 */
	private volatile boolean isClosing = false;

	/**
	 * 使用默认SSE端点构造一个新的WebMvcSseServerTransportProvider实例。
	 * @param objectMapper 用于消息JSON序列化/反序列化的ObjectMapper。
	 * @param messageEndpoint 客户端应通过HTTP POST发送其JSON-RPC消息的端点URI。
	 * 该端点将通过SSE连接的初始端点事件传达给客户端。
	 * @throws IllegalArgumentException 如果objectMapper或messageEndpoint为null
	 */
	public WebMvcSseServerTransportProvider(ObjectMapper objectMapper, String messageEndpoint) {
		this(objectMapper, messageEndpoint, DEFAULT_SSE_ENDPOINT);
	}

	/**
	 * 构造一个新的WebMvcSseServerTransportProvider实例。
	 * @param objectMapper 用于消息JSON序列化/反序列化的ObjectMapper。
	 * @param messageEndpoint 客户端应通过HTTP POST发送其JSON-RPC消息的端点URI。
	 * 该端点将通过SSE连接的初始端点事件传达给客户端。
	 * @param sseEndpoint 客户端建立其SSE连接的端点URI。
	 * @throws IllegalArgumentException 如果任何参数为null
	 */
	public WebMvcSseServerTransportProvider(ObjectMapper objectMapper, String messageEndpoint, String sseEndpoint) {
		this(objectMapper, "", messageEndpoint, sseEndpoint);
	}

	/**
	 * 构造一个新的WebMvcSseServerTransportProvider实例。
	 * @param objectMapper 用于消息JSON序列化/反序列化的ObjectMapper。
	 * @param baseUrl 消息端点的基础URL，用于构造客户端的完整端点URL。
	 * @param messageEndpoint 客户端应通过HTTP POST发送其JSON-RPC消息的端点URI。
	 * 该端点将通过SSE连接的初始端点事件传达给客户端。
	 * @param sseEndpoint 客户端建立其SSE连接的端点URI。
	 * @throws IllegalArgumentException 如果任何参数为null
	 */
	public WebMvcSseServerTransportProvider(ObjectMapper objectMapper, String baseUrl, String messageEndpoint,
			String sseEndpoint) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.notNull(baseUrl, "Message base URL must not be null");
		Assert.notNull(messageEndpoint, "Message endpoint must not be null");
		Assert.notNull(sseEndpoint, "SSE endpoint must not be null");

		this.objectMapper = objectMapper;
		this.baseUrl = baseUrl;
		this.messageEndpoint = messageEndpoint;
		this.sseEndpoint = sseEndpoint;
		this.routerFunction = RouterFunctions.route()
			.GET(this.sseEndpoint, this::handleSseConnection)
			.POST(this.messageEndpoint, this::handleMessage)
			.build();
	}

	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * 通过SSE连接向所有已连接的客户端广播通知。
	 * 消息被序列化为JSON并作为类型为"message"的SSE事件发送。如果
	 * 在向特定客户端发送过程中发生任何错误，这些错误会被记录但不会
	 * 阻止向其他客户端发送。
	 * @param method 通知的方法名
	 * @param params 通知的参数
	 * @return 当广播尝试完成时完成的Mono
	 */
	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		if (sessions.isEmpty()) {
			logger.debug("No active sessions to broadcast message to");
			return Mono.empty();
		}

		logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());

		return Flux.fromIterable(sessions.values())
			.flatMap(session -> session.sendNotification(method, params)
				.doOnError(
						e -> logger.error("Failed to send message to session {}: {}", session.getId(), e.getMessage()))
				.onErrorComplete())
			.then();
	}

	/**
	 * 启动传输的优雅关闭。此方法：
	 * <ul>
	 * <li>设置关闭标志以防止新连接</li>
	 * <li>关闭所有活动的SSE连接</li>
	 * <li>移除所有会话记录</li>
	 * </ul>
	 * @return 当所有清理操作完成时完成的Mono
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Flux.fromIterable(sessions.values()).doFirst(() -> {
			this.isClosing = true;
			logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());
		})
			.flatMap(McpServerSession::closeGracefully)
			.then()
			.doOnSuccess(v -> logger.debug("Graceful shutdown completed"));
	}

	/**
	 * 返回定义此传输HTTP端点的RouterFunction。
	 * 路由函数处理两个端点：
	 * <ul>
	 * <li>GET /sse - 用于建立SSE连接</li>
	 * <li>POST [messageEndpoint] - 用于接收来自客户端的JSON-RPC消息</li>
	 * </ul>
	 * @return 用于处理HTTP请求的配置RouterFunction
	 */
	public RouterFunction<ServerResponse> getRouterFunction() {
		return this.routerFunction;
	}

	/**
	 * 通过创建新会话并建立SSE连接来处理来自客户端的新SSE连接请求。此方法：
	 * <ul>
	 * <li>生成唯一的会话ID</li>
	 * <li>使用WebMvcMcpSessionTransport创建新会话</li>
	 * <li>发送初始端点事件以通知客户端在哪里发送消息</li>
	 * <li>在会话映射中维护会话</li>
	 * </ul>
	 * @param request 传入的服务器请求
	 * @return 配置用于SSE通信的ServerResponse，如果服务器正在关闭或连接失败则返回错误响应
	 */
	private ServerResponse handleSseConnection(ServerRequest request) {
		if (this.isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		String sessionId = UUID.randomUUID().toString();
		logger.debug("Creating new SSE connection for session: {}", sessionId);

		// Send initial endpoint event
		try {
			return ServerResponse.sse(sseBuilder -> {
				sseBuilder.onComplete(() -> {
					logger.debug("SSE connection completed for session: {}", sessionId);
					sessions.remove(sessionId);
				});
				sseBuilder.onTimeout(() -> {
					logger.debug("SSE connection timed out for session: {}", sessionId);
					sessions.remove(sessionId);
				});

				WebMvcMcpSessionTransport sessionTransport = new WebMvcMcpSessionTransport(sessionId, sseBuilder);
				McpServerSession session = sessionFactory.create(sessionTransport);
				this.sessions.put(sessionId, session);

				try {
					sseBuilder.id(sessionId)
						.event(ENDPOINT_EVENT_TYPE)
						.data(this.baseUrl + this.messageEndpoint + "?sessionId=" + sessionId);
				}
				catch (Exception e) {
					logger.error("Failed to send initial endpoint event: {}", e.getMessage());
					sseBuilder.error(e);
				}
			}, Duration.ZERO);
		}
		catch (Exception e) {
			logger.error("Failed to send initial endpoint event to session {}: {}", sessionId, e.getMessage());
			sessions.remove(sessionId);
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}

	/**
	 * 处理来自客户端的传入JSON-RPC消息。此方法：
	 * <ul>
	 * <li>将请求体反序列化为JSON-RPC消息</li>
	 * <li>通过会话的handle方法处理消息</li>
	 * <li>根据处理结果返回适当的HTTP响应</li>
	 * </ul>
	 * @param request 包含JSON-RPC消息的传入服务器请求
	 * @return 表示成功(200 OK)的ServerResponse，或在失败情况下带有错误详情的适当错误状态
	 */
	private ServerResponse handleMessage(ServerRequest request) {
		if (this.isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		if (request.param("sessionId").isEmpty()) {
			return ServerResponse.badRequest().body(new McpError("Session ID missing in message endpoint"));
		}

		String sessionId = request.param("sessionId").get();
		McpServerSession session = sessions.get(sessionId);

		if (session == null) {
			return ServerResponse.status(HttpStatus.NOT_FOUND).body(new McpError("Session not found: " + sessionId));
		}

		try {
			String body = request.body(String.class);
			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);

			// Process the message through the session's handle method
			session.handle(message).block(); // Block for WebMVC compatibility

			return ServerResponse.ok().build();
		}
		catch (IllegalArgumentException | IOException e) {
			logger.error("Failed to deserialize message: {}", e.getMessage());
			return ServerResponse.badRequest().body(new McpError("Invalid message format"));
		}
		catch (Exception e) {
			logger.error("Error handling message: {}", e.getMessage());
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new McpError(e.getMessage()));
		}
	}

	/**
	 * WebMVC SSE会话的McpServerTransport实现。此类处理
	 * 特定客户端会话的传输层通信。
	 */
	private class WebMvcMcpSessionTransport implements McpServerTransport {

		private final String sessionId;

		private final SseBuilder sseBuilder;

		/**
		 * 使用指定的ID和SSE构建器创建新的会话传输。
		 * @param sessionId 此会话的唯一标识符
		 * @param sseBuilder 用于向客户端发送服务器事件的SSE构建器
		 */
		WebMvcMcpSessionTransport(String sessionId, SseBuilder sseBuilder) {
			this.sessionId = sessionId;
			this.sseBuilder = sseBuilder;
			logger.debug("Session transport {} initialized with SSE builder", sessionId);
		}

		/**
		 * 通过SSE连接向客户端发送JSON-RPC消息。
		 * @param message 要发送的JSON-RPC消息
		 * @return 当消息发送完成时完成的Mono
		 */
		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return Mono.fromRunnable(() -> {
				try {
					String jsonText = objectMapper.writeValueAsString(message);
					sseBuilder.id(sessionId).event(MESSAGE_EVENT_TYPE).data(jsonText);
					logger.debug("Message sent to session {}", sessionId);
				}
				catch (Exception e) {
					logger.error("Failed to send message to session {}: {}", sessionId, e.getMessage());
					sseBuilder.error(e);
				}
			});
		}

		/**
		 * 使用配置的ObjectMapper将数据从一种类型转换为另一种类型。
		 * @param data 要转换的源数据对象
		 * @param typeRef 目标类型引用
		 * @return 类型T的转换后对象
		 * @param <T> 目标类型
		 */
		@Override
		public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
			return objectMapper.convertValue(data, typeRef);
		}

		/**
		 * 启动传输的优雅关闭。
		 * @return 当关闭完成时完成的Mono
		 */
		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(() -> {
				logger.debug("Closing session transport: {}", sessionId);
				try {
					sseBuilder.complete();
					logger.debug("Successfully completed SSE builder for session {}", sessionId);
				}
				catch (Exception e) {
					logger.warn("Failed to complete SSE builder for session {}: {}", sessionId, e.getMessage());
				}
			});
		}

		/**
		 * 立即关闭传输。
		 */
		@Override
		public void close() {
			try {
				sseBuilder.complete();
				logger.debug("Successfully completed SSE builder for session {}", sessionId);
			}
			catch (Exception e) {
				logger.warn("Failed to complete SSE builder for session {}: {}", sessionId, e.getMessage());
			}
		}

	}

}
