/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransport;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * 用于创建模型上下文协议(MCP)客户端的工厂类。MCP是一个使AI模型能够
 * 通过标准化接口与外部工具和资源进行交互的协议。
 *
 * <p>
 * 此类作为建立与MCP服务器连接的主要入口点，实现MCP规范的客户端部分。
 * 该协议遵循客户端-服务器架构，其中：
 * <ul>
 * <li>客户端（此实现）发起连接并发送请求
 * <li>服务器响应请求并提供对工具和资源的访问
 * <li>通信通过传输层（例如stdio、SSE）使用JSON-RPC 2.0进行
 * </ul>
 *
 * <p>
 * 该类提供工厂方法来创建以下两种客户端：
 * <ul>
 * <li>{@link McpAsyncClient} 用于带有CompletableFuture响应的非阻塞操作
 * <li>{@link McpSyncClient} 用于带有直接响应的阻塞操作
 * </ul>
 *
 * <p>
 * 创建基本同步客户端的示例：<pre>{@code
 * McpClient.sync(transport)
 *     .requestTimeout(Duration.ofSeconds(5))
 *     .build();
 * }</pre>
 *
 * 创建基本异步客户端的示例：<pre>{@code
 * McpClient.async(transport)
 *     .requestTimeout(Duration.ofSeconds(5))
 *     .build();
 * }</pre>
 *
 * <p>
 * 高级异步配置示例：<pre>{@code
 * McpClient.async(transport)
 *     .requestTimeout(Duration.ofSeconds(10))
 *     .capabilities(new ClientCapabilities(...))
 *     .clientInfo(new Implementation("My Client", "1.0.0"))
 *     .roots(new Root("file://workspace", "Workspace Files"))
 *     .toolsChangeConsumer(tools -> Mono.fromRunnable(() -> System.out.println("Tools updated: " + tools)))
 *     .resourcesChangeConsumer(resources -> Mono.fromRunnable(() -> System.out.println("Resources updated: " + resources)))
 *     .promptsChangeConsumer(prompts -> Mono.fromRunnable(() -> System.out.println("Prompts updated: " + prompts)))
 *     .loggingConsumer(message -> Mono.fromRunnable(() -> System.out.println("Log message: " + message)))
 *     .build();
 * }</pre>
 *
 * <p>
 * 客户端支持：
 * <ul>
 * <li>工具发现和调用
 * <li>资源访问和管理
 * <li>提示模板处理
 * <li>通过变更消费者进行实时更新
 * <li>自定义采样策略
 * <li>带有严重性级别的结构化日志记录
 * </ul>
 *
 * <p>
 * 客户端通过MCP日志工具支持结构化日志记录：
 * <ul>
 * <li>从DEBUG到EMERGENCY的八个严重性级别
 * <li>可选的日志记录器名称分类
 * <li>可配置的日志消费者
 * <li>服务器控制的最小日志级别
 * </ul>
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpAsyncClient
 * @see McpSyncClient
 * @see McpTransport
 */
public interface McpClient {

	/**
	 * 使用指定的传输层开始构建同步MCP客户端。同步MCP客户端提供
	 * 阻塞操作。同步客户端在返回之前等待每个操作完成，使其使用
	 * 更简单，但在并发操作时可能性能较低。传输层使用stdio或
	 * 服务器发送事件(SSE)等协议处理客户端和服务器之间的低级通信。
	 * @param transport MCP通信的传输层实现。常见的实现包括用于
	 * 基于stdio的通信的{@code StdioClientTransport}和用于基于SSE的
	 * 通信的{@code SseClientTransport}。
	 * @return 用于配置客户端的新构建器实例
	 * @throws IllegalArgumentException 如果transport为null
	 */
	static SyncSpec sync(McpClientTransport transport) {
		return new SyncSpec(transport);
	}

