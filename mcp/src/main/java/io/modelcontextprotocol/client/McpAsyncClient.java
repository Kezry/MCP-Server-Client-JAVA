/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpClientSession.NotificationHandler;
import io.modelcontextprotocol.spec.McpClientSession.RequestHandler;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.PaginatedRequest;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpTransport;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 模型上下文协议(MCP)客户端实现，使用Project Reactor的Mono和Flux类型提供与MCP服务器的异步通信。
 *
 * <p>
 * 该客户端实现了MCP规范，使AI模型能够通过标准化接口与外部工具和资源进行交互。主要特性包括：
 * <ul>
 * <li>使用响应式编程模式的异步通信
 * <li>服务器提供功能的工具发现和调用
 * <li>基于URI寻址的资源访问和管理
 * <li>用于标准化AI交互的提示模板处理
 * <li>工具、资源和提示变更的实时通知
 * <li>可配置严重级别的结构化日志记录
 * <li>AI模型交互的消息采样
 * </ul>
 *
 * <p>
 * 客户端遵循以下生命周期：
 * <ol>
 * <li>初始化 - 建立连接并协商功能
 * <li>正常运行 - 处理请求和通知
 * <li>优雅关闭 - 确保连接清理终止
 * </ol>
 *
 * <p>
 * 此实现使用Project Reactor进行非阻塞操作，使其适用于高吞吐量场景和响应式应用。所有操作都返回
 * 可以组合成响应式管道的Mono或Flux类型。
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 * @author Jihoon Kim
 * @see McpClient
 * @see McpSchema
 * @see McpClientSession
 */
public class McpAsyncClient {

	private static final Logger logger = LoggerFactory.getLogger(McpAsyncClient.class);

	private static TypeReference<Void> VOID_TYPE_REFERENCE = new TypeReference<>() {
	};

	protected final Sinks.One<McpSchema.InitializeResult> initializedSink = Sinks.one();

	private AtomicBoolean initialized = new AtomicBoolean(false);

	/**
	 * 等待客户端-服务器连接初始化的最大超时时间。
	 */
	private final Duration initializationTimeout;

	/**
	 * MCP会话实现，用于管理客户端和服务器之间的双向JSON-RPC通信。
	 */
	private final McpClientSession mcpSession;

	/**
	 * 客户端功能。
	 */
	private final McpSchema.ClientCapabilities clientCapabilities;

	/**
	 * 客户端实现信息。
	 */
	private final McpSchema.Implementation clientInfo;

	/**
	 * 服务器功能。
	 */
	private McpSchema.ServerCapabilities serverCapabilities;

	/**
	 * 服务器指令。
	 */
	private String serverInstructions;

	/**
	 * 服务器实现信息。
	 */
	private McpSchema.Implementation serverInfo;

	/**
	 * Roots定义了服务器可以在文件系统中操作的边界，
	 * 使它们能够了解可以访问哪些目录和文件。
	 * 服务器可以从支持的客户端请求根目录列表，并在列表更改时
	 * 接收通知。
	 */
	private final ConcurrentHashMap<String, Root> roots;

	/**
	 * MCP为服务器提供了一种标准化的方式，通过客户端从语言模型请求LLM采样（"完成"
	 * 或"生成"）。这个流程允许客户端保持对模型访问、选择和权限的控制，同时使
	 * 服务器能够利用AI功能——无需服务器API密钥。服务器可以请求基于文本或图像的
	 * 交互，并可以选择在其提示中包含来自MCP服务器的上下文。
	 */
	private Function<CreateMessageRequest, Mono<CreateMessageResult>> samplingHandler;

	/**
	 * 客户端传输实现。
	 */
	private final McpTransport transport;

	/**
	 * 支持的协议版本。
	 */
	private List<String> protocolVersions = List.of(McpSchema.LATEST_PROTOCOL_VERSION);

