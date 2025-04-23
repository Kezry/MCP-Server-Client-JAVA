/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * 用于创建模型上下文协议(MCP)服务器的工厂类。MCP服务器通过标准化接口向AI模型暴露
 * 工具、资源和提示。
 *
 * <p>
 * 这个类作为实现MCP规范服务器端的主要入口点。服务器的职责包括：
 * <ul>
 * <li>暴露可供模型调用以执行操作的工具
 * <li>提供访问为模型提供上下文的资源
 * <li>管理用于结构化模型交互的提示模板
 * <li>处理客户端连接和请求
 * <li>实现能力协商
 * </ul>
 *
 * <p>
 * 线程安全性：同步和异步服务器实现都是线程安全的。同步服务器按顺序处理请求，而异步服务器
 * 可以通过其响应式编程模型安全地处理并发请求。
 *
 * <p>
 * 错误处理：服务器实现通过McpError类提供健壮的错误处理。错误会在保持服务器稳定性的同时
 * 正确传播给客户端。服务器实现应使用适当的错误代码并提供有意义的错误消息以帮助诊断问题。
 *
 * <p>
 * 该类提供工厂方法来创建以下两种服务器：
 * <ul>
 * <li>{@link McpAsyncServer} 用于带有响应式响应的非阻塞操作
 * <li>{@link McpSyncServer} 用于带有直接响应的阻塞操作
 * </ul>
 *
 * <p>
 * 创建基本同步服务器的示例：<pre>{@code
 * McpServer.sync(transportProvider)
 *     .serverInfo("my-server", "1.0.0")
 *     .tool(new Tool("calculator", "Performs calculations", schema),
 *           (exchange, args) -> new CallToolResult("Result: " + calculate(args)))
 *     .build();
 * }</pre>
 *
 * 创建基本异步服务器的示例：<pre>{@code
 * McpServer.async(transportProvider)
 *     .serverInfo("my-server", "1.0.0")
 *     .tool(new Tool("calculator", "Performs calculations", schema),
 *           (exchange, args) -> Mono.fromSupplier(() -> calculate(args))
 *               .map(result -> new CallToolResult("Result: " + result)))
 *     .build();
 * }</pre>
 *
 * <p>
 * 完整的异步配置示例：<pre>{@code
 * McpServer.async(transportProvider)
 *     .serverInfo("advanced-server", "2.0.0")
 *     .capabilities(new ServerCapabilities(...))
 *     // 注册工具
 *     .tools(
 *         new McpServerFeatures.AsyncToolSpecification(calculatorTool,
 *             (exchange, args) -> Mono.fromSupplier(() -> calculate(args))
 *                 .map(result -> new CallToolResult("Result: " + result))),
 *         new McpServerFeatures.AsyncToolSpecification(weatherTool,
 *             (exchange, args) -> Mono.fromSupplier(() -> getWeather(args))
 *                 .map(result -> new CallToolResult("Weather: " + result)))
 *     )
 *     // 注册资源
 *     .resources(
 *         new McpServerFeatures.AsyncResourceSpecification(fileResource,
 *             (exchange, req) -> Mono.fromSupplier(() -> readFile(req))
 *                 .map(ReadResourceResult::new)),
 *         new McpServerFeatures.AsyncResourceSpecification(dbResource,
 *             (exchange, req) -> Mono.fromSupplier(() -> queryDb(req))
 *                 .map(ReadResourceResult::new))
 *     )
 *     // 添加资源模板
 *     .resourceTemplates(
 *         new ResourceTemplate("file://{path}", "Access files"),
 *         new ResourceTemplate("db://{table}", "Access database")
 *     )
 *     // 注册提示
 *     .prompts(
 *         new McpServerFeatures.AsyncPromptSpecification(analysisPrompt,
 *             (exchange, req) -> Mono.fromSupplier(() -> generateAnalysisPrompt(req))
 *                 .map(GetPromptResult::new)),
 *         new McpServerFeatures.AsyncPromptRegistration(summaryPrompt,
 *             (exchange, req) -> Mono.fromSupplier(() -> generateSummaryPrompt(req))
 *                 .map(GetPromptResult::new))
 *     )
 *     .build();
 * }</pre>
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @author Jihoon Kim
 * @see McpAsyncServer
 * @see McpSyncServer
 * @see McpServerTransportProvider
 */
public interface McpServer {