	/**
	 * 使用指定的传输层开始构建异步MCP客户端。异步MCP客户端提供
	 * 非阻塞操作。异步客户端立即返回响应式原语(Mono/Flux)，允许
	 * 并发操作和响应式编程模式。传输层使用stdio或服务器发送事件
	 * (SSE)等协议处理客户端和服务器之间的低级通信。
	 * @param transport MCP通信的传输层实现。常见的实现包括用于
	 * 基于stdio的通信的{@code StdioClientTransport}和用于基于SSE的
	 * 通信的{@code SseClientTransport}。
	 * @return 用于配置客户端的新构建器实例
	 * @throws IllegalArgumentException 如果transport为null
	 */
	static AsyncSpec async(McpClientTransport transport) {
		return new AsyncSpec(transport);
	}

	/**
	 * 同步客户端规范。此类遵循构建器模式，提供流畅的API用于
	 * 设置具有自定义配置的客户端。
	 *
	 * <p>
	 * 构建器支持以下配置：
	 * <ul>
	 * <li>用于客户端-服务器通信的传输层
	 * <li>用于操作边界的请求超时
	 * <li>用于功能协商的客户端能力
	 * <li>用于版本跟踪的客户端实现详情
	 * <li>用于资源访问的根URI
	 * <li>用于工具、资源和提示的变更通知处理器
	 * <li>自定义消息采样逻辑
	 * </ul>
	 */
	class SyncSpec {

		private final McpClientTransport transport;

		private Duration requestTimeout = Duration.ofSeconds(20); // Default timeout

		private Duration initializationTimeout = Duration.ofSeconds(20);

		private ClientCapabilities capabilities;

		private Implementation clientInfo = new Implementation("Java SDK MCP Client", "1.0.0");

		private final Map<String, Root> roots = new HashMap<>();

		private final List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers = new ArrayList<>();

		private final List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers = new ArrayList<>();

		private final List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers = new ArrayList<>();

		private final List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers = new ArrayList<>();

		private Function<CreateMessageRequest, CreateMessageResult> samplingHandler;

		private SyncSpec(McpClientTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
		}

