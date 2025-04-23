/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.util.Assert;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 基于Servlet的MCP HTTP和服务器发送事件(SSE)传输规范的实现。
 * 该实现提供了与WebFluxSseServerTransportProvider类似的功能，
 * 但使用传统的Servlet API而不是WebFlux。
 *
 * <p>
 * 该传输处理两种类型的端点：
 * <ul>
 * <li>SSE端点(/sse) - 建立用于服务器到客户端事件的长连接</li>
 * <li>消息端点(可配置) - 处理客户端到服务器的消息请求</li>
 * </ul>
 *
 * <p>
 * 特性：
 * <ul>
 * <li>使用Servlet 6.0异步支持进行异步消息处理</li>
 * <li>多客户端连接的会话管理</li>
 * <li>支持优雅关闭</li>
 * <li>错误处理和响应格式化</li>
 * </ul>
 *
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @see McpServerTransportProvider
 * @see HttpServlet
 */

@WebServlet(asyncSupported = true)
public class HttpServletSseServerTransportProvider extends HttpServlet implements McpServerTransportProvider {

	/** 该类的日志记录器 */
	private static final Logger logger = LoggerFactory.getLogger(HttpServletSseServerTransportProvider.class);

	public static final String UTF_8 = "UTF-8";

	public static final String APPLICATION_JSON = "application/json";

	public static final String FAILED_TO_SEND_ERROR_RESPONSE = "Failed to send error response: {}";

	/** SSE连接的默认端点路径 */
	public static final String DEFAULT_SSE_ENDPOINT = "/sse";

	/** 常规消息的事件类型 */
	public static final String MESSAGE_EVENT_TYPE = "message";

	/** 端点信息的事件类型 */
	public static final String ENDPOINT_EVENT_TYPE = "endpoint";

	public static final String DEFAULT_BASE_URL = "";

	/** 用于序列化/反序列化的JSON对象映射器 */
	private final ObjectMapper objectMapper;

	/** 服务器传输的基础URL */
	private final String baseUrl;

	/** 处理客户端消息的端点路径 */
	private final String messageEndpoint;

	/** 处理SSE连接的端点路径 */
	private final String sseEndpoint;

	/** 活动客户端会话的映射，以会话ID为键 */
	private final Map<String, McpServerSession> sessions = new ConcurrentHashMap<>();

	/** 指示传输是否正在关闭的标志 */
	private final AtomicBoolean isClosing = new AtomicBoolean(false);

	/** 用于创建新会话的会话工厂 */
	private McpServerSession.Factory sessionFactory;

	/**
	 * 使用自定义SSE端点创建新的HttpServletSseServerTransportProvider实例。
	 * @param objectMapper 用于消息序列化/反序列化的JSON对象映射器
	 * @param messageEndpoint 客户端发送消息的端点路径
	 * @param sseEndpoint 客户端建立SSE连接的端点路径
	 */
	public HttpServletSseServerTransportProvider(ObjectMapper objectMapper, String messageEndpoint,
			String sseEndpoint) {
		this(objectMapper, DEFAULT_BASE_URL, messageEndpoint, sseEndpoint);
	}

	/**
	 * 使用自定义SSE端点创建新的HttpServletSseServerTransportProvider实例。
	 * @param objectMapper 用于消息序列化/反序列化的JSON对象映射器
	 * @param baseUrl 服务器传输的基础URL
	 * @param messageEndpoint 客户端发送消息的端点路径
	 * @param sseEndpoint 客户端建立SSE连接的端点路径
	 */
	public HttpServletSseServerTransportProvider(ObjectMapper objectMapper, String baseUrl, String messageEndpoint,
			String sseEndpoint) {
		this.objectMapper = objectMapper;
		this.baseUrl = baseUrl;
		this.messageEndpoint = messageEndpoint;
		this.sseEndpoint = sseEndpoint;
	}

	/**
	 * 使用默认SSE端点创建新的HttpServletSseServerTransportProvider实例。
	 * @param objectMapper 用于消息序列化/反序列化的JSON对象映射器
	 * @param messageEndpoint 客户端发送消息的端点路径
	 */
	public HttpServletSseServerTransportProvider(ObjectMapper objectMapper, String messageEndpoint) {
		this(objectMapper, messageEndpoint, DEFAULT_SSE_ENDPOINT);
	}

