/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.SetLevelRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 模型上下文协议(MCP)服务器实现，使用Project Reactor的Mono和Flux类型提供异步
 * 通信。
 *
 * <p>
 * 此服务器实现了MCP规范，使AI模型能够通过标准化接口暴露工具、资源和提示。
 * 主要特性包括：
 * <ul>
 * <li>使用响应式编程模式的异步通信
 * <li>动态工具注册和管理
 * <li>基于URI寻址的资源处理
 * <li>提示模板管理
 * <li>状态变更的实时客户端通知
 * <li>可配置严重级别的结构化日志记录
 * <li>支持客户端AI模型采样
 * </ul>
 *
 * <p>
 * 服务器遵循以下生命周期：
 * <ol>
 * <li>初始化 - 接受客户端连接并协商能力
 * <li>正常运行 - 处理客户端请求并发送通知
 * <li>优雅关闭 - 确保连接清理终止
 * </ol>
 *
 * <p>
 * 此实现使用Project Reactor进行非阻塞操作，使其适用于高吞吐量场景和响应式
 * 应用。所有操作都返回可以组合成响应式管道的Mono或Flux类型。
 *
 * <p>
 * 服务器支持通过{@link #addTool}、{@link #addResource}和{@link #addPrompt}等
 * 方法在运行时修改其能力，并在配置为这样做时自动通知已连接的客户端变更。
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @author Jihoon Kim
 * @see McpServer
 * @see McpSchema
 * @see McpClientSession
 */
public class McpAsyncServer {

	private static final Logger logger = LoggerFactory.getLogger(McpAsyncServer.class);

	private final McpAsyncServer delegate;

	McpAsyncServer() {
		this.delegate = null;
	}

	/**
	 * 使用给定的传输提供者和能力创建新的McpAsyncServer。
	 * @param mcpTransportProvider MCP通信的传输层实现。
	 * @param features MCP服务器支持的功能。
	 * @param objectMapper 用于JSON序列化/反序列化的ObjectMapper
	 */
	McpAsyncServer(McpServerTransportProvider mcpTransportProvider, ObjectMapper objectMapper,
			McpServerFeatures.Async features, Duration requestTimeout) {
		this.delegate = new AsyncServerImpl(mcpTransportProvider, objectMapper, requestTimeout, features);
	}

	/**
	 * 获取定义支持的特性和功能的服务器能力。
	 * @return 服务器能力
	 */
	public McpSchema.ServerCapabilities getServerCapabilities() {
		return this.delegate.getServerCapabilities();
	}

	/**
	 * 获取服务器实现信息。
	 * @return 服务器实现详情
	 */
	public McpSchema.Implementation getServerInfo() {
		return this.delegate.getServerInfo();
	}

	/**
	 * 优雅地关闭服务器，允许任何正在进行的操作完成。
	 * @return 当服务器已关闭时完成的Mono
	 */
	public Mono<Void> closeGracefully() {
		return this.delegate.closeGracefully();
	}

	/**
	 * 立即关闭服务器。
	 */
	public void close() {
		this.delegate.close();
	}

	// ---------------------------------------
	// Tool Management
	// ---------------------------------------
	/**
	 * 在运行时添加新的工具规范。
	 * @param toolSpecification 要添加的工具规范
	 * @return 当客户端已被通知变更时完成的Mono
	 */
	public Mono<Void> addTool(McpServerFeatures.AsyncToolSpecification toolSpecification) {
		return this.delegate.addTool(toolSpecification);
	}

	/**
	 * 在运行时移除工具处理程序。
	 * @param toolName 要移除的工具处理程序的名称
	 * @return 当客户端已被通知变更时完成的Mono
	 */
	public Mono<Void> removeTool(String toolName) {
		return this.delegate.removeTool(toolName);
	}

	/**
	 * 通知客户端可用工具列表已更改。
	 * @return 当所有客户端都已被通知时完成的Mono
	 */
	public Mono<Void> notifyToolsListChanged() {
		return this.delegate.notifyToolsListChanged();
	}

	// ---------------------------------------
	// Resource Management
	// ---------------------------------------
	/**
	 * 在运行时添加新的资源处理程序。
	 * @param resourceHandler 要添加的资源处理程序
	 * @return 当客户端已被通知变更时完成的Mono
	 */
	public Mono<Void> addResource(McpServerFeatures.AsyncResourceSpecification resourceHandler) {
		return this.delegate.addResource(resourceHandler);
	}

	/**
	 * 在运行时移除资源处理程序。
	 * @param resourceUri 要移除的资源处理程序的URI
	 * @return 当客户端已被通知变更时完成的Mono
	 */
	public Mono<Void> removeResource(String resourceUri) {
		return this.delegate.removeResource(resourceUri);
	}

	/**
	 * 通知客户端可用资源列表已更改。
	 * @return 当所有客户端都已被通知时完成的Mono
	 */
	public Mono<Void> notifyResourcesListChanged() {
		return this.delegate.notifyResourcesListChanged();
	}

	// ---------------------------------------
	// Prompt Management
	// ---------------------------------------
	/**
	 * 在运行时添加新的提示处理程序。
	 * @param promptSpecification 要添加的提示处理程序
	 * @return 当客户端已被通知变更时完成的Mono
	 */
	public Mono<Void> addPrompt(McpServerFeatures.AsyncPromptSpecification promptSpecification) {
		return this.delegate.addPrompt(promptSpecification);
	}

	/**
	 * Remove a prompt handler at runtime.
	 * @param promptName The name of the prompt handler to remove
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> removePrompt(String promptName) {
		return this.delegate.removePrompt(promptName);
	}

	/**
	 * Notifies clients that the list of available prompts has changed.
	 * @return A Mono that completes when all clients have been notified
	 */
	public Mono<Void> notifyPromptsListChanged() {
		return this.delegate.notifyPromptsListChanged();
	}

	// ---------------------------------------
	// Logging Management
	// ---------------------------------------

	/**
	 * This implementation would, incorrectly, broadcast the logging message to all
	 * connected clients, using a single minLoggingLevel for all of them. Similar to the
	 * sampling and roots, the logging level should be set per client session and use the
	 * ServerExchange to send the logging message to the right client.
	 * @param loggingMessageNotification The logging message to send
	 * @return A Mono that completes when the notification has been sent
	 * @deprecated Use
	 * {@link McpAsyncServerExchange#loggingNotification(LoggingMessageNotification)}
	 * instead.
	 */
	@Deprecated
	public Mono<Void> loggingNotification(LoggingMessageNotification loggingMessageNotification) {
		return this.delegate.loggingNotification(loggingMessageNotification);
	}

	// ---------------------------------------
	// Sampling
	// ---------------------------------------
	/**
	 * This method is package-private and used for test only. Should not be called by user
	 * code.
	 * @param protocolVersions the Client supported protocol versions.
	 */
	void setProtocolVersions(List<String> protocolVersions) {
		this.delegate.setProtocolVersions(protocolVersions);
	}

	private static class AsyncServerImpl extends McpAsyncServer {

		private final McpServerTransportProvider mcpTransportProvider;

		private final ObjectMapper objectMapper;

		private final McpSchema.ServerCapabilities serverCapabilities;

		private final McpSchema.Implementation serverInfo;

		private final String instructions;

		private final CopyOnWriteArrayList<McpServerFeatures.AsyncToolSpecification> tools = new CopyOnWriteArrayList<>();

		private final CopyOnWriteArrayList<McpSchema.ResourceTemplate> resourceTemplates = new CopyOnWriteArrayList<>();

		private final ConcurrentHashMap<String, McpServerFeatures.AsyncResourceSpecification> resources = new ConcurrentHashMap<>();

		private final ConcurrentHashMap<String, McpServerFeatures.AsyncPromptSpecification> prompts = new ConcurrentHashMap<>();

		// FIXME: this field is deprecated and should be remvoed together with the
		// broadcasting loggingNotification.
		private LoggingLevel minLoggingLevel = LoggingLevel.DEBUG;

		private final ConcurrentHashMap<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions = new ConcurrentHashMap<>();

		private List<String> protocolVersions = List.of(McpSchema.LATEST_PROTOCOL_VERSION);

		AsyncServerImpl(McpServerTransportProvider mcpTransportProvider, ObjectMapper objectMapper,
				Duration requestTimeout, McpServerFeatures.Async features) {
			this.mcpTransportProvider = mcpTransportProvider;
			this.objectMapper = objectMapper;
			this.serverInfo = features.serverInfo();
			this.serverCapabilities = features.serverCapabilities();
			this.instructions = features.instructions();
			this.tools.addAll(features.tools());
			this.resources.putAll(features.resources());
			this.resourceTemplates.addAll(features.resourceTemplates());
			this.prompts.putAll(features.prompts());
			this.completions.putAll(features.completions());

			Map<String, McpServerSession.RequestHandler<?>> requestHandlers = new HashMap<>();

			// Initialize request handlers for standard MCP methods

			// Ping MUST respond with an empty data, but not NULL response.
			requestHandlers.put(McpSchema.METHOD_PING, (exchange, params) -> Mono.just(Map.of()));

			// Add tools API handlers if the tool capability is enabled
			if (this.serverCapabilities.tools() != null) {
				requestHandlers.put(McpSchema.METHOD_TOOLS_LIST, toolsListRequestHandler());
				requestHandlers.put(McpSchema.METHOD_TOOLS_CALL, toolsCallRequestHandler());
			}

			// Add resources API handlers if provided
			if (this.serverCapabilities.resources() != null) {
				requestHandlers.put(McpSchema.METHOD_RESOURCES_LIST, resourcesListRequestHandler());
				requestHandlers.put(McpSchema.METHOD_RESOURCES_READ, resourcesReadRequestHandler());
				requestHandlers.put(McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, resourceTemplateListRequestHandler());
			}

			// Add prompts API handlers if provider exists
			if (this.serverCapabilities.prompts() != null) {
				requestHandlers.put(McpSchema.METHOD_PROMPT_LIST, promptsListRequestHandler());
				requestHandlers.put(McpSchema.METHOD_PROMPT_GET, promptsGetRequestHandler());
			}

			// Add logging API handlers if the logging capability is enabled
			if (this.serverCapabilities.logging() != null) {
				requestHandlers.put(McpSchema.METHOD_LOGGING_SET_LEVEL, setLoggerRequestHandler());
			}

			// Add completion API handlers if the completion capability is enabled
			if (this.serverCapabilities.completions() != null) {
				requestHandlers.put(McpSchema.METHOD_COMPLETION_COMPLETE, completionCompleteRequestHandler());
			}

			Map<String, McpServerSession.NotificationHandler> notificationHandlers = new HashMap<>();

			notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_INITIALIZED, (exchange, params) -> Mono.empty());

			List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers = features
				.rootsChangeConsumers();

			if (Utils.isEmpty(rootsChangeConsumers)) {
				rootsChangeConsumers = List.of((exchange,
						roots) -> Mono.fromRunnable(() -> logger.warn(
								"Roots list changed notification, but no consumers provided. Roots list changed: {}",
								roots)));
			}

			notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_ROOTS_LIST_CHANGED,
					asyncRootsListChangedNotificationHandler(rootsChangeConsumers));

			mcpTransportProvider.setSessionFactory(
					transport -> new McpServerSession(UUID.randomUUID().toString(), requestTimeout, transport,
							this::asyncInitializeRequestHandler, Mono::empty, requestHandlers, notificationHandlers));
		}

		// ---------------------------------------
		// Lifecycle Management
		// ---------------------------------------
		private Mono<McpSchema.InitializeResult> asyncInitializeRequestHandler(
				McpSchema.InitializeRequest initializeRequest) {
			return Mono.defer(() -> {
				logger.info("Client initialize request - Protocol: {}, Capabilities: {}, Info: {}",
						initializeRequest.protocolVersion(), initializeRequest.capabilities(),
						initializeRequest.clientInfo());

				// The server MUST respond with the highest protocol version it supports
				// if
				// it does not support the requested (e.g. Client) version.
				String serverProtocolVersion = this.protocolVersions.get(this.protocolVersions.size() - 1);

				if (this.protocolVersions.contains(initializeRequest.protocolVersion())) {
					// If the server supports the requested protocol version, it MUST
					// respond
					// with the same version.
					serverProtocolVersion = initializeRequest.protocolVersion();
				}
				else {
					logger.warn(
							"Client requested unsupported protocol version: {}, so the server will suggest the {} version instead",
							initializeRequest.protocolVersion(), serverProtocolVersion);
				}

				return Mono.just(new McpSchema.InitializeResult(serverProtocolVersion, this.serverCapabilities,
						this.serverInfo, this.instructions));
			});
		}

		public McpSchema.ServerCapabilities getServerCapabilities() {
			return this.serverCapabilities;
		}

		public McpSchema.Implementation getServerInfo() {
			return this.serverInfo;
		}

		@Override
		public Mono<Void> closeGracefully() {
			return this.mcpTransportProvider.closeGracefully();
		}

		@Override
		public void close() {
			this.mcpTransportProvider.close();
		}

		private McpServerSession.NotificationHandler asyncRootsListChangedNotificationHandler(
				List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers) {
			return (exchange, params) -> exchange.listRoots()
				.flatMap(listRootsResult -> Flux.fromIterable(rootsChangeConsumers)
					.flatMap(consumer -> consumer.apply(exchange, listRootsResult.roots()))
					.onErrorResume(error -> {
						logger.error("Error handling roots list change notification", error);
						return Mono.empty();
					})
					.then());
		}

		// ---------------------------------------
		// Tool Management
		// ---------------------------------------

		@Override
		public Mono<Void> addTool(McpServerFeatures.AsyncToolSpecification toolSpecification) {
			if (toolSpecification == null) {
				return Mono.error(new McpError("Tool specification must not be null"));
			}
			if (toolSpecification.tool() == null) {
				return Mono.error(new McpError("Tool must not be null"));
			}
			if (toolSpecification.call() == null) {
				return Mono.error(new McpError("Tool call handler must not be null"));
			}
			if (this.serverCapabilities.tools() == null) {
				return Mono.error(new McpError("Server must be configured with tool capabilities"));
			}

			return Mono.defer(() -> {
				// Check for duplicate tool names
				if (this.tools.stream().anyMatch(th -> th.tool().name().equals(toolSpecification.tool().name()))) {
					return Mono
						.error(new McpError("Tool with name '" + toolSpecification.tool().name() + "' already exists"));
				}

				this.tools.add(toolSpecification);
				logger.debug("Added tool handler: {}", toolSpecification.tool().name());

				if (this.serverCapabilities.tools().listChanged()) {
					return notifyToolsListChanged();
				}
				return Mono.empty();
			});
		}

		@Override
		public Mono<Void> removeTool(String toolName) {
			if (toolName == null) {
				return Mono.error(new McpError("Tool name must not be null"));
			}
			if (this.serverCapabilities.tools() == null) {
				return Mono.error(new McpError("Server must be configured with tool capabilities"));
			}

			return Mono.defer(() -> {
				boolean removed = this.tools
					.removeIf(toolSpecification -> toolSpecification.tool().name().equals(toolName));
				if (removed) {
					logger.debug("Removed tool handler: {}", toolName);
					if (this.serverCapabilities.tools().listChanged()) {
						return notifyToolsListChanged();
					}
					return Mono.empty();
				}
				return Mono.error(new McpError("Tool with name '" + toolName + "' not found"));
			});
		}

		@Override
		public Mono<Void> notifyToolsListChanged() {
			return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, null);
		}

		private McpServerSession.RequestHandler<McpSchema.ListToolsResult> toolsListRequestHandler() {
			return (exchange, params) -> {
				List<Tool> tools = this.tools.stream().map(McpServerFeatures.AsyncToolSpecification::tool).toList();

				return Mono.just(new McpSchema.ListToolsResult(tools, null));
			};
		}

		private McpServerSession.RequestHandler<CallToolResult> toolsCallRequestHandler() {
			return (exchange, params) -> {
				McpSchema.CallToolRequest callToolRequest = objectMapper.convertValue(params,
						new TypeReference<McpSchema.CallToolRequest>() {
						});

				Optional<McpServerFeatures.AsyncToolSpecification> toolSpecification = this.tools.stream()
					.filter(tr -> callToolRequest.name().equals(tr.tool().name()))
					.findAny();

				if (toolSpecification.isEmpty()) {
					return Mono.error(new McpError("Tool not found: " + callToolRequest.name()));
				}

				return toolSpecification.map(tool -> tool.call().apply(exchange, callToolRequest.arguments()))
					.orElse(Mono.error(new McpError("Tool not found: " + callToolRequest.name())));
			};
		}

		// ---------------------------------------
		// Resource Management
		// ---------------------------------------

		@Override
		public Mono<Void> addResource(McpServerFeatures.AsyncResourceSpecification resourceSpecification) {
			if (resourceSpecification == null || resourceSpecification.resource() == null) {
				return Mono.error(new McpError("Resource must not be null"));
			}

			if (this.serverCapabilities.resources() == null) {
				return Mono.error(new McpError("Server must be configured with resource capabilities"));
			}

			return Mono.defer(() -> {
				if (this.resources.putIfAbsent(resourceSpecification.resource().uri(), resourceSpecification) != null) {
					return Mono.error(new McpError(
							"Resource with URI '" + resourceSpecification.resource().uri() + "' already exists"));
				}
				logger.debug("Added resource handler: {}", resourceSpecification.resource().uri());
				if (this.serverCapabilities.resources().listChanged()) {
					return notifyResourcesListChanged();
				}
				return Mono.empty();
			});
		}

		@Override
		public Mono<Void> removeResource(String resourceUri) {
			if (resourceUri == null) {
				return Mono.error(new McpError("Resource URI must not be null"));
			}
			if (this.serverCapabilities.resources() == null) {
				return Mono.error(new McpError("Server must be configured with resource capabilities"));
			}

			return Mono.defer(() -> {
				McpServerFeatures.AsyncResourceSpecification removed = this.resources.remove(resourceUri);
				if (removed != null) {
					logger.debug("Removed resource handler: {}", resourceUri);
					if (this.serverCapabilities.resources().listChanged()) {
						return notifyResourcesListChanged();
					}
					return Mono.empty();
				}
				return Mono.error(new McpError("Resource with URI '" + resourceUri + "' not found"));
			});
		}

		@Override
		public Mono<Void> notifyResourcesListChanged() {
			return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED, null);
		}

		private McpServerSession.RequestHandler<McpSchema.ListResourcesResult> resourcesListRequestHandler() {
			return (exchange, params) -> {
				var resourceList = this.resources.values()
					.stream()
					.map(McpServerFeatures.AsyncResourceSpecification::resource)
					.toList();
				return Mono.just(new McpSchema.ListResourcesResult(resourceList, null));
			};
		}

		private McpServerSession.RequestHandler<McpSchema.ListResourceTemplatesResult> resourceTemplateListRequestHandler() {
			return (exchange, params) -> Mono
				.just(new McpSchema.ListResourceTemplatesResult(this.resourceTemplates, null));

		}

		private McpServerSession.RequestHandler<McpSchema.ReadResourceResult> resourcesReadRequestHandler() {
			return (exchange, params) -> {
				McpSchema.ReadResourceRequest resourceRequest = objectMapper.convertValue(params,
						new TypeReference<McpSchema.ReadResourceRequest>() {
						});
				var resourceUri = resourceRequest.uri();
				McpServerFeatures.AsyncResourceSpecification specification = this.resources.get(resourceUri);
				if (specification != null) {
					return specification.readHandler().apply(exchange, resourceRequest);
				}
				return Mono.error(new McpError("Resource not found: " + resourceUri));
			};
		}

		// ---------------------------------------
		// Prompt Management
		// ---------------------------------------

		@Override
		public Mono<Void> addPrompt(McpServerFeatures.AsyncPromptSpecification promptSpecification) {
			if (promptSpecification == null) {
				return Mono.error(new McpError("Prompt specification must not be null"));
			}
			if (this.serverCapabilities.prompts() == null) {
				return Mono.error(new McpError("Server must be configured with prompt capabilities"));
			}

			return Mono.defer(() -> {
				McpServerFeatures.AsyncPromptSpecification specification = this.prompts
					.putIfAbsent(promptSpecification.prompt().name(), promptSpecification);
				if (specification != null) {
					return Mono.error(new McpError(
							"Prompt with name '" + promptSpecification.prompt().name() + "' already exists"));
				}

				logger.debug("Added prompt handler: {}", promptSpecification.prompt().name());

				// Servers that declared the listChanged capability SHOULD send a
				// notification,
				// when the list of available prompts changes
				if (this.serverCapabilities.prompts().listChanged()) {
					return notifyPromptsListChanged();
				}
				return Mono.empty();
			});
		}

		@Override
		public Mono<Void> removePrompt(String promptName) {
			if (promptName == null) {
				return Mono.error(new McpError("Prompt name must not be null"));
			}
			if (this.serverCapabilities.prompts() == null) {
				return Mono.error(new McpError("Server must be configured with prompt capabilities"));
			}

			return Mono.defer(() -> {
				McpServerFeatures.AsyncPromptSpecification removed = this.prompts.remove(promptName);

				if (removed != null) {
					logger.debug("Removed prompt handler: {}", promptName);
					// Servers that declared the listChanged capability SHOULD send a
					// notification, when the list of available prompts changes
					if (this.serverCapabilities.prompts().listChanged()) {
						return this.notifyPromptsListChanged();
					}
					return Mono.empty();
				}
				return Mono.error(new McpError("Prompt with name '" + promptName + "' not found"));
			});
		}

		@Override
		public Mono<Void> notifyPromptsListChanged() {
			return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, null);
		}

		private McpServerSession.RequestHandler<McpSchema.ListPromptsResult> promptsListRequestHandler() {
			return (exchange, params) -> {
				// TODO: Implement pagination
				// McpSchema.PaginatedRequest request = objectMapper.convertValue(params,
				// new TypeReference<McpSchema.PaginatedRequest>() {
				// });

				var promptList = this.prompts.values()
					.stream()
					.map(McpServerFeatures.AsyncPromptSpecification::prompt)
					.toList();

				return Mono.just(new McpSchema.ListPromptsResult(promptList, null));
			};
		}

		private McpServerSession.RequestHandler<McpSchema.GetPromptResult> promptsGetRequestHandler() {
			return (exchange, params) -> {
				McpSchema.GetPromptRequest promptRequest = objectMapper.convertValue(params,
						new TypeReference<McpSchema.GetPromptRequest>() {
						});

				// Implement prompt retrieval logic here
				McpServerFeatures.AsyncPromptSpecification specification = this.prompts.get(promptRequest.name());
				if (specification == null) {
					return Mono.error(new McpError("Prompt not found: " + promptRequest.name()));
				}

				return specification.promptHandler().apply(exchange, promptRequest);
			};
		}

		// ---------------------------------------
		// Logging Management
		// ---------------------------------------

		@Override
		public Mono<Void> loggingNotification(LoggingMessageNotification loggingMessageNotification) {

			if (loggingMessageNotification == null) {
				return Mono.error(new McpError("Logging message must not be null"));
			}

			if (loggingMessageNotification.level().level() < minLoggingLevel.level()) {
				return Mono.empty();
			}

			return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_MESSAGE,
					loggingMessageNotification);
		}

		private McpServerSession.RequestHandler<Object> setLoggerRequestHandler() {
			return (exchange, params) -> {
				return Mono.defer(() -> {

					SetLevelRequest newMinLoggingLevel = objectMapper.convertValue(params,
							new TypeReference<SetLevelRequest>() {
							});

					exchange.setMinLoggingLevel(newMinLoggingLevel.level());

					// FIXME: this field is deprecated and should be removed together
					// with the broadcasting loggingNotification.
					this.minLoggingLevel = newMinLoggingLevel.level();

					return Mono.just(Map.of());
				});
			};
		}

		private McpServerSession.RequestHandler<McpSchema.CompleteResult> completionCompleteRequestHandler() {
			return (exchange, params) -> {
				McpSchema.CompleteRequest request = parseCompletionParams(params);

				if (request.ref() == null) {
					return Mono.error(new McpError("ref must not be null"));
				}

				if (request.ref().type() == null) {
					return Mono.error(new McpError("type must not be null"));
				}

				String type = request.ref().type();

				// check if the referenced resource exists
				if (type.equals("ref/prompt") && request.ref() instanceof McpSchema.PromptReference promptReference) {
					McpServerFeatures.AsyncPromptSpecification prompt = this.prompts.get(promptReference.name());
					if (prompt == null) {
						return Mono.error(new McpError("Prompt not found: " + promptReference.name()));
					}
				}

				if (type.equals("ref/resource")
						&& request.ref() instanceof McpSchema.ResourceReference resourceReference) {
					McpServerFeatures.AsyncResourceSpecification resource = this.resources.get(resourceReference.uri());
					if (resource == null) {
						return Mono.error(new McpError("Resource not found: " + resourceReference.uri()));
					}
				}

				McpServerFeatures.AsyncCompletionSpecification specification = this.completions.get(request.ref());

				if (specification == null) {
					return Mono.error(new McpError("AsyncCompletionSpecification not found: " + request.ref()));
				}

				return specification.completionHandler().apply(exchange, request);
			};
		}

		/**
		 * Parses the raw JSON-RPC request parameters into a
		 * {@link McpSchema.CompleteRequest} object.
		 * <p>
		 * This method manually extracts the `ref` and `argument` fields from the input
		 * map, determines the correct reference type (either prompt or resource), and
		 * constructs a fully-typed {@code CompleteRequest} instance.
		 * @param object the raw request parameters, expected to be a Map containing "ref"
		 * and "argument" entries.
		 * @return a {@link McpSchema.CompleteRequest} representing the structured
		 * completion request.
		 * @throws IllegalArgumentException if the "ref" type is not recognized.
		 */
		@SuppressWarnings("unchecked")
		private McpSchema.CompleteRequest parseCompletionParams(Object object) {
			Map<String, Object> params = (Map<String, Object>) object;
			Map<String, Object> refMap = (Map<String, Object>) params.get("ref");
			Map<String, Object> argMap = (Map<String, Object>) params.get("argument");

			String refType = (String) refMap.get("type");

			McpSchema.CompleteReference ref = switch (refType) {
				case "ref/prompt" -> new McpSchema.PromptReference(refType, (String) refMap.get("name"));
				case "ref/resource" -> new McpSchema.ResourceReference(refType, (String) refMap.get("uri"));
				default -> throw new IllegalArgumentException("Invalid ref type: " + refType);
			};

			String argName = (String) argMap.get("name");
			String argValue = (String) argMap.get("value");
			McpSchema.CompleteRequest.CompleteArgument argument = new McpSchema.CompleteRequest.CompleteArgument(
					argName, argValue);

			return new McpSchema.CompleteRequest(ref, argument);
		}

		// ---------------------------------------
		// Sampling
		// ---------------------------------------

		@Override
		void setProtocolVersions(List<String> protocolVersions) {
			this.protocolVersions = protocolVersions;
		}

	}

}