		/**
		 * 设置在请求超时之前等待服务器响应的持续时间。此超时
		 * 适用于通过客户端发出的所有请求，包括工具调用、资源
		 * 访问和提示操作。
		 * @param requestTimeout 请求超时前等待的持续时间。不能
		 * 为null。
		 * @return 用于方法链接的此构建器实例
		 * @throws IllegalArgumentException 如果requestTimeout为null
		 */
		public SyncSpec requestTimeout(Duration requestTimeout) {
			Assert.notNull(requestTimeout, "Request timeout must not be null");
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * @param initializationTimeout The duration to wait for the initialization
		 * lifecycle step to complete.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if initializationTimeout is null
		 */
		public SyncSpec initializationTimeout(Duration initializationTimeout) {
			Assert.notNull(initializationTimeout, "Initialization timeout must not be null");
			this.initializationTimeout = initializationTimeout;
			return this;
		}

		/**
		 * Sets the client capabilities that will be advertised to the server during
		 * connection initialization. Capabilities define what features the client
		 * supports, such as tool execution, resource access, and prompt handling.
		 * @param capabilities The client capabilities configuration. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if capabilities is null
		 */
		public SyncSpec capabilities(ClientCapabilities capabilities) {
			Assert.notNull(capabilities, "Capabilities must not be null");
			this.capabilities = capabilities;
			return this;
		}

		/**
		 * Sets the client implementation information that will be shared with the server
		 * during connection initialization. This helps with version compatibility and
		 * debugging.
		 * @param clientInfo The client implementation details including name and version.
		 * Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if clientInfo is null
		 */
		public SyncSpec clientInfo(Implementation clientInfo) {
			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			return this;
		}

		/**
		 * Sets the root URIs that this client can access. Roots define the base URIs for
		 * resources that the client can request from the server. For example, a root
		 * might be "file://workspace" for accessing workspace files.
		 * @param roots A list of root definitions. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if roots is null
		 */
		public SyncSpec roots(List<Root> roots) {
			Assert.notNull(roots, "Roots must not be null");
			for (Root root : roots) {
				this.roots.put(root.uri(), root);
			}
			return this;
		}

		/**
		 * Sets the root URIs that this client can access, using a varargs parameter for
		 * convenience. This is an alternative to {@link #roots(List)}.
		 * @param roots An array of root definitions. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if roots is null
		 * @see #roots(List)
		 */
		public SyncSpec roots(Root... roots) {
			Assert.notNull(roots, "Roots must not be null");
			for (Root root : roots) {
				this.roots.put(root.uri(), root);
			}
			return this;
		}

		/**
		 * Sets a custom sampling handler for processing message creation requests. The
		 * sampling handler can modify or validate messages before they are sent to the
		 * server, enabling custom processing logic.
		 * @param samplingHandler A function that processes message requests and returns
		 * results. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if samplingHandler is null
		 */
		public SyncSpec sampling(Function<CreateMessageRequest, CreateMessageResult> samplingHandler) {
			Assert.notNull(samplingHandler, "Sampling handler must not be null");
			this.samplingHandler = samplingHandler;
			return this;
		}

		/**
		 * Adds a consumer to be notified when the available tools change. This allows the
		 * client to react to changes in the server's tool capabilities, such as tools
		 * being added or removed.
		 * @param toolsChangeConsumer A consumer that receives the updated list of
		 * available tools. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolsChangeConsumer is null
		 */
		public SyncSpec toolsChangeConsumer(Consumer<List<McpSchema.Tool>> toolsChangeConsumer) {
			Assert.notNull(toolsChangeConsumer, "Tools change consumer must not be null");
			this.toolsChangeConsumers.add(toolsChangeConsumer);
			return this;
		}

		/**
		 * Adds a consumer to be notified when the available resources change. This allows
		 * the client to react to changes in the server's resource availability, such as
		 * files being added or removed.
		 * @param resourcesChangeConsumer A consumer that receives the updated list of
		 * available resources. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourcesChangeConsumer is null
		 */
		public SyncSpec resourcesChangeConsumer(Consumer<List<McpSchema.Resource>> resourcesChangeConsumer) {
			Assert.notNull(resourcesChangeConsumer, "Resources change consumer must not be null");
			this.resourcesChangeConsumers.add(resourcesChangeConsumer);
			return this;
		}

		/**
		 * Adds a consumer to be notified when the available prompts change. This allows
		 * the client to react to changes in the server's prompt templates, such as new
		 * templates being added or existing ones being modified.
		 * @param promptsChangeConsumer A consumer that receives the updated list of
		 * available prompts. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if promptsChangeConsumer is null
		 */
		public SyncSpec promptsChangeConsumer(Consumer<List<McpSchema.Prompt>> promptsChangeConsumer) {
			Assert.notNull(promptsChangeConsumer, "Prompts change consumer must not be null");
			this.promptsChangeConsumers.add(promptsChangeConsumer);
			return this;
		}

		/**
		 * Adds a consumer to be notified when logging messages are received from the
		 * server. This allows the client to react to log messages, such as warnings or
		 * errors, that are sent by the server.
		 * @param loggingConsumer A consumer that receives logging messages. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 */
		public SyncSpec loggingConsumer(Consumer<McpSchema.LoggingMessageNotification> loggingConsumer) {
			Assert.notNull(loggingConsumer, "Logging consumer must not be null");
			this.loggingConsumers.add(loggingConsumer);
			return this;
		}

		/**
		 * Adds multiple consumers to be notified when logging messages are received from
		 * the server. This allows the client to react to log messages, such as warnings
		 * or errors, that are sent by the server.
		 * @param loggingConsumers A list of consumers that receive logging messages. Must
		 * not be null.
		 * @return This builder instance for method chaining
		 */
		public SyncSpec loggingConsumers(List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers) {
			Assert.notNull(loggingConsumers, "Logging consumers must not be null");
			this.loggingConsumers.addAll(loggingConsumers);
			return this;
		}

		/**
		 * Create an instance of {@link McpSyncClient} with the provided configurations or
		 * sensible defaults.
		 * @return a new instance of {@link McpSyncClient}.
		 */
		public McpSyncClient build() {
			McpClientFeatures.Sync syncFeatures = new McpClientFeatures.Sync(this.clientInfo, this.capabilities,
					this.roots, this.toolsChangeConsumers, this.resourcesChangeConsumers, this.promptsChangeConsumers,
					this.loggingConsumers, this.samplingHandler);

			McpClientFeatures.Async asyncFeatures = McpClientFeatures.Async.fromSync(syncFeatures);

			return new McpSyncClient(
					new McpAsyncClient(transport, this.requestTimeout, this.initializationTimeout, asyncFeatures));
		}

	}