	/**
	 * 开始构建提供阻塞操作的同步MCP服务器。
	 * 同步服务器在每个请求时阻塞当前线程的执行，直到将控制权返回给调用者，
	 * 这使得它们更容易实现，但在并发操作时可能不太可扩展。
	 * @param transportProvider MCP通信的传输层实现。
	 * @return 用于配置服务器的新{@link SyncSpecification}实例。
	 */
	static SyncSpecification sync(McpServerTransportProvider transportProvider) {
		return new SyncSpecification(transportProvider);
	}

	/**
	 * 开始构建提供非阻塞操作的异步MCP服务器。
	 * 异步服务器可以在单个线程上使用函数式范式和非阻塞服务器传输来并发处理多个请求，
	 * 这使得它们在高并发场景下更具可扩展性，但实现起来更复杂。
	 * @param transportProvider MCP通信的传输层实现。
	 * @return 用于配置服务器的新{@link AsyncSpecification}实例。
	 */
	static AsyncSpecification async(McpServerTransportProvider transportProvider) {
		return new AsyncSpecification(transportProvider);
	}

	/**
	 * Asynchronous server specification.
	 */
	class AsyncSpecification {

		private static final McpSchema.Implementation DEFAULT_SERVER_INFO = new McpSchema.Implementation("mcp-server",
				"1.0.0");

		private final McpServerTransportProvider transportProvider;

		private ObjectMapper objectMapper;

		private McpSchema.Implementation serverInfo = DEFAULT_SERVER_INFO;

		private McpSchema.ServerCapabilities serverCapabilities;

		private String instructions;

		/**
		 * 模型上下文协议(MCP)允许服务器暴露可被语言模型调用的工具。工具使模型能够与外部
		 * 系统交互，例如查询数据库、调用API或执行计算。每个工具都由唯一的名称标识，
		 * 并包含描述其模式的元数据。
		 */
		private final List<McpServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();

		/**
		 * 模型上下文协议(MCP)为服务器提供了一种标准化的方式来向客户端暴露资源。资源允许
		 * 服务器共享为语言模型提供上下文的数据，如文件、数据库模式或应用程序特定信息。
		 * 每个资源都由URI唯一标识。
		 */
		private final Map<String, McpServerFeatures.AsyncResourceSpecification> resources = new HashMap<>();

		private final List<ResourceTemplate> resourceTemplates = new ArrayList<>();

		/**
		 * 模型上下文协议(MCP)为服务器提供了一种标准化的方式来向客户端暴露提示模板。提示允许
		 * 服务器提供结构化的消息和指令，用于与语言模型交互。客户端可以发现可用的提示、
		 * 检索其内容，并提供参数来自定义它们。
		 */
		private final Map<String, McpServerFeatures.AsyncPromptSpecification> prompts = new HashMap<>();

		private final Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions = new HashMap<>();

		private final List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeHandlers = new ArrayList<>();

		private Duration requestTimeout = Duration.ofSeconds(10); // Default timeout

		private AsyncSpecification(McpServerTransportProvider transportProvider) {
			Assert.notNull(transportProvider, "Transport provider must not be null");
			this.transportProvider = transportProvider;
		}