	/**
	 * Sets the session factory for creating new sessions.
	 * @param sessionFactory The session factory to use
	 */
	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * Broadcasts a notification to all connected clients.
	 * @param method The method name for the notification
	 * @param params The parameters for the notification
	 * @return A Mono that completes when the broadcast attempt is finished
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
	 * 处理建立SSE连接的GET请求。
	 * <p>
	 * 当客户端连接到SSE端点时，此方法设置新的SSE连接。
	 * 它配置SSE的响应头，创建新会话，并向客户端发送初始端点信息。
	 * @param request HTTP servlet请求
	 * @param response HTTP servlet响应
	 * @throws ServletException 如果发生servlet特定错误
	 * @throws IOException 如果发生I/O错误
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String requestURI = request.getRequestURI();
		if (!requestURI.endsWith(sseEndpoint)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		if (isClosing.get()) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		response.setContentType("text/event-stream");
		response.setCharacterEncoding(UTF_8);
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("Connection", "keep-alive");
		response.setHeader("Access-Control-Allow-Origin", "*");

		String sessionId = UUID.randomUUID().toString();
		AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(0);

		PrintWriter writer = response.getWriter();

		// 创建新的会话传输
		HttpServletMcpSessionTransport sessionTransport = new HttpServletMcpSessionTransport(sessionId, asyncContext,
				writer);

		// 使用会话工厂创建新会话
		McpServerSession session = sessionFactory.create(sessionTransport);
		this.sessions.put(sessionId, session);

		// 发送初始端点事件
		this.sendEvent(writer, ENDPOINT_EVENT_TYPE, this.baseUrl + this.messageEndpoint + "?sessionId=" + sessionId);
	}

	/**
	 * 处理客户端消息的POST请求。
	 * <p>
	 * 此方法处理来自客户端的传入消息，通过会话处理程序路由它们，
	 * 并发送适当的响应。它处理错误情况，并根据MCP规范格式化错误响应。
	 * @param request HTTP servlet请求
	 * @param response HTTP servlet响应
	 * @throws ServletException 如果发生servlet特定错误
	 * @throws IOException 如果发生I/O错误
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		if (isClosing.get()) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		String requestURI = request.getRequestURI();
		if (!requestURI.endsWith(messageEndpoint)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		// 从请求参数获取会话ID
		String sessionId = request.getParameter("sessionId");
		if (sessionId == null) {
			response.setContentType(APPLICATION_JSON);
			response.setCharacterEncoding(UTF_8);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			String jsonError = objectMapper.writeValueAsString(new McpError("Session ID missing in message endpoint"));
			PrintWriter writer = response.getWriter();
			writer.write(jsonError);
			writer.flush();
			return;
		}

		// 从会话映射中获取会话
		McpServerSession session = sessions.get(sessionId);
		if (session == null) {
			response.setContentType(APPLICATION_JSON);
			response.setCharacterEncoding(UTF_8);
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			String jsonError = objectMapper.writeValueAsString(new McpError("Session not found: " + sessionId));
			PrintWriter writer = response.getWriter();
			writer.write(jsonError);
			writer.flush();
			return;
		}

		try {
			BufferedReader reader = request.getReader();
			StringBuilder body = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				body.append(line);
			}

			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body.toString());

			// 通过会话的handle方法处理消息
			session.handle(message).block(); // 为Servlet兼容性而阻塞

			response.setStatus(HttpServletResponse.SC_OK);
		}
		catch (Exception e) {
			logger.error("Error processing message: {}", e.getMessage());
			try {
				McpError mcpError = new McpError(e.getMessage());
				response.setContentType(APPLICATION_JSON);
				response.setCharacterEncoding(UTF_8);
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				String jsonError = objectMapper.writeValueAsString(mcpError);
				PrintWriter writer = response.getWriter();
				writer.write(jsonError);
				writer.flush();
			}
			catch (IOException ex) {
				logger.error(FAILED_TO_SEND_ERROR_RESPONSE, ex.getMessage());
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error processing message");
			}
		}
	}

	/**
	 * Initiates a graceful shutdown of the transport.
	 * <p>
	 * This method marks the transport as closing and closes all active client sessions.
	 * New connection attempts will be rejected during shutdown.
	 * @return A Mono that completes when all sessions have been closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		isClosing.set(true);
		logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());

		return Flux.fromIterable(sessions.values()).flatMap(McpServerSession::closeGracefully).then();
	}

	/**
	 * 向客户端发送SSE事件。
	 * @param writer 用于发送事件的写入器
	 * @param eventType 事件类型（消息或端点）
	 * @param data 事件数据
	 * @throws IOException 如果在写入事件时发生错误
	 */
	private void sendEvent(PrintWriter writer, String eventType, String data) throws IOException {
		writer.write("event: " + eventType + "\n");
		writer.write("data: " + data + "\n\n");
		writer.flush();

		if (writer.checkError()) {
			throw new IOException("Client disconnected");
		}
	}

	/**
	 * 在servlet被销毁时清理资源。
	 * <p>
	 * 此方法通过在调用父类的destroy方法之前关闭所有客户端连接
	 * 来确保优雅关闭。
	 */
	@Override
	public void destroy() {
		closeGracefully().block();
		super.destroy();
	}

	/**
	 * HttpServlet SSE会话的McpServerTransport实现。
	 * 此类处理特定客户端会话的传输层通信。
	 */
	private class HttpServletMcpSessionTransport implements McpServerTransport {

		private final String sessionId;

		private final AsyncContext asyncContext;

		private final PrintWriter writer;