	/**
	 * Asynchronous client specification. This class follows the builder pattern to
	 * provide a fluent API for setting up clients with custom configurations.
	 *
	 * <p>
	 * The builder supports configuration of:
	 * <ul>
	 * <li>Transport layer for client-server communication
	 * <li>Request timeouts for operation boundaries
	 * <li>Client capabilities for feature negotiation
	 * <li>Client implementation details for version tracking
	 * <li>Root URIs for resource access
	 * <li>Change notification handlers for tools, resources, and prompts
	 * <li>Custom message sampling logic
	 * </ul>
	 */
	class AsyncSpec {

		private final McpClientTransport transport;

		private Duration requestTimeout = Duration.ofSeconds(20); // Default timeout

		private Duration initializationTimeout = Duration.ofSeconds(20);

		private ClientCapabilities capabilities;

		private Implementation clientInfo = new Implementation("Spring AI MCP Client", "0.3.1");

		private final Map<String, Root> roots = new HashMap<>();

		private final List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers = new ArrayList<>();

		private final List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers = new ArrayList<>();

		private final List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers = new ArrayList<>();

		private final List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers = new ArrayList<>();

		private Function<CreateMessageRequest, Mono<CreateMessageResult>> samplingHandler;

		private AsyncSpec(McpClientTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
		}