		/**
		 * 设置在请求超时前等待服务器响应的持续时间。此超时适用于通过客户端发出的
		 * 所有请求，包括工具调用、资源访问和提示操作。
		 * @param requestTimeout 请求超时前的等待时间。不能为null。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果requestTimeout为null
		 */
		public AsyncSpecification requestTimeout(Duration requestTimeout) {
			Assert.notNull(requestTimeout, "Request timeout must not be null");
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * 设置将在连接初始化期间与客户端共享的服务器实现信息。这有助于版本兼容性、
		 * 调试和服务器标识。
		 * @param serverInfo 包含名称和版本的服务器实现详情。不能为null。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果serverInfo为null
		 */
		public AsyncSpecification serverInfo(McpSchema.Implementation serverInfo) {
			Assert.notNull(serverInfo, "Server info must not be null");
			this.serverInfo = serverInfo;
			return this;
		}

		/**
		 * 使用名称和版本字符串设置服务器实现信息。这是
		 * {@link #serverInfo(McpSchema.Implementation)}的一个便捷替代方法。
		 * @param name 服务器名称。不能为null或空。
		 * @param version 服务器版本。不能为null或空。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果name或version为null或空
		 * @see #serverInfo(McpSchema.Implementation)
		 */
		public AsyncSpecification serverInfo(String name, String version) {
			Assert.hasText(name, "Name must not be null or empty");
			Assert.hasText(version, "Version must not be null or empty");
			this.serverInfo = new McpSchema.Implementation(name, version);
			return this;
		}

		/**
		 * 设置将在连接初始化期间与客户端共享的服务器指令。这些指令为客户端提供
		 * 如何与此服务器交互的指导。
		 * @param instructions 指令文本。可以为null或空。
		 * @return 用于方法链式调用的构建器实例
		 */
		public AsyncSpecification instructions(String instructions) {
			this.instructions = instructions;
			return this;
		}

		/**
		 * 设置将在连接初始化期间向客户端公布的服务器能力。能力定义了服务器
		 * 支持的功能，例如：
		 * <ul>
		 * <li>工具执行
		 * <li>资源访问
		 * <li>提示处理
		 * </ul>
		 * @param serverCapabilities 服务器能力配置。不能为null。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果serverCapabilities为null
		 */
		public AsyncSpecification capabilities(McpSchema.ServerCapabilities serverCapabilities) {
			Assert.notNull(serverCapabilities, "Server capabilities must not be null");
			this.serverCapabilities = serverCapabilities;
			return this;
		}

		/**
		 * 向服务器添加单个工具及其实现处理程序。这是一个便捷方法，无需显式创建
		 * {@link McpServerFeatures.AsyncToolSpecification}即可注册单个工具。
		 *
		 * <p>
		 * 使用示例：<pre>{@code
		 * .tool(
		 *     new Tool("calculator", "Performs calculations", schema),
		 *     (exchange, args) -> Mono.fromSupplier(() -> calculate(args))
		 *         .map(result -> new CallToolResult("Result: " + result))
		 * )
		 * }</pre>
		 * @param tool 包含名称、描述和模式的工具定义。不能为null。
		 * @param handler 实现工具逻辑的函数。不能为null。
		 * 函数的第一个参数是{@link McpAsyncServerExchange}，服务器可以通过它
		 * 与已连接的客户端交互。第二个参数是传递给工具的参数映射。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果tool或handler为null
		 */
		public AsyncSpecification tool(McpSchema.Tool tool,
				BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<CallToolResult>> handler) {
			Assert.notNull(tool, "Tool must not be null");
			Assert.notNull(handler, "Handler must not be null");

			this.tools.add(new McpServerFeatures.AsyncToolSpecification(tool, handler));

			return this;
		}

		/**
		 * Adds multiple tools with their handlers to the server using a List. This method
		 * is useful when tools are dynamically generated or loaded from a configuration
		 * source.
		 * @param toolSpecifications The list of tool specifications to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolSpecifications is null
		 * @see #tools(McpServerFeatures.AsyncToolSpecification...)
		 */
		public AsyncSpecification tools(List<McpServerFeatures.AsyncToolSpecification> toolSpecifications) {
			Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
			this.tools.addAll(toolSpecifications);
			return this;
		}

		/**
		 * Adds multiple tools with their handlers to the server using varargs. This
		 * method provides a convenient way to register multiple tools inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .tools(
		 *     new McpServerFeatures.AsyncToolSpecification(calculatorTool, calculatorHandler),
		 *     new McpServerFeatures.AsyncToolSpecification(weatherTool, weatherHandler),
		 *     new McpServerFeatures.AsyncToolSpecification(fileManagerTool, fileManagerHandler)
		 * )
		 * }</pre>
		 * @param toolSpecifications The tool specifications to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolSpecifications is null
		 * @see #tools(List)
		 */
		public AsyncSpecification tools(McpServerFeatures.AsyncToolSpecification... toolSpecifications) {
			Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
			for (McpServerFeatures.AsyncToolSpecification tool : toolSpecifications) {
				this.tools.add(tool);
			}
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using a Map. This method is
		 * useful when resources are dynamically generated or loaded from a configuration
		 * source.
		 * @param resourceSpecifications Map of resource name to specification. Must not
		 * be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 * @see #resources(McpServerFeatures.AsyncResourceSpecification...)
		 */
		public AsyncSpecification resources(
				Map<String, McpServerFeatures.AsyncResourceSpecification> resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers map must not be null");
			this.resources.putAll(resourceSpecifications);
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using a List. This method is
		 * useful when resources need to be added in bulk from a collection.
		 * @param resourceSpecifications List of resource specifications. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 * @see #resources(McpServerFeatures.AsyncResourceSpecification...)
		 */
		public AsyncSpecification resources(List<McpServerFeatures.AsyncResourceSpecification> resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
			for (McpServerFeatures.AsyncResourceSpecification resource : resourceSpecifications) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using varargs. This method
		 * provides a convenient way to register multiple resources inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .resources(
		 *     new McpServerFeatures.AsyncResourceSpecification(fileResource, fileHandler),
		 *     new McpServerFeatures.AsyncResourceSpecification(dbResource, dbHandler),
		 *     new McpServerFeatures.AsyncResourceSpecification(apiResource, apiHandler)
		 * )
		 * }</pre>
		 * @param resourceSpecifications The resource specifications to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 */
		public AsyncSpecification resources(McpServerFeatures.AsyncResourceSpecification... resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
			for (McpServerFeatures.AsyncResourceSpecification resource : resourceSpecifications) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Sets the resource templates that define patterns for dynamic resource access.
		 * Templates use URI patterns with placeholders that can be filled at runtime.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .resourceTemplates(
		 *     new ResourceTemplate("file://{path}", "Access files by path"),
		 *     new ResourceTemplate("db://{table}/{id}", "Access database records")
		 * )
		 * }</pre>
		 * @param resourceTemplates List of resource templates. If null, clears existing
		 * templates.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceTemplates is null.
		 * @see #resourceTemplates(ResourceTemplate...)
		 */
		public AsyncSpecification resourceTemplates(List<ResourceTemplate> resourceTemplates) {
			Assert.notNull(resourceTemplates, "Resource templates must not be null");
			this.resourceTemplates.addAll(resourceTemplates);
			return this;
		}

		/**
		 * Sets the resource templates using varargs for convenience. This is an
		 * alternative to {@link #resourceTemplates(List)}.
		 * @param resourceTemplates The resource templates to set.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceTemplates is null.
		 * @see #resourceTemplates(List)
		 */
		public AsyncSpecification resourceTemplates(ResourceTemplate... resourceTemplates) {
			Assert.notNull(resourceTemplates, "Resource templates must not be null");
			for (ResourceTemplate resourceTemplate : resourceTemplates) {
				this.resourceTemplates.add(resourceTemplate);
			}
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a Map. This method is
		 * useful when prompts are dynamically generated or loaded from a configuration
		 * source.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .prompts(Map.of("analysis", new McpServerFeatures.AsyncPromptSpecification(
		 *     new Prompt("analysis", "Code analysis template"),
		 *     request -> Mono.fromSupplier(() -> generateAnalysisPrompt(request))
		 *         .map(GetPromptResult::new)
		 * )));
		 * }</pre>
		 * @param prompts Map of prompt name to specification. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public AsyncSpecification prompts(Map<String, McpServerFeatures.AsyncPromptSpecification> prompts) {
			Assert.notNull(prompts, "Prompts map must not be null");
			this.prompts.putAll(prompts);
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a List. This method is
		 * useful when prompts need to be added in bulk from a collection.
		 * @param prompts List of prompt specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 * @see #prompts(McpServerFeatures.AsyncPromptSpecification...)
		 */
		public AsyncSpecification prompts(List<McpServerFeatures.AsyncPromptSpecification> prompts) {
			Assert.notNull(prompts, "Prompts list must not be null");
			for (McpServerFeatures.AsyncPromptSpecification prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using varargs. This method
		 * provides a convenient way to register multiple prompts inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .prompts(
		 *     new McpServerFeatures.AsyncPromptSpecification(analysisPrompt, analysisHandler),
		 *     new McpServerFeatures.AsyncPromptSpecification(summaryPrompt, summaryHandler),
		 *     new McpServerFeatures.AsyncPromptSpecification(reviewPrompt, reviewHandler)
		 * )
		 * }</pre>
		 * @param prompts The prompt specifications to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public AsyncSpecification prompts(McpServerFeatures.AsyncPromptSpecification... prompts) {
			Assert.notNull(prompts, "Prompts list must not be null");
			for (McpServerFeatures.AsyncPromptSpecification prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * 注册一个在根列表发生变化时会被通知的消费者。这对于动态更新资源可用性很有用，
		 * 例如在添加或删除文件时。
		 * @param handler 要注册的处理程序。不能为null。函数的第一个参数是
		 * {@link McpAsyncServerExchange}，服务器可以通过它与已连接的客户端交互。
		 * 第二个参数是根列表。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果consumer为null
		 */
		public AsyncSpecification rootsChangeHandler(
				BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>> handler) {
			Assert.notNull(handler, "Consumer must not be null");
			this.rootsChangeHandlers.add(handler);
			return this;
		}

		/**
		 * 注册多个在根列表发生变化时会被通知的消费者。当需要一次注册多个消费者时，
		 * 这个方法很有用。
		 * @param handlers 要注册的处理程序列表。不能为null。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果consumers为null
		 * @see #rootsChangeHandler(BiFunction)
		 */
		public AsyncSpecification rootsChangeHandlers(
				List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> handlers) {
			Assert.notNull(handlers, "Handlers list must not be null");
			this.rootsChangeHandlers.addAll(handlers);
			return this;
		}

		/**
		 * 使用可变参数注册多个在根列表发生变化时会被通知的消费者。这个方法提供了
		 * 一种方便的方式来内联注册多个消费者。
		 * @param handlers 要注册的处理程序。不能为null。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果consumers为null
		 * @see #rootsChangeHandlers(List)
		 */
		public AsyncSpecification rootsChangeHandlers(
				@SuppressWarnings("unchecked") BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>... handlers) {
			Assert.notNull(handlers, "Handlers list must not be null");
			return this.rootsChangeHandlers(Arrays.asList(handlers));
		}

		/**
		 * 设置用于序列化和反序列化JSON消息的对象映射器。
		 * @param objectMapper 要使用的实例。不能为null。
		 * @return 用于方法链式调用的构建器实例。
		 * @throws IllegalArgumentException 如果objectMapper为null
		 */
		public AsyncSpecification objectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "ObjectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		/**
		 * 构建一个提供非阻塞操作的异步MCP服务器。
		 * @return 使用此构建器设置配置的新{@link McpAsyncServer}实例。
		 */
		public McpAsyncServer build() {
			var features = new McpServerFeatures.Async(this.serverInfo, this.serverCapabilities, this.tools,
					this.resources, this.resourceTemplates, this.prompts, this.completions, this.rootsChangeHandlers,
					this.instructions);
			var mapper = this.objectMapper != null ? this.objectMapper : new ObjectMapper();
			return new McpAsyncServer(this.transportProvider, mapper, features, this.requestTimeout);
		}

	}

	/**
	 * 同步服务器规范。
	 */
	class SyncSpecification {

		private static final McpSchema.Implementation DEFAULT_SERVER_INFO = new McpSchema.Implementation("mcp-server",
				"1.0.0");

		private final McpServerTransportProvider transportProvider;

		private ObjectMapper objectMapper;

		private McpSchema.Implementation serverInfo = DEFAULT_SERVER_INFO;

		private McpSchema.ServerCapabilities serverCapabilities;

		private String instructions;

		/**
		 * 模型上下文协议(MCP)允许服务器暴露可被语言模型调用的工具。工具使模型能够与外部
		 * 系统交互，例如查询数据库、调用API或执行计算。每个工具都由唯一的名称标识，
		 * 并包含描述其模式的元数据。
		 */
		private final List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

		/**
		 * 模型上下文协议(MCP)为服务器提供了一种标准化的方式来向客户端暴露资源。资源允许
		 * 服务器共享为语言模型提供上下文的数据，如文件、数据库模式或应用程序特定信息。
		 * 每个资源都由URI唯一标识。
		 */
		private final Map<String, McpServerFeatures.SyncResourceSpecification> resources = new HashMap<>();

		private final List<ResourceTemplate> resourceTemplates = new ArrayList<>();

		/**
		 * 模型上下文协议(MCP)为服务器提供了一种标准化的方式来向客户端暴露提示模板。提示允许
		 * 服务器提供结构化的消息和指令，用于与语言模型交互。客户端可以发现可用的提示、
		 * 检索其内容，并提供参数来自定义它们。
		 */
		private final Map<String, McpServerFeatures.SyncPromptSpecification> prompts = new HashMap<>();

		private final Map<McpSchema.CompleteReference, McpServerFeatures.SyncCompletionSpecification> completions = new HashMap<>();

		private final List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeHandlers = new ArrayList<>();

		private Duration requestTimeout = Duration.ofSeconds(10); // Default timeout

		private SyncSpecification(McpServerTransportProvider transportProvider) {
			Assert.notNull(transportProvider, "Transport provider must not be null");
			this.transportProvider = transportProvider;
		}

		/**
		 * 设置在请求超时前等待服务器响应的持续时间。此超时适用于通过客户端发出的
		 * 所有请求，包括工具调用、资源访问和提示操作。
		 * @param requestTimeout 请求超时前的等待时间。不能为null。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果requestTimeout为null
		 */
		public SyncSpecification requestTimeout(Duration requestTimeout) {
			Assert.notNull(requestTimeout, "Request timeout must not be null");
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * 设置将在连接初始化期间与客户端共享的服务器实现信息。这有助于版本兼容性、
		 * 调试和服务器标识。
		 * @param serverInfo 包含名称和版本的服务器实现详情。不能为null。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果serverInfo为null
		 */
		public SyncSpecification serverInfo(McpSchema.Implementation serverInfo) {
			Assert.notNull(serverInfo, "Server info must not be null");
			this.serverInfo = serverInfo;
			return this;
		}

		/**
		 * 使用名称和版本字符串设置服务器实现信息。这是
		 * {@link #serverInfo(McpSchema.Implementation)}的一个便捷替代方法。
		 * @param name 服务器名称。不能为null或空。
		 * @param version 服务器版本。不能为null或空。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果name或version为null或空
		 * @see #serverInfo(McpSchema.Implementation)
		 */
		public SyncSpecification serverInfo(String name, String version) {
			Assert.hasText(name, "Name must not be null or empty");
			Assert.hasText(version, "Version must not be null or empty");
			this.serverInfo = new McpSchema.Implementation(name, version);
			return this;
		}

		/**
		 * 设置将在连接初始化期间与客户端共享的服务器指令。这些指令为客户端提供
		 * 如何与此服务器交互的指导。
		 * @param instructions 指令文本。可以为null或空。
		 * @return 用于方法链式调用的构建器实例
		 */
		public SyncSpecification instructions(String instructions) {
			this.instructions = instructions;
			return this;
		}

		/**
		 * 设置将在连接初始化期间向客户端公布的服务器能力。能力定义了服务器
		 * 支持的功能，例如：
		 * <ul>
		 * <li>工具执行
		 * <li>资源访问
		 * <li>提示处理
		 * </ul>
		 * @param serverCapabilities 服务器能力配置。不能为null。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果serverCapabilities为null
		 */
		public SyncSpecification capabilities(McpSchema.ServerCapabilities serverCapabilities) {
			Assert.notNull(serverCapabilities, "Server capabilities must not be null");
			this.serverCapabilities = serverCapabilities;
			return this;
		}

		/**
		 * 向服务器添加单个工具及其实现处理程序。这是一个便捷方法，无需显式创建
		 * {@link McpServerFeatures.SyncToolSpecification}即可注册单个工具。
		 *
		 * <p>
		 * 使用示例：<pre>{@code
		 * .tool(
		 *     new Tool("calculator", "Performs calculations", schema),
		 *     (exchange, args) -> new CallToolResult("Result: " + calculate(args))
		 * )
		 * }</pre>
		 * @param tool 包含名称、描述和模式的工具定义。不能为null。
		 * @param handler 实现工具逻辑的函数。不能为null。
		 * 函数的第一个参数是{@link McpSyncServerExchange}，服务器可以通过它
		 * 与已连接的客户端交互。第二个参数是传递给工具的参数列表。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果tool或handler为null
		 */
		public SyncSpecification tool(McpSchema.Tool tool,
				BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler) {
			Assert.notNull(tool, "Tool must not be null");
			Assert.notNull(handler, "Handler must not be null");

			this.tools.add(new McpServerFeatures.SyncToolSpecification(tool, handler));

			return this;
		}

		/**
		 * 使用List向服务器添加多个工具及其处理程序。当工具是动态生成或从配置源
		 * 加载时，这个方法很有用。
		 * @param toolSpecifications 要添加的工具规范列表。不能为null。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果toolSpecifications为null
		 * @see #tools(McpServerFeatures.SyncToolSpecification...)
		 */
		public SyncSpecification tools(List<McpServerFeatures.SyncToolSpecification> toolSpecifications) {
			Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
			this.tools.addAll(toolSpecifications);
			return this;
		}

		/**
		 * 使用可变参数向服务器添加多个工具及其处理程序。这个方法提供了一种方便的
		 * 方式来内联注册多个工具。
		 *
		 * <p>
		 * 使用示例：<pre>{@code
		 * .tools(
		 *     new ToolSpecification(calculatorTool, calculatorHandler),
		 *     new ToolSpecification(weatherTool, weatherHandler),
		 *     new ToolSpecification(fileManagerTool, fileManagerHandler)
		 * )
		 * }</pre>
		 * @param toolSpecifications 要添加的工具规范。不能为null。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果toolSpecifications为null
		 * @see #tools(List)
		 */
		public SyncSpecification tools(McpServerFeatures.SyncToolSpecification... toolSpecifications) {
			Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
			for (McpServerFeatures.SyncToolSpecification tool : toolSpecifications) {
				this.tools.add(tool);
			}
			return this;
		}

		/**
		 * 使用Map注册多个资源及其处理程序。当资源是动态生成或从配置源加载时，
		 * 这个方法很有用。
		 * @param resourceSpecifications 资源名称到规范的映射。不能为null。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果resourceSpecifications为null
		 * @see #resources(McpServerFeatures.SyncResourceSpecification...)
		 */
		public SyncSpecification resources(
				Map<String, McpServerFeatures.SyncResourceSpecification> resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers map must not be null");
			this.resources.putAll(resourceSpecifications);
			return this;
		}

		/**
		 * 使用List注册多个资源及其处理程序。当需要从集合中批量添加资源时，
		 * 这个方法很有用。
		 * @param resourceSpecifications 资源规范列表。不能为null。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果resourceSpecifications为null
		 * @see #resources(McpServerFeatures.SyncResourceSpecification...)
		 */
		public SyncSpecification resources(List<McpServerFeatures.SyncResourceSpecification> resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
			for (McpServerFeatures.SyncResourceSpecification resource : resourceSpecifications) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using varargs. This method
		 * provides a convenient way to register multiple resources inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .resources(
		 *     new ResourceSpecification(fileResource, fileHandler),
		 *     new ResourceSpecification(dbResource, dbHandler),
		 *     new ResourceSpecification(apiResource, apiHandler)
		 * )
		 * }</pre>
		 * @param resourceSpecifications The resource specifications to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 */
		public SyncSpecification resources(McpServerFeatures.SyncResourceSpecification... resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
			for (McpServerFeatures.SyncResourceSpecification resource : resourceSpecifications) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Sets the resource templates that define patterns for dynamic resource access.
		 * Templates use URI patterns with placeholders that can be filled at runtime.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .resourceTemplates(
		 *     new ResourceTemplate("file://{path}", "Access files by path"),
		 *     new ResourceTemplate("db://{table}/{id}", "Access database records")
		 * )
		 * }</pre>
		 * @param resourceTemplates List of resource templates. If null, clears existing
		 * templates.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceTemplates is null.
		 * @see #resourceTemplates(ResourceTemplate...)
		 */
		public SyncSpecification resourceTemplates(List<ResourceTemplate> resourceTemplates) {
			Assert.notNull(resourceTemplates, "Resource templates must not be null");
			this.resourceTemplates.addAll(resourceTemplates);
			return this;
		}

		/**
		 * Sets the resource templates using varargs for convenience. This is an
		 * alternative to {@link #resourceTemplates(List)}.
		 * @param resourceTemplates The resource templates to set.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceTemplates is null
		 * @see #resourceTemplates(List)
		 */
		public SyncSpecification resourceTemplates(ResourceTemplate... resourceTemplates) {
			Assert.notNull(resourceTemplates, "Resource templates must not be null");
			for (ResourceTemplate resourceTemplate : resourceTemplates) {
				this.resourceTemplates.add(resourceTemplate);
			}
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a Map. This method is
		 * useful when prompts are dynamically generated or loaded from a configuration
		 * source.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * Map<String, PromptSpecification> prompts = new HashMap<>();
		 * prompts.put("analysis", new PromptSpecification(
		 *     new Prompt("analysis", "Code analysis template"),
		 *     (exchange, request) -> new GetPromptResult(generateAnalysisPrompt(request))
		 * ));
		 * .prompts(prompts)
		 * }</pre>
		 * @param prompts Map of prompt name to specification. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public SyncSpecification prompts(Map<String, McpServerFeatures.SyncPromptSpecification> prompts) {
			Assert.notNull(prompts, "Prompts map must not be null");
			this.prompts.putAll(prompts);
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a List. This method is
		 * useful when prompts need to be added in bulk from a collection.
		 * @param prompts List of prompt specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 * @see #prompts(McpServerFeatures.SyncPromptSpecification...)
		 */
		public SyncSpecification prompts(List<McpServerFeatures.SyncPromptSpecification> prompts) {
			Assert.notNull(prompts, "Prompts list must not be null");
			for (McpServerFeatures.SyncPromptSpecification prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using varargs. This method
		 * provides a convenient way to register multiple prompts inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .prompts(
		 *     new PromptSpecification(analysisPrompt, analysisHandler),
		 *     new PromptSpecification(summaryPrompt, summaryHandler),
		 *     new PromptSpecification(reviewPrompt, reviewHandler)
		 * )
		 * }</pre>
		 * @param prompts The prompt specifications to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public SyncSpecification prompts(McpServerFeatures.SyncPromptSpecification... prompts) {
			Assert.notNull(prompts, "Prompts list must not be null");
			for (McpServerFeatures.SyncPromptSpecification prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers multiple completions with their handlers using a List. This method is
		 * useful when completions need to be added in bulk from a collection.
		 * @param completions List of completion specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if completions is null
		 * @see #completions(McpServerFeatures.SyncCompletionSpecification...)
		 */
		public SyncSpecification completions(List<McpServerFeatures.SyncCompletionSpecification> completions) {
			Assert.notNull(completions, "Completions list must not be null");
			for (McpServerFeatures.SyncCompletionSpecification completion : completions) {
				this.completions.put(completion.referenceKey(), completion);
			}
			return this;
		}

		/**
		 * Registers multiple completions with their handlers using varargs. This method
		 * is useful when completions are defined inline and added directly.
		 * @param completions Array of completion specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if completions is null
		 */
		public SyncSpecification completions(McpServerFeatures.SyncCompletionSpecification... completions) {
			Assert.notNull(completions, "Completions list must not be null");
			for (McpServerFeatures.SyncCompletionSpecification completion : completions) {
				this.completions.put(completion.referenceKey(), completion);
			}
			return this;
		}

		/**
		 * Registers a consumer that will be notified when the list of roots changes. This
		 * is useful for updating resource availability dynamically, such as when new
		 * files are added or removed.
		 * @param handler The handler to register. Must not be null. The function's first
		 * argument is an {@link McpSyncServerExchange} upon which the server can interact
		 * with the connected client. The second argument is the list of roots.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumer is null
		 */
		public SyncSpecification rootsChangeHandler(BiConsumer<McpSyncServerExchange, List<McpSchema.Root>> handler) {
			Assert.notNull(handler, "Consumer must not be null");
			this.rootsChangeHandlers.add(handler);
			return this;
		}

		/**
		 * Registers multiple consumers that will be notified when the list of roots
		 * changes. This method is useful when multiple consumers need to be registered at
		 * once.
		 * @param handlers The list of handlers to register. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumers is null
		 * @see #rootsChangeHandler(BiConsumer)
		 */
		public SyncSpecification rootsChangeHandlers(
				List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> handlers) {
			Assert.notNull(handlers, "Handlers list must not be null");
			this.rootsChangeHandlers.addAll(handlers);
			return this;
		}

		/**
		 * 使用可变参数注册多个在根列表发生变化时会被通知的消费者。这个方法提供了
		 * 一种方便的方式来内联注册多个消费者。
		 * @param handlers 要注册的处理程序。不能为null。
		 * @return 用于方法链式调用的构建器实例
		 * @throws IllegalArgumentException 如果consumers为null
		 * @see #rootsChangeHandlers(List)
		 */
		public SyncSpecification rootsChangeHandlers(
				BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>... handlers) {
			Assert.notNull(handlers, "Handlers list must not be null");
			return this.rootsChangeHandlers(List.of(handlers));
		}

		/**
		 * 设置用于序列化和反序列化JSON消息的对象映射器。
		 * @param objectMapper 要使用的实例。不能为null。
		 * @return 用于方法链式调用的构建器实例。
		 * @throws IllegalArgumentException 如果objectMapper为null
		 */
		public SyncSpecification objectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "ObjectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		/**
		 * Builds a synchronous MCP server that provides blocking operations.
		 * @return A new instance of {@link McpSyncServer} configured with this builder's
		 * settings.
		 */
		public McpSyncServer build() {
			McpServerFeatures.Sync syncFeatures = new McpServerFeatures.Sync(this.serverInfo, this.serverCapabilities,
					this.tools, this.resources, this.resourceTemplates, this.prompts, this.completions,
					this.rootsChangeHandlers, this.instructions);
			McpServerFeatures.Async asyncFeatures = McpServerFeatures.Async.fromSync(syncFeatures);
			var mapper = this.objectMapper != null ? this.objectMapper : new ObjectMapper();
			var asyncServer = new McpAsyncServer(this.transportProvider, mapper, asyncFeatures, this.requestTimeout);

			return new McpSyncServer(asyncServer);
		}

	}

}