		/**
		 * Creates a new session transport with the specified ID and SSE writer.
		 * @param sessionId The unique identifier for this session
		 * @param asyncContext The async context for the session
		 * @param writer The writer for sending server events to the client
		 */
		HttpServletMcpSessionTransport(String sessionId, AsyncContext asyncContext, PrintWriter writer) {
			this.sessionId = sessionId;
			this.asyncContext = asyncContext;
			this.writer = writer;
			logger.debug("Session transport {} initialized with SSE writer", sessionId);
		}

		/**
		 * Sends a JSON-RPC message to the client through the SSE connection.
		 * @param message The JSON-RPC message to send
		 * @return A Mono that completes when the message has been sent
		 */
		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return Mono.fromRunnable(() -> {
				try {
					String jsonText = objectMapper.writeValueAsString(message);
					sendEvent(writer, MESSAGE_EVENT_TYPE, jsonText);
					logger.debug("Message sent to session {}", sessionId);
				}
				catch (Exception e) {
					logger.error("Failed to send message to session {}: {}", sessionId, e.getMessage());
					sessions.remove(sessionId);
					asyncContext.complete();
				}
			});
		}

		/**
		 * Converts data from one type to another using the configured ObjectMapper.
		 * @param data The source data object to convert
		 * @param typeRef The target type reference
		 * @return The converted object of type T
		 * @param <T> The target type
		 */
		@Override
		public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
			return objectMapper.convertValue(data, typeRef);
		}

		/**
		 * Initiates a graceful shutdown of the transport.
		 * @return A Mono that completes when the shutdown is complete
		 */
		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(() -> {
				logger.debug("Closing session transport: {}", sessionId);
				try {
					sessions.remove(sessionId);
					asyncContext.complete();
					logger.debug("Successfully completed async context for session {}", sessionId);
				}
				catch (Exception e) {
					logger.warn("Failed to complete async context for session {}: {}", sessionId, e.getMessage());
				}
			});
		}

		/**
		 * Closes the transport immediately.
		 */
		@Override
		public void close() {
			try {
				sessions.remove(sessionId);
				asyncContext.complete();
				logger.debug("Successfully completed async context for session {}", sessionId);
			}
			catch (Exception e) {
				logger.warn("Failed to complete async context for session {}: {}", sessionId, e.getMessage());
			}
		}

	}

	/**
	 * Creates a new Builder instance for configuring and creating instances of
	 * HttpServletSseServerTransportProvider.
	 * @return A new Builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of HttpServletSseServerTransportProvider.
	 * <p>
	 * This builder provides a fluent API for configuring and creating instances of
	 * HttpServletSseServerTransportProvider with custom settings.
	 */
	public static class Builder {

		private ObjectMapper objectMapper = new ObjectMapper();

		private String baseUrl = DEFAULT_BASE_URL;

		private String messageEndpoint;

		private String sseEndpoint = DEFAULT_SSE_ENDPOINT;

		/**
		 * Sets the JSON object mapper to use for message serialization/deserialization.
		 * @param objectMapper The object mapper to use
		 * @return This builder instance for method chaining
		 */
		public Builder objectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "ObjectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		/**
		 * Sets the base URL for the server transport.
		 * @param baseUrl The base URL to use
		 * @return This builder instance for method chaining
		 */
		public Builder baseUrl(String baseUrl) {
			Assert.notNull(baseUrl, "Base URL must not be null");
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * Sets the endpoint path where clients will send their messages.
		 * @param messageEndpoint The message endpoint path
		 * @return This builder instance for method chaining
		 */
		public Builder messageEndpoint(String messageEndpoint) {
			Assert.hasText(messageEndpoint, "Message endpoint must not be empty");
			this.messageEndpoint = messageEndpoint;
			return this;
		}

		/**
		 * Sets the endpoint path where clients will establish SSE connections.
		 * <p>
		 * If not specified, the default value of {@link #DEFAULT_SSE_ENDPOINT} will be
		 * used.
		 * @param sseEndpoint The SSE endpoint path
		 * @return This builder instance for method chaining
		 */
		public Builder sseEndpoint(String sseEndpoint) {
			Assert.hasText(sseEndpoint, "SSE endpoint must not be empty");
			this.sseEndpoint = sseEndpoint;
			return this;
		}

		/**
		 * Builds a new instance of HttpServletSseServerTransportProvider with the
		 * configured settings.
		 * @return A new HttpServletSseServerTransportProvider instance
		 * @throws IllegalStateException if objectMapper or messageEndpoint is not set
		 */
		public HttpServletSseServerTransportProvider build() {
			if (objectMapper == null) {
				throw new IllegalStateException("ObjectMapper must be set");
			}
			if (messageEndpoint == null) {
				throw new IllegalStateException("MessageEndpoint must be set");
			}
			return new HttpServletSseServerTransportProvider(objectMapper, baseUrl, messageEndpoint, sseEndpoint);
		}

	}

}