		/**
		 * 设置在请求超时之前等待服务器响应的持续时间。此超时
		 * 适用于通过客户端发出的所有请求，包括工具调用、资源
		 * 访问和提示操作。
		 * @param requestTimeout 请求超时前等待的持续时间。不能
		 * 为null。
		 * @return 用于方法链接的此构建器实例
		 * @throws IllegalArgumentException 如果requestTimeout为null
		 */
		public AsyncSpec requestTimeout(Duration requestTimeout) {
			Assert.notNull(requestTimeout, "Request timeout must not be null");
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * @param initializationTimeout The duration to wait for the initialization
		 * lifecycle step to complete.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if initializationTimeout is null
		 */
		public AsyncSpec initializationTimeout(Duration initializationTimeout) {
			Assert.notNull(initializationTimeout, "Initialization timeout must not be null");
			this.initializationTimeout = initializationTimeout;
			return this;
		}

		/**
		 * Sets the client capabilities that will be advertised to the server during
		 * connection initialization. Capabilities define what features the client
		 * supports, such as tool execution, resource access, and prompt handling.
		 * @param capabilities The client capabilities configuration. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if capabilities is null
		 */
		public AsyncSpec capabilities(ClientCapabilities capabilities) {
			Assert.notNull(capabilities, "Capabilities must not be null");
			this.capabilities = capabilities;
			return this;
		}

		/**
		 * Sets the client implementation information that will be shared with the server
		 * during connection initialization. This helps with version compatibility and
		 * debugging.
		 * @param clientInfo The client implementation details including name and version.
		 * Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if clientInfo is null
		 */
		public AsyncSpec clientInfo(Implementation clientInfo) {
			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			return this;
		}

		/**
		 * Sets the root URIs that this client can access. Roots define the base URIs for
		 * resources that the client can request from the server. For example, a root
		 * might be "file://workspace" for accessing workspace files.
		 * @param roots A list of root definitions. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if roots is null
		 */
		public AsyncSpec roots(List<Root> roots) {
			Assert.notNull(roots, "Roots must not be null");
			for (Root root : roots) {
				this.roots.put(root.uri(), root);
			}
			return this;
		}

		/**
		 * Sets the root URIs that this client can access, using a varargs parameter for
		 * convenience. This is an alternative to {@link #roots(List)}.
		 * @param roots An array of root definitions. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if roots is null
		 * @see #roots(List)
		 */
		public AsyncSpec roots(Root... roots) {
			Assert.notNull(roots, "Roots must not be null");
			for (Root root : roots) {
				this.roots.put(root.uri(), root);
			}
			return this;
		}

		/**
		 * Sets a custom sampling handler for processing message creation requests. The
		 * sampling handler can modify or validate messages before they are sent to the
		 * server, enabling custom processing logic.
		 * @param samplingHandler A function that processes message requests and returns
		 * results. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if samplingHandler is null
		 */
		public AsyncSpec sampling(Function<CreateMessageRequest, Mono<CreateMessageResult>> samplingHandler) {
			Assert.notNull(samplingHandler, "Sampling handler must not be null");
			this.samplingHandler = samplingHandler;
			return this;
		}

		/**
		 * Adds a consumer to be notified when the available tools change. This allows the
		 * client to react to changes in the server's tool capabilities, such as tools
		 * being added or removed.
		 * @param toolsChangeConsumer A consumer that receives the updated list of
		 * available tools. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolsChangeConsumer is null
		 */
		public AsyncSpec toolsChangeConsumer(Function<List<McpSchema.Tool>, Mono<Void>> toolsChangeConsumer) {
			Assert.notNull(toolsChangeConsumer, "Tools change consumer must not be null");
			this.toolsChangeConsumers.add(toolsChangeConsumer);
			return this;
		}

		/**
		 * Adds a consumer to be notified when the available resources change. This allows
		 * the client to react to changes in the server's resource availability, such as
		 * files being added or removed.
		 * @param resourcesChangeConsumer A consumer that receives the updated list of
		 * available resources. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourcesChangeConsumer is null
		 */
		public AsyncSpec resourcesChangeConsumer(
				Function<List<McpSchema.Resource>, Mono<Void>> resourcesChangeConsumer) {
			Assert.notNull(resourcesChangeConsumer, "Resources change consumer must not be null");
			this.resourcesChangeConsumers.add(resourcesChangeConsumer);
			return this;
		}

		/**
		 * Adds a consumer to be notified when the available prompts change. This allows
		 * the client to react to changes in the server's prompt templates, such as new
		 * templates being added or existing ones being modified.
		 * @param promptsChangeConsumer A consumer that receives the updated list of
		 * available prompts. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if promptsChangeConsumer is null
		 */
		public AsyncSpec promptsChangeConsumer(Function<List<McpSchema.Prompt>, Mono<Void>> promptsChangeConsumer) {
			Assert.notNull(promptsChangeConsumer, "Prompts change consumer must not be null");
			this.promptsChangeConsumers.add(promptsChangeConsumer);
			return this;
		}

		/**
		 * Adds a consumer to be notified when logging messages are received from the
		 * server. This allows the client to react to log messages, such as warnings or
		 * errors, that are sent by the server.
		 * @param loggingConsumer A consumer that receives logging messages. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 */
		public AsyncSpec loggingConsumer(Function<McpSchema.LoggingMessageNotification, Mono<Void>> loggingConsumer) {
			Assert.notNull(loggingConsumer, "Logging consumer must not be null");
			this.loggingConsumers.add(loggingConsumer);
			return this;
		}

		/**
		 * Adds multiple consumers to be notified when logging messages are received from
		 * the server. This allows the client to react to log messages, such as warnings
		 * or errors, that are sent by the server.
		 * @param loggingConsumers A list of consumers that receive logging messages. Must
		 * not be null.
		 * @return This builder instance for method chaining
		 */
		public AsyncSpec loggingConsumers(
				List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers) {
			Assert.notNull(loggingConsumers, "Logging consumers must not be null");
			this.loggingConsumers.addAll(loggingConsumers);
			return this;
		}

		/**
		 * Create an instance of {@link McpAsyncClient} with the provided configurations
		 * or sensible defaults.
		 * @return a new instance of {@link McpAsyncClient}.
		 */
		public McpAsyncClient build() {
			return new McpAsyncClient(this.transport, this.requestTimeout, this.initializationTimeout,
					new McpClientFeatures.Async(this.clientInfo, this.capabilities, this.roots,
							this.toolsChangeConsumers, this.resourcesChangeConsumers, this.promptsChangeConsumers,
							this.loggingConsumers, this.samplingHandler));
		}

	}

}
