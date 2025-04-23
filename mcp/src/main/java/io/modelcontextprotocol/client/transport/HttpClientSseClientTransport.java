/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.FlowSseClient.SseEvent;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 使用Java的HttpClient实现的服务器发送事件(SSE)传输层，遵循MCP HTTP with SSE
 * 传输规范，实现了{@link io.modelcontextprotocol.spec.McpTransport}接口。
 *
 * <p>
 * 该传输实现通过SSE建立客户端和服务器之间的双向通信通道，
 * 使用SSE进行服务器到客户端的消息传输，使用HTTP POST请求进行
 * 客户端到服务器的消息传输。该传输：
 * <ul>
 * <li>建立SSE连接以接收服务器消息</li>
 * <li>通过SSE事件处理端点发现</li>
 * <li>使用Jackson管理消息序列化/反序列化</li>
 * <li>提供优雅的连接终止</li>
 * </ul>
 *
 * <p>
 * 该传输支持两种类型的SSE事件：
 * <ul>
 * <li>'endpoint' - 包含用于发送客户端消息的URL</li>
 * <li>'message' - 包含JSON-RPC消息负载</li>
 * </ul>
 *
 * @author Christian Tzolov
 * @see io.modelcontextprotocol.spec.McpTransport
 * @see io.modelcontextprotocol.spec.McpClientTransport
 */