	/**
	 * 使用给定的传输和会话请求-响应超时创建新的McpAsyncClient。
	 * @param transport 要使用的传输。
	 * @param requestTimeout 会话请求-响应超时。
	 * @param initializationTimeout 等待客户端-服务器的最大超时时间
	 * @param features MCP客户端支持的功能。
	 */
	McpAsyncClient(McpClientTransport transport, Duration requestTimeout, Duration initializationTimeout,
			McpClientFeatures.Async features) {

		Assert.notNull(transport, "Transport must not be null");
		Assert.notNull(requestTimeout, "Request timeout must not be null");
		Assert.notNull(initializationTimeout, "Initialization timeout must not be null");

		this.clientInfo = features.clientInfo();
		this.clientCapabilities = features.clientCapabilities();
		this.transport = transport;
		this.roots = new ConcurrentHashMap<>(features.roots());
		this.initializationTimeout = initializationTimeout;

		// Request Handlers
		Map<String, RequestHandler<?>> requestHandlers = new HashMap<>();

		// Roots List Request Handler
		if (this.clientCapabilities.roots() != null) {
			requestHandlers.put(McpSchema.METHOD_ROOTS_LIST, rootsListRequestHandler());
		}

		// Sampling Handler
		if (this.clientCapabilities.sampling() != null) {
			if (features.samplingHandler() == null) {
				throw new McpError("Sampling handler must not be null when client capabilities include sampling");
			}
			this.samplingHandler = features.samplingHandler();
			requestHandlers.put(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, samplingCreateMessageHandler());
		}

		// Notification Handlers
		Map<String, NotificationHandler> notificationHandlers = new HashMap<>();

		// Tools Change Notification
		List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumersFinal = new ArrayList<>();
		toolsChangeConsumersFinal
			.add((notification) -> Mono.fromRunnable(() -> logger.debug("Tools changed: {}", notification)));

		if (!Utils.isEmpty(features.toolsChangeConsumers())) {
			toolsChangeConsumersFinal.addAll(features.toolsChangeConsumers());
		}
		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED,
				asyncToolsChangeNotificationHandler(toolsChangeConsumersFinal));

		// Resources Change Notification
		List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumersFinal = new ArrayList<>();
		resourcesChangeConsumersFinal
			.add((notification) -> Mono.fromRunnable(() -> logger.debug("Resources changed: {}", notification)));