public class HttpClientSseClientTransport implements McpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(HttpClientSseClientTransport.class);

	/** 用于JSON-RPC消息的SSE事件类型 */
	private static final String MESSAGE_EVENT_TYPE = "message";

	/** 用于端点发现的SSE事件类型 */
	private static final String ENDPOINT_EVENT_TYPE = "endpoint";

	/** 默认SSE端点路径 */
	private static final String DEFAULT_SSE_ENDPOINT = "/sse";

	/** MCP服务器的基础URI */
	private final URI baseUri;

	/** SSE端点路径 */
	private final String sseEndpoint;

	/** 用于处理服务器发送事件的SSE客户端。使用/sse端点 */
	private final FlowSseClient sseClient;

	/**
	 * 用于向服务器发送消息的HTTP客户端。通过消息端点使用HTTP POST
	 */
	private final HttpClient httpClient;

	/** 用于构建向服务器发送消息的请求的HTTP请求构建器 */
	private final HttpRequest.Builder requestBuilder;

	/** 用于消息序列化/反序列化的JSON对象映射器 */
	protected ObjectMapper objectMapper;

	/** 指示传输是否处于关闭状态的标志 */
	private volatile boolean isClosing = false;

	/** 用于协调端点发现的锁 */
	private final CountDownLatch closeLatch = new CountDownLatch(1);

	/** 保存已发现的消息端点URL */
	private final AtomicReference<String> messageEndpoint = new AtomicReference<>();

	/** 保存SSE连接的Future */
	private final AtomicReference<CompletableFuture<Void>> connectionFuture = new AtomicReference<>();

	/**
	 * 使用默认HTTP客户端和对象映射器创建新的传输实例。
	 * @param baseUri MCP服务器的基础URI
	 * @deprecated 请使用{@link HttpClientSseClientTransport#builder(String)}代替。
	 * 此构造函数将在未来版本中移除。
	 */
	@Deprecated(forRemoval = true)
	public HttpClientSseClientTransport(String baseUri) {
		this(HttpClient.newBuilder(), baseUri, new ObjectMapper());
	}

	/**
	 * 使用自定义HTTP客户端构建器和对象映射器创建新的传输实例。
	 * @param clientBuilder 要使用的HTTP客户端构建器
	 * @param baseUri MCP服务器的基础URI
	 * @param objectMapper 用于JSON序列化/反序列化的对象映射器
	 * @throws IllegalArgumentException 如果objectMapper或clientBuilder为null
	 * @deprecated 请使用{@link HttpClientSseClientTransport#builder(String)}代替。
	 * 此构造函数将在未来版本中移除。
	 */
	@Deprecated(forRemoval = true)
	public HttpClientSseClientTransport(HttpClient.Builder clientBuilder, String baseUri, ObjectMapper objectMapper) {
		this(clientBuilder, baseUri, DEFAULT_SSE_ENDPOINT, objectMapper);
	}

	/**
	 * 使用自定义HTTP客户端构建器和对象映射器创建新的传输实例。
	 * @param clientBuilder 要使用的HTTP客户端构建器
	 * @param baseUri MCP服务器的基础URI
	 * @param sseEndpoint SSE端点路径
	 * @param objectMapper 用于JSON序列化/反序列化的对象映射器
	 * @throws IllegalArgumentException 如果objectMapper或clientBuilder为null
	 * @deprecated 请使用{@link HttpClientSseClientTransport#builder(String)}代替。
	 * 此构造函数将在未来版本中移除。
	 */
	@Deprecated(forRemoval = true)
	public HttpClientSseClientTransport(HttpClient.Builder clientBuilder, String baseUri, String sseEndpoint,
			ObjectMapper objectMapper) {
		this(clientBuilder, HttpRequest.newBuilder(), baseUri, sseEndpoint, objectMapper);
	}

	/**
	 * 使用自定义HTTP客户端构建器、对象映射器和请求头创建新的传输实例。
	 * @param clientBuilder 要使用的HTTP客户端构建器
	 * @param requestBuilder 要使用的HTTP请求构建器
	 * @param baseUri MCP服务器的基础URI
	 * @param sseEndpoint SSE端点路径
	 * @param objectMapper 用于JSON序列化/反序列化的对象映射器
	 * @throws IllegalArgumentException 如果objectMapper、clientBuilder或headers为null
	 * @deprecated 请使用{@link HttpClientSseClientTransport#builder(String)}代替。
	 * 此构造函数将在未来版本中移除。
	 */
	@Deprecated(forRemoval = true)
	public HttpClientSseClientTransport(HttpClient.Builder clientBuilder, HttpRequest.Builder requestBuilder,
			String baseUri, String sseEndpoint, ObjectMapper objectMapper) {
		this(clientBuilder.connectTimeout(Duration.ofSeconds(10)).build(), requestBuilder, baseUri, sseEndpoint,
				objectMapper);
	}

	/**
	 * 使用自定义HTTP客户端、对象映射器和请求头创建新的传输实例。
	 * @param httpClient 要使用的HTTP客户端
	 * @param requestBuilder 要使用的HTTP请求构建器
	 * @param baseUri MCP服务器的基础URI
	 * @param sseEndpoint SSE端点路径
	 * @param objectMapper 用于JSON序列化/反序列化的对象映射器
	 * @throws IllegalArgumentException 如果objectMapper、clientBuilder或headers为null
	 */
	HttpClientSseClientTransport(HttpClient httpClient, HttpRequest.Builder requestBuilder, String baseUri,
			String sseEndpoint, ObjectMapper objectMapper) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.hasText(baseUri, "baseUri must not be empty");
		Assert.hasText(sseEndpoint, "sseEndpoint must not be empty");
		Assert.notNull(httpClient, "httpClient must not be null");
		Assert.notNull(requestBuilder, "requestBuilder must not be null");
		this.baseUri = URI.create(baseUri);
		this.sseEndpoint = sseEndpoint;
		this.objectMapper = objectMapper;
		this.httpClient = httpClient;
		this.requestBuilder = requestBuilder;

		this.sseClient = new FlowSseClient(this.httpClient, requestBuilder);
	}

	/**
	 * 创建{@link HttpClientSseClientTransport}的新构建器。
	 * @param baseUri MCP服务器的基础URI
	 * @return 新的构建器实例
	 */
	public static Builder builder(String baseUri) {
		return new Builder().baseUri(baseUri);
	}

	/**
	 * {@link HttpClientSseClientTransport}的构建器。
	 */
	public static class Builder {

		private String baseUri;

		private String sseEndpoint = DEFAULT_SSE_ENDPOINT;

		private HttpClient.Builder clientBuilder = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10));

		private ObjectMapper objectMapper = new ObjectMapper();

		private HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
			.header("Content-Type", "application/json");

		/**
		 * 创建新的构建器实例。
		 */
		Builder() {
			// 默认构造函数
		}

		/**
		 * 使用指定的基础URI创建新的构建器。
		 * @param baseUri MCP服务器的基础URI
		 * @deprecated 请使用{@link HttpClientSseClientTransport#builder(String)}代替。
		 * 此构造函数已弃用，将在未来版本中移除或设为{@code protected}或
		 * {@code private}。
		 */
		@Deprecated(forRemoval = true)
		public Builder(String baseUri) {
			Assert.hasText(baseUri, "baseUri must not be empty");
			this.baseUri = baseUri;
		}

		/**
		 * 设置基础URI。
		 * @param baseUri 基础URI
		 * @return 此构建器
		 */
		Builder baseUri(String baseUri) {
			Assert.hasText(baseUri, "baseUri must not be empty");
			this.baseUri = baseUri;
			return this;
		}

		/**
		 * 设置SSE端点路径。
		 * @param sseEndpoint SSE端点路径
		 * @return 此构建器
		 */
		public Builder sseEndpoint(String sseEndpoint) {
			Assert.hasText(sseEndpoint, "sseEndpoint must not be empty");
			this.sseEndpoint = sseEndpoint;
			return this;
		}

		/**
		 * 设置HTTP客户端构建器。
		 * @param clientBuilder HTTP客户端构建器
		 * @return 此构建器
		 */
		public Builder clientBuilder(HttpClient.Builder clientBuilder) {
			Assert.notNull(clientBuilder, "clientBuilder must not be null");
			this.clientBuilder = clientBuilder;
			return this;
		}

		/**
		 * 自定义HTTP客户端构建器。
		 * @param clientCustomizer 用于自定义HTTP客户端构建器的消费者
		 * @return 此构建器
		 */
		public Builder customizeClient(final Consumer<HttpClient.Builder> clientCustomizer) {
			Assert.notNull(clientCustomizer, "clientCustomizer must not be null");
			clientCustomizer.accept(clientBuilder);
			return this;
		}

		/**
		 * 设置HTTP请求构建器。
		 * @param requestBuilder HTTP请求构建器
		 * @return 此构建器
		 */
		public Builder requestBuilder(HttpRequest.Builder requestBuilder) {
			Assert.notNull(requestBuilder, "requestBuilder must not be null");
			this.requestBuilder = requestBuilder;
			return this;
		}

		/**
		 * 自定义HTTP请求构建器。
		 * @param requestCustomizer 用于自定义HTTP请求构建器的消费者
		 * @return 此构建器
		 */
		public Builder customizeRequest(final Consumer<HttpRequest.Builder> requestCustomizer) {
			Assert.notNull(requestCustomizer, "requestCustomizer must not be null");
			requestCustomizer.accept(requestBuilder);
			return this;
		}

		/**
		 * 设置用于JSON序列化/反序列化的对象映射器。
		 * @param objectMapper 对象映射器
		 * @return 此构建器
		 */
		public Builder objectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "objectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		/**
		 * 构建新的{@link HttpClientSseClientTransport}实例。
		 * @return 新的传输实例
		 */
		public HttpClientSseClientTransport build() {
			return new HttpClientSseClientTransport(clientBuilder.build(), requestBuilder, baseUri, sseEndpoint,
					objectMapper);
		}

	}

	/**
	 * 建立与服务器的SSE连接并设置消息处理。
	 *
	 * <p>
	 * 此方法：
	 * <ul>
	 * <li>初始化SSE连接</li>
	 * <li>处理端点发现事件</li>
	 * <li>处理传入的JSON-RPC消息</li>
	 * </ul>
	 * @param handler 用于处理接收到的JSON-RPC消息的函数
	 * @return 当连接建立完成时完成的Mono
	 */
	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		connectionFuture.set(future);

		URI clientUri = Utils.resolveUri(this.baseUri, this.sseEndpoint);
		sseClient.subscribe(clientUri.toString(), new FlowSseClient.SseEventHandler() {
			@Override
			public void onEvent(SseEvent event) {
				if (isClosing) {
					return;
				}

				try {
					if (ENDPOINT_EVENT_TYPE.equals(event.type())) {
						String endpoint = event.data();
						messageEndpoint.set(endpoint);
						closeLatch.countDown();
						future.complete(null);
					}
					else if (MESSAGE_EVENT_TYPE.equals(event.type())) {
						JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, event.data());
						handler.apply(Mono.just(message)).subscribe();
					}
					else {
						logger.error("Received unrecognized SSE event type: {}", event.type());
					}
				}
				catch (IOException e) {
					logger.error("Error processing SSE event", e);
					future.completeExceptionally(e);
				}
			}

			@Override
			public void onError(Throwable error) {
				if (!isClosing) {
					logger.error("SSE connection error", error);
					future.completeExceptionally(error);
				}
			}
		});

		return Mono.fromFuture(future);
	}

	/**
	 * 向服务器发送JSON-RPC消息。
	 *
	 * <p>
	 * 此方法在发送消息之前等待消息端点被发现。消息被序列化为JSON
	 * 并作为HTTP POST请求发送。
	 * @param message 要发送的JSON-RPC消息
	 * @return 当消息发送完成时完成的Mono
	 * @throws McpError 如果消息端点不可用或等待超时
	 */
	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		if (isClosing) {
			return Mono.empty();
		}

		try {
			if (!closeLatch.await(10, TimeUnit.SECONDS)) {
				return Mono.error(new McpError("Failed to wait for the message endpoint"));
			}
		}
		catch (InterruptedException e) {
			return Mono.error(new McpError("Failed to wait for the message endpoint"));
		}

		String endpoint = messageEndpoint.get();
		if (endpoint == null) {
			return Mono.error(new McpError("No message endpoint available"));
		}

		try {
			String jsonText = this.objectMapper.writeValueAsString(message);
			URI requestUri = Utils.resolveUri(baseUri, endpoint);
			HttpRequest request = this.requestBuilder.uri(requestUri)
				.POST(HttpRequest.BodyPublishers.ofString(jsonText))
				.build();

			return Mono.fromFuture(
					httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenAccept(response -> {
						if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 202
								&& response.statusCode() != 206) {
							logger.error("Error sending message: {}", response.statusCode());
						}
					}));
		}
		catch (IOException e) {
			if (!isClosing) {
				return Mono.error(new RuntimeException("Failed to serialize message", e));
			}
			return Mono.empty();
		}
	}

	/**
	 * 优雅地关闭传输连接。
	 *
	 * <p>
	 * 设置关闭标志并取消任何待处理的连接future。这可以防止
	 * 新消息被发送，并允许正在进行的操作完成。
	 * @return 当关闭过程启动时完成的Mono
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			isClosing = true;
			CompletableFuture<Void> future = connectionFuture.get();
			if (future != null && !future.isDone()) {
				future.cancel(true);
			}
		});
	}

	/**
	 * 使用配置的对象映射器将数据反序列化为指定类型。
	 * @param data 要反序列化的数据
	 * @param typeRef 目标类型的类型引用
	 * @param <T> 目标类型
	 * @return 反序列化后的对象
	 */
	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

}