		if (!Utils.isEmpty(features.resourcesChangeConsumers())) {
			resourcesChangeConsumersFinal.addAll(features.resourcesChangeConsumers());
		}

		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED,
				asyncResourcesChangeNotificationHandler(resourcesChangeConsumersFinal));

		// Prompts Change Notification
		List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumersFinal = new ArrayList<>();
		promptsChangeConsumersFinal
			.add((notification) -> Mono.fromRunnable(() -> logger.debug("Prompts changed: {}", notification)));
		if (!Utils.isEmpty(features.promptsChangeConsumers())) {
			promptsChangeConsumersFinal.addAll(features.promptsChangeConsumers());
		}
		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED,
				asyncPromptsChangeNotificationHandler(promptsChangeConsumersFinal));

		// Utility Logging Notification
		List<Function<LoggingMessageNotification, Mono<Void>>> loggingConsumersFinal = new ArrayList<>();
		loggingConsumersFinal.add((notification) -> Mono.fromRunnable(() -> logger.debug("Logging: {}", notification)));
		if (!Utils.isEmpty(features.loggingConsumers())) {
			loggingConsumersFinal.addAll(features.loggingConsumers());
		}
		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_MESSAGE,
				asyncLoggingNotificationHandler(loggingConsumersFinal));

		this.mcpSession = new McpClientSession(requestTimeout, transport, requestHandlers, notificationHandlers);

	}

	/**
	 * 获取定义支持的特性和功能的服务器能力。
	 * @return 服务器能力
	 */
	public McpSchema.ServerCapabilities getServerCapabilities() {
		return this.serverCapabilities;
	}

	/**
	 * 获取为客户端提供如何与此服务器交互指导的服务器指令。
	 * @return 服务器指令
	 */
	public String getServerInstructions() {
		return this.serverInstructions;
	}

	/**
	 * 获取服务器实现信息。
	 * @return 服务器实现详情
	 */
	public McpSchema.Implementation getServerInfo() {
		return this.serverInfo;
	}

	/**
	 * 检查客户端-服务器连接是否已初始化。
	 * @return 如果客户端-服务器连接已初始化则返回true
	 */
	public boolean isInitialized() {
		return this.initialized.get();
	}

	/**
	 * 获取定义支持的特性和功能的客户端能力。
	 * @return 客户端能力
	 */
	public ClientCapabilities getClientCapabilities() {
		return this.clientCapabilities;
	}

	/**
	 * 获取客户端实现信息。
	 * @return 客户端实现详情
	 */
	public McpSchema.Implementation getClientInfo() {
		return this.clientInfo;
	}

	/**
	 * 立即关闭客户端连接。
	 */
	public void close() {
		this.mcpSession.close();
	}

	/**
	 * 优雅地关闭客户端连接。
	 * @return 当连接关闭时完成的Mono
	 */
	public Mono<Void> closeGracefully() {
		return this.mcpSession.closeGracefully();
	}

	// --------------------------
	// Initialization
	// --------------------------
	/**
	 * 初始化阶段必须是客户端和服务器之间的第一次交互。
	 * 在此阶段，客户端和服务器：
	 * <ul>
	 * <li>建立协议版本兼容性</li>
	 * <li>交换和协商能力</li>
	 * <li>共享实现细节</li>
	 * </ul>
	 * <br/>
	 * 客户端必须通过发送包含以下内容的初始化请求来启动此阶段：
	 * 客户端支持的协议版本、客户端的能力和客户端
	 * 实现信息。
	 * <p/>
	 * 服务器必须响应其自身的能力和信息。
	 * <p/>
	 * 初始化成功后，客户端必须发送已初始化通知
	 * 以表明它已准备好开始正常操作。
	 * @return 初始化结果。
	 * @see <a href=
	 * "https://github.com/modelcontextprotocol/specification/blob/main/docs/specification/basic/lifecycle.md#initialization">MCP
	 * 初始化规范</a>
	 */
	public Mono<McpSchema.InitializeResult> initialize() {

		String latestVersion = this.protocolVersions.get(this.protocolVersions.size() - 1);

		McpSchema.InitializeRequest initializeRequest = new McpSchema.InitializeRequest(// @formatter:off
				latestVersion,
				this.clientCapabilities,
				this.clientInfo); // @formatter:on

		Mono<McpSchema.InitializeResult> result = this.mcpSession.sendRequest(McpSchema.METHOD_INITIALIZE,
				initializeRequest, new TypeReference<McpSchema.InitializeResult>() {
				});

		return result.flatMap(initializeResult -> {

			this.serverCapabilities = initializeResult.capabilities();
			this.serverInstructions = initializeResult.instructions();
			this.serverInfo = initializeResult.serverInfo();

			logger.info("Server response with Protocol: {}, Capabilities: {}, Info: {} and Instructions {}",
					initializeResult.protocolVersion(), initializeResult.capabilities(), initializeResult.serverInfo(),
					initializeResult.instructions());

			if (!this.protocolVersions.contains(initializeResult.protocolVersion())) {
				return Mono.error(new McpError(
						"Unsupported protocol version from the server: " + initializeResult.protocolVersion()));
			}

			return this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED, null).doOnSuccess(v -> {
				this.initialized.set(true);
				this.initializedSink.tryEmitValue(initializeResult);
			}).thenReturn(initializeResult);
		});
	}

	/**
	 * 用于处理在执行操作前检查初始化的常见模式的实用方法。
	 * @param <T> 结果Mono的类型
	 * @param actionName 如果客户端已初始化要执行的操作
	 * @param operation 如果客户端已初始化要执行的操作
	 * @return 完成操作结果的Mono
	 */
	private <T> Mono<T> withInitializationCheck(String actionName,
			Function<McpSchema.InitializeResult, Mono<T>> operation) {
		return this.initializedSink.asMono()
			.timeout(this.initializationTimeout)
			.onErrorResume(TimeoutException.class,
					ex -> Mono.error(new McpError("Client must be initialized before " + actionName)))
			.flatMap(operation);
	}

	// --------------------------
	// Basic Utilities
	// --------------------------

	/**
	 * 向服务器发送ping请求。
	 * @return 完成服务器ping响应的Mono
	 */
	public Mono<Object> ping() {
		return this.withInitializationCheck("pinging the server", initializedResult -> this.mcpSession
			.sendRequest(McpSchema.METHOD_PING, null, new TypeReference<Object>() {
			}));
	}

	// --------------------------
	// Roots
	// --------------------------
	/**
	 * 向客户端的根列表添加新的根。
	 * @param root 要添加的根。
	 * @return 当根被添加且通知已发送时完成的Mono。
	 */
	public Mono<Void> addRoot(Root root) {

		if (root == null) {
			return Mono.error(new McpError("Root must not be null"));
		}

		if (this.clientCapabilities.roots() == null) {
			return Mono.error(new McpError("Client must be configured with roots capabilities"));
		}

		if (this.roots.containsKey(root.uri())) {
			return Mono.error(new McpError("Root with uri '" + root.uri() + "' already exists"));
		}

		this.roots.put(root.uri(), root);

		logger.debug("Added root: {}", root);

		if (this.clientCapabilities.roots().listChanged()) {
			if (this.isInitialized()) {
				return this.rootsListChangedNotification();
			}
			else {
				logger.warn("Client is not initialized, ignore sending a roots list changed notification");
			}
		}
		return Mono.empty();
	}

	/**
	 * 从客户端的根列表中移除一个根。
	 * @param rootUri 要移除的根的URI。
	 * @return 当根被移除且通知已发送时完成的Mono。
	 */
	public Mono<Void> removeRoot(String rootUri) {

		if (rootUri == null) {
			return Mono.error(new McpError("Root uri must not be null"));
		}

		if (this.clientCapabilities.roots() == null) {
			return Mono.error(new McpError("Client must be configured with roots capabilities"));
		}

		Root removed = this.roots.remove(rootUri);

		if (removed != null) {
			logger.debug("Removed Root: {}", rootUri);
			if (this.clientCapabilities.roots().listChanged()) {
				if (this.isInitialized()) {
					return this.rootsListChangedNotification();
				}
				else {
					logger.warn("Client is not initialized, ignore sending a roots list changed notification");
				}

			}
			return Mono.empty();
		}
		return Mono.error(new McpError("Root with uri '" + rootUri + "' not found"));
	}

	/**
	 * 手动发送roots/list_changed通知。如果客户端处于已初始化状态，
	 * addRoot和removeRoot方法会自动发送roots/list_changed通知。
	 * @return 当通知发送完成时完成的Mono。
	 */
	public Mono<Void> rootsListChangedNotification() {
		return this.withInitializationCheck("sending roots list changed notification",
				initResult -> this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_ROOTS_LIST_CHANGED));
	}

	private RequestHandler<McpSchema.ListRootsResult> rootsListRequestHandler() {
		return params -> {
			@SuppressWarnings("unused")
			McpSchema.PaginatedRequest request = transport.unmarshalFrom(params,
					new TypeReference<McpSchema.PaginatedRequest>() {
					});

			List<Root> roots = this.roots.values().stream().toList();

			return Mono.just(new McpSchema.ListRootsResult(roots));
		};
	}

	// --------------------------
	// Sampling
	// --------------------------
	private RequestHandler<CreateMessageResult> samplingCreateMessageHandler() {
		return params -> {
			McpSchema.CreateMessageRequest request = transport.unmarshalFrom(params,
					new TypeReference<McpSchema.CreateMessageRequest>() {
					});

			return this.samplingHandler.apply(request);
		};
	}

	// --------------------------
	// Tools
	// --------------------------
	private static final TypeReference<McpSchema.CallToolResult> CALL_TOOL_RESULT_TYPE_REF = new TypeReference<>() {
	};

	private static final TypeReference<McpSchema.ListToolsResult> LIST_TOOLS_RESULT_TYPE_REF = new TypeReference<>() {
	};

	/**
	 * 调用服务器提供的工具。工具使服务器能够暴露可执行的功能，
	 * 这些功能可以与外部系统交互、执行计算，并在现实世界中采取行动。
	 * @param callToolRequest 包含工具名称和输入参数的请求。
	 * @return 发出工具调用结果的Mono，包括输出和任何错误。
	 * @see McpSchema.CallToolRequest
	 * @see McpSchema.CallToolResult
	 * @see #listTools()
	 */
	public Mono<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest callToolRequest) {
		return this.withInitializationCheck("calling tools", initializedResult -> {
			if (this.serverCapabilities.tools() == null) {
				return Mono.error(new McpError("Server does not provide tools capability"));
			}
			return this.mcpSession.sendRequest(McpSchema.METHOD_TOOLS_CALL, callToolRequest, CALL_TOOL_RESULT_TYPE_REF);
		});
	}

	/**
	 * 获取服务器提供的所有工具列表。
	 * @return 发出工具列表结果的Mono。
	 */
	public Mono<McpSchema.ListToolsResult> listTools() {
		return this.listTools(null);
	}

	/**
	 * 获取服务器提供的分页工具列表。
	 * @param cursor 来自前一个列表请求的可选分页游标
	 * @return 发出工具列表结果的Mono
	 */
	public Mono<McpSchema.ListToolsResult> listTools(String cursor) {
		return this.withInitializationCheck("listing tools", initializedResult -> {
			if (this.serverCapabilities.tools() == null) {
				return Mono.error(new McpError("Server does not provide tools capability"));
			}
			return this.mcpSession.sendRequest(McpSchema.METHOD_TOOLS_LIST, new McpSchema.PaginatedRequest(cursor),
					LIST_TOOLS_RESULT_TYPE_REF);
		});
	}

	private NotificationHandler asyncToolsChangeNotificationHandler(
			List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers) {
		// TODO: params are not used yet
		return params -> this.listTools()
			.flatMap(listToolsResult -> Flux.fromIterable(toolsChangeConsumers)
				.flatMap(consumer -> consumer.apply(listToolsResult.tools()))
				.onErrorResume(error -> {
					logger.error("Error handling tools list change notification", error);
					return Mono.empty();
				})
				.then());
	}

	// --------------------------
	// Resources
	// --------------------------

	private static final TypeReference<McpSchema.ListResourcesResult> LIST_RESOURCES_RESULT_TYPE_REF = new TypeReference<>() {
	};

	private static final TypeReference<McpSchema.ReadResourceResult> READ_RESOURCE_RESULT_TYPE_REF = new TypeReference<>() {
	};

	private static final TypeReference<McpSchema.ListResourceTemplatesResult> LIST_RESOURCE_TEMPLATES_RESULT_TYPE_REF = new TypeReference<>() {
	};

	/**
	 * 获取服务器提供的所有资源列表。资源表示MCP服务器向客户端提供的任何
	 * UTF-8编码数据，如数据库记录、API响应、日志文件等。
	 * @return 完成资源列表结果的Mono。
	 * @see McpSchema.ListResourcesResult
	 * @see #readResource(McpSchema.Resource)
	 */
	public Mono<McpSchema.ListResourcesResult> listResources() {
		return this.listResources(null);
	}

	/**
	 * 获取服务器提供的分页资源列表。资源表示MCP服务器向客户端提供的任何
	 * UTF-8编码数据，如数据库记录、API响应、日志文件等。
	 * @param cursor 来自前一个列表请求的可选分页游标。
	 * @return 完成资源列表结果的Mono。
	 * @see McpSchema.ListResourcesResult
	 * @see #readResource(McpSchema.Resource)
	 */
	public Mono<McpSchema.ListResourcesResult> listResources(String cursor) {
		return this.withInitializationCheck("listing resources", initializedResult -> {
			if (this.serverCapabilities.resources() == null) {
				return Mono.error(new McpError("Server does not provide the resources capability"));
			}
			return this.mcpSession.sendRequest(McpSchema.METHOD_RESOURCES_LIST, new McpSchema.PaginatedRequest(cursor),
					LIST_RESOURCES_RESULT_TYPE_REF);
		});
	}

	/**
	 * Reads the content of a specific resource identified by the provided Resource
	 * object. This method fetches the actual data that the resource represents.
	 * @param resource The resource to read, containing the URI that identifies the
	 * resource.
	 * @return A Mono that completes with the resource content.
	 * @see McpSchema.Resource
	 * @see McpSchema.ReadResourceResult
	 */
	public Mono<McpSchema.ReadResourceResult> readResource(McpSchema.Resource resource) {
		return this.readResource(new McpSchema.ReadResourceRequest(resource.uri()));
	}

	/**
	 * 读取由提供的请求标识的特定资源的内容。此
	 * 方法获取资源表示的实际数据。
	 * @param readResourceRequest 包含要读取的资源URI的请求
	 * @return 完成资源内容的Mono。
	 * @see McpSchema.ReadResourceRequest
	 * @see McpSchema.ReadResourceResult
	 */
	public Mono<McpSchema.ReadResourceResult> readResource(McpSchema.ReadResourceRequest readResourceRequest) {
		return this.withInitializationCheck("reading resources", initializedResult -> {
			if (this.serverCapabilities.resources() == null) {
				return Mono.error(new McpError("Server does not provide the resources capability"));
			}
			return this.mcpSession.sendRequest(McpSchema.METHOD_RESOURCES_READ, readResourceRequest,
					READ_RESOURCE_RESULT_TYPE_REF);
		});
	}

	/**
	 * 获取服务器提供的所有资源模板列表。资源
	 * 模板允许服务器使用URI模板暴露参数化资源，
	 * 实现基于可变参数的动态资源访问。
	 * @return 完成资源模板列表结果的Mono。
	 * @see McpSchema.ListResourceTemplatesResult
	 */
	public Mono<McpSchema.ListResourceTemplatesResult> listResourceTemplates() {
		return this.listResourceTemplates(null);
	}

	/**
	 * 获取服务器提供的分页资源模板列表。资源
	 * 模板允许服务器使用URI模板暴露参数化资源，
	 * 实现基于可变参数的动态资源访问。
	 * @param cursor 来自前一个列表请求的可选分页游标。
	 * @return 完成资源模板列表结果的Mono。
	 * @see McpSchema.ListResourceTemplatesResult
	 */
	public Mono<McpSchema.ListResourceTemplatesResult> listResourceTemplates(String cursor) {
		return this.withInitializationCheck("listing resource templates", initializedResult -> {
			if (this.serverCapabilities.resources() == null) {
				return Mono.error(new McpError("Server does not provide the resources capability"));
			}
			return this.mcpSession.sendRequest(McpSchema.METHOD_RESOURCES_TEMPLATES_LIST,
					new McpSchema.PaginatedRequest(cursor), LIST_RESOURCE_TEMPLATES_RESULT_TYPE_REF);
		});
	}

	/**
	 * 订阅特定资源的变更。当服务器上的资源发生变更时，
	 * 客户端将通过资源变更通知处理器接收通知。
	 * @param subscribeRequest 包含资源URI的订阅请求。
	 * @return 当订阅完成时完成的Mono。
	 * @see McpSchema.SubscribeRequest
	 * @see #unsubscribeResource(McpSchema.UnsubscribeRequest)
	 */
	public Mono<Void> subscribeResource(McpSchema.SubscribeRequest subscribeRequest) {
		return this.withInitializationCheck("subscribing to resources", initializedResult -> this.mcpSession
			.sendRequest(McpSchema.METHOD_RESOURCES_SUBSCRIBE, subscribeRequest, VOID_TYPE_REFERENCE));
	}

	/**
	 * 取消对资源的现有订阅。取消订阅后，当资源
	 * 发生变更时，客户端将不再接收通知。
	 * @param unsubscribeRequest 包含资源URI的
	 * 取消订阅请求。
	 * @return 当取消订阅完成时完成的Mono。
	 * @see McpSchema.UnsubscribeRequest
	 * @see #subscribeResource(McpSchema.SubscribeRequest)
	 */
	public Mono<Void> unsubscribeResource(McpSchema.UnsubscribeRequest unsubscribeRequest) {
		return this.withInitializationCheck("unsubscribing from resources", initializedResult -> this.mcpSession
			.sendRequest(McpSchema.METHOD_RESOURCES_UNSUBSCRIBE, unsubscribeRequest, VOID_TYPE_REFERENCE));
	}

	private NotificationHandler asyncResourcesChangeNotificationHandler(
			List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers) {
		return params -> listResources().flatMap(listResourcesResult -> Flux.fromIterable(resourcesChangeConsumers)
			.flatMap(consumer -> consumer.apply(listResourcesResult.resources()))
			.onErrorResume(error -> {
				logger.error("Error handling resources list change notification", error);
				return Mono.empty();
			})
			.then());
	}

	// --------------------------
	// Prompts
	// --------------------------
	private static final TypeReference<McpSchema.ListPromptsResult> LIST_PROMPTS_RESULT_TYPE_REF = new TypeReference<>() {
	};

	private static final TypeReference<McpSchema.GetPromptResult> GET_PROMPT_RESULT_TYPE_REF = new TypeReference<>() {
	};

	/**
	 * 获取服务器提供的所有提示列表。
	 * @return 完成提示列表结果的Mono。
	 * @see McpSchema.ListPromptsResult
	 * @see #getPrompt(GetPromptRequest)
	 */
	public Mono<ListPromptsResult> listPrompts() {
		return this.listPrompts(null);
	}

	/**
	 * 获取服务器提供的分页提示列表。
	 * @param cursor 来自前一个列表请求的可选分页游标
	 * @return 完成提示列表结果的Mono。
	 * @see McpSchema.ListPromptsResult
	 * @see #getPrompt(GetPromptRequest)
	 */
	public Mono<ListPromptsResult> listPrompts(String cursor) {
		return this.withInitializationCheck("listing prompts", initializedResult -> this.mcpSession
			.sendRequest(McpSchema.METHOD_PROMPT_LIST, new PaginatedRequest(cursor), LIST_PROMPTS_RESULT_TYPE_REF));
	}

	/**
	 * 通过ID获取特定提示。这提供了完整的提示模板，
	 * 包括用于生成AI内容的所有参数和指令。
	 * @param getPromptRequest 包含要获取的提示ID的请求。
	 * @return 完成提示结果的Mono。
	 * @see McpSchema.GetPromptRequest
	 * @see McpSchema.GetPromptResult
	 * @see #listPrompts()
	 */
	public Mono<GetPromptResult> getPrompt(GetPromptRequest getPromptRequest) {
		return this.withInitializationCheck("getting prompts", initializedResult -> this.mcpSession
			.sendRequest(McpSchema.METHOD_PROMPT_GET, getPromptRequest, GET_PROMPT_RESULT_TYPE_REF));
	}

	private NotificationHandler asyncPromptsChangeNotificationHandler(
			List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers) {
		return params -> listPrompts().flatMap(listPromptsResult -> Flux.fromIterable(promptsChangeConsumers)
			.flatMap(consumer -> consumer.apply(listPromptsResult.prompts()))
			.onErrorResume(error -> {
				logger.error("Error handling prompts list change notification", error);
				return Mono.empty();
			})
			.then());
	}

	// --------------------------
	// Logging
	// --------------------------
	/**
	 * 为来自服务器的日志通知创建通知处理器。此
	 * 处理器自动将日志消息分发给所有注册的消费者。
	 * @param loggingConsumers 当收到日志消息时将被通知的消费者列表。
	 * 每个消费者都会收到日志消息通知。
	 * @return 通过将消息分发给所有注册消费者来处理日志通知的NotificationHandler
	 */
	private NotificationHandler asyncLoggingNotificationHandler(
			List<Function<LoggingMessageNotification, Mono<Void>>> loggingConsumers) {

		return params -> {
			McpSchema.LoggingMessageNotification loggingMessageNotification = transport.unmarshalFrom(params,
					new TypeReference<McpSchema.LoggingMessageNotification>() {
					});

			return Flux.fromIterable(loggingConsumers)
				.flatMap(consumer -> consumer.apply(loggingMessageNotification))
				.then();
		};
	}

	/**
	 * 设置从服务器接收的消息的最小日志级别。客户端
	 * 将只接收等于或高于指定严重性级别的日志消息。
	 * @param loggingLevel 要接收的最小日志级别。
	 * @return 当日志级别设置完成时完成的Mono。
	 * @see McpSchema.LoggingLevel
	 */
	public Mono<Void> setLoggingLevel(LoggingLevel loggingLevel) {
		if (loggingLevel == null) {
			return Mono.error(new McpError("Logging level must not be null"));
		}

		return this.withInitializationCheck("setting logging level", initializedResult -> {
			var params = new McpSchema.SetLevelRequest(loggingLevel);
			return this.mcpSession.sendRequest(McpSchema.METHOD_LOGGING_SET_LEVEL, params, new TypeReference<Object>() {
			}).then();
		});
	}

	/**
	 * 此方法是包私有的，仅用于测试。不应被用户
	 * 代码调用。
	 * @param protocolVersions 客户端支持的协议版本。
	 */
	void setProtocolVersions(List<String> protocolVersions) {
		this.protocolVersions = protocolVersions;
	}

	// --------------------------
	// Completions
	// --------------------------
	private static final TypeReference<McpSchema.CompleteResult> COMPLETION_COMPLETE_RESULT_TYPE_REF = new TypeReference<>() {
	};

	/**
	 * 发送completion/complete请求，基于给定的引用和参数
	 * 生成值建议。这通常用于为用户输入字段提供自动完成选项。
	 * @param completeRequest 包含提示或资源引用以及用于
	 * 生成完成的参数的请求。
	 * @return 完成包含完成建议的结果的Mono。
	 * @see McpSchema.CompleteRequest
	 * @see McpSchema.CompleteResult
	 */
	public Mono<McpSchema.CompleteResult> completeCompletion(McpSchema.CompleteRequest completeRequest) {
		return this.withInitializationCheck("complete completions", initializedResult -> this.mcpSession
			.sendRequest(McpSchema.METHOD_COMPLETION_COMPLETE, completeRequest, COMPLETION_COMPLETE_RESULT_TYPE_REF));
	}

}
