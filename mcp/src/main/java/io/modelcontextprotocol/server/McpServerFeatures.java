/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * MCP server features specification that a particular server can choose to support.
 *
 * @author Dariusz Jędrzejczyk
 * @author Jihoon Kim
 */
public class McpServerFeatures {

	/**
	 * Asynchronous server features specification.
	 *
	 * @param serverInfo The server implementation details
	 * @param serverCapabilities The server capabilities
	 * @param tools The list of tool specifications
	 * @param resources The map of resource specifications
	 * @param resourceTemplates The list of resource templates
	 * @param prompts The map of prompt specifications
	 * @param rootsChangeConsumers The list of consumers that will be notified when the
	 * roots list changes
	 * @param instructions The server instructions text
	 */
	record Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
			List<McpServerFeatures.AsyncToolSpecification> tools, Map<String, AsyncResourceSpecification> resources,
			List<McpSchema.ResourceTemplate> resourceTemplates,
			Map<String, McpServerFeatures.AsyncPromptSpecification> prompts,
			Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions,
			List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers,
			String instructions) {

		/**
		 * 创建实例并验证参数。
		 * @param serverInfo 服务器实现详情
		 * @param serverCapabilities 服务器能力
		 * @param tools 工具规范列表
		 * @param resources 资源规范映射
		 * @param resourceTemplates 资源模板列表
		 * @param prompts 提示规范映射
		 * @param completions 完成规范映射
		 * @param rootsChangeConsumers 当根目录列表发生变化时将被通知的消费者列表
		 * @param instructions 服务器指令文本
		 */
		Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpServerFeatures.AsyncToolSpecification> tools, Map<String, AsyncResourceSpecification> resources,
				List<McpSchema.ResourceTemplate> resourceTemplates,
				Map<String, McpServerFeatures.AsyncPromptSpecification> prompts,
				Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions,
				List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers,
				String instructions) {

			Assert.notNull(serverInfo, "Server info must not be null");

			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // completions
							null, // experimental
							new McpSchema.ServerCapabilities.LoggingCapabilities(), // Enable
																					// logging
																					// by
																					// default
							!Utils.isEmpty(prompts) ? new McpSchema.ServerCapabilities.PromptCapabilities(false) : null,
							!Utils.isEmpty(resources)
									? new McpSchema.ServerCapabilities.ResourceCapabilities(false, false) : null,
							!Utils.isEmpty(tools) ? new McpSchema.ServerCapabilities.ToolCapabilities(false) : null);

			this.tools = (tools != null) ? tools : List.of();
			this.resources = (resources != null) ? resources : Map.of();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : List.of();
			this.prompts = (prompts != null) ? prompts : Map.of();
			this.completions = (completions != null) ? completions : Map.of();
			this.rootsChangeConsumers = (rootsChangeConsumers != null) ? rootsChangeConsumers : List.of();
			this.instructions = instructions;
		}

		/**
		 * Convert a synchronous specification into an asynchronous one and provide
		 * blocking code offloading to prevent accidental blocking of the non-blocking
		 * transport.
		 * @param syncSpec a potentially blocking, synchronous specification.
		 * @return a specification which is protected from blocking calls specified by the
		 * user.
		 */
		static Async fromSync(Sync syncSpec) {
			List<McpServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();
			for (var tool : syncSpec.tools()) {
				tools.add(AsyncToolSpecification.fromSync(tool));
			}

			Map<String, AsyncResourceSpecification> resources = new HashMap<>();
			syncSpec.resources().forEach((key, resource) -> {
				resources.put(key, AsyncResourceSpecification.fromSync(resource));
			});

			Map<String, AsyncPromptSpecification> prompts = new HashMap<>();
			syncSpec.prompts().forEach((key, prompt) -> {
				prompts.put(key, AsyncPromptSpecification.fromSync(prompt));
			});

			Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions = new HashMap<>();
			syncSpec.completions().forEach((key, completion) -> {
				completions.put(key, AsyncCompletionSpecification.fromSync(completion));
			});

			List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootChangeConsumers = new ArrayList<>();

			for (var rootChangeConsumer : syncSpec.rootsChangeConsumers()) {
				rootChangeConsumers.add((exchange, list) -> Mono
					.<Void>fromRunnable(() -> rootChangeConsumer.accept(new McpSyncServerExchange(exchange), list))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			return new Async(syncSpec.serverInfo(), syncSpec.serverCapabilities(), tools, resources,
					syncSpec.resourceTemplates(), prompts, completions, rootChangeConsumers, syncSpec.instructions());
		}
	}

	/**
	 * Synchronous server features specification.
	 *
	 * @param serverInfo The server implementation details
	 * @param serverCapabilities The server capabilities
	 * @param tools The list of tool specifications
	 * @param resources The map of resource specifications
	 * @param resourceTemplates The list of resource templates
	 * @param prompts The map of prompt specifications
	 * @param rootsChangeConsumers The list of consumers that will be notified when the
	 * roots list changes
	 * @param instructions The server instructions text
	 */
	record Sync(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
			List<McpServerFeatures.SyncToolSpecification> tools,
			Map<String, McpServerFeatures.SyncResourceSpecification> resources,
			List<McpSchema.ResourceTemplate> resourceTemplates,
			Map<String, McpServerFeatures.SyncPromptSpecification> prompts,
			Map<McpSchema.CompleteReference, McpServerFeatures.SyncCompletionSpecification> completions,
			List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeConsumers, String instructions) {

		/**
		 * 创建实例并验证参数。
		 * @param serverInfo 服务器实现详情
		 * @param serverCapabilities 服务器能力
		 * @param tools 工具规范列表
		 * @param resources 资源规范映射
		 * @param resourceTemplates 资源模板列表
		 * @param prompts 提示规范映射
		 * @param completions 完成规范映射
		 * @param rootsChangeConsumers 当根目录列表发生变化时将被通知的消费者列表
		 * @param instructions 服务器指令文本
		 */
		Sync(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpServerFeatures.SyncToolSpecification> tools,
				Map<String, McpServerFeatures.SyncResourceSpecification> resources,
				List<McpSchema.ResourceTemplate> resourceTemplates,
				Map<String, McpServerFeatures.SyncPromptSpecification> prompts,
				Map<McpSchema.CompleteReference, McpServerFeatures.SyncCompletionSpecification> completions,
				List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeConsumers,
				String instructions) {

			Assert.notNull(serverInfo, "Server info must not be null");

			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // completions
							null, // experimental
							new McpSchema.ServerCapabilities.LoggingCapabilities(), // Enable
																					// logging
																					// by
																					// default
							!Utils.isEmpty(prompts) ? new McpSchema.ServerCapabilities.PromptCapabilities(false) : null,
							!Utils.isEmpty(resources)
									? new McpSchema.ServerCapabilities.ResourceCapabilities(false, false) : null,
							!Utils.isEmpty(tools) ? new McpSchema.ServerCapabilities.ToolCapabilities(false) : null);

			this.tools = (tools != null) ? tools : new ArrayList<>();
			this.resources = (resources != null) ? resources : new HashMap<>();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : new ArrayList<>();
			this.prompts = (prompts != null) ? prompts : new HashMap<>();
			this.completions = (completions != null) ? completions : new HashMap<>();
			this.rootsChangeConsumers = (rootsChangeConsumers != null) ? rootsChangeConsumers : new ArrayList<>();
			this.instructions = instructions;
		}

	}

	/**
	 * 具有异步处理函数的工具规范。工具是MCP服务器向AI模型暴露功能的主要方式。每个工具
	 * 代表一个特定的能力，例如：
	 * <ul>
	 * <li>执行计算
	 * <li>访问外部API
	 * <li>查询数据库
	 * <li>操作文件
	 * <li>执行系统命令
	 * </ul>
	 *
	 * <p>
	 * 工具规范示例：<pre>{@code
	 * new McpServerFeatures.AsyncToolSpecification(
	 *     new Tool(
	 *         "calculator",
	 *         "执行数学计算",
	 *         new JsonSchemaObject()
	 *             .required("expression")
	 *             .property("expression", JsonSchemaType.STRING)
	 *     ),
	 *     (exchange, args) -> {
	 *         String expr = (String) args.get("expression");
	 *         return Mono.fromSupplier(() -> evaluate(expr))
	 *             .map(result -> new CallToolResult("结果：" + result));
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param tool 工具定义，包括名称、描述和参数模式
	 * @param call 实现工具逻辑的函数，接收参数并返回结果。函数的第一个参数是
	 * {@link McpAsyncServerExchange}，服务器可以通过它与已连接的客户端交互。
	 * 第二个参数是工具参数的映射。
	 */
	public record AsyncToolSpecification(McpSchema.Tool tool,
			BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<McpSchema.CallToolResult>> call) {

		static AsyncToolSpecification fromSync(SyncToolSpecification tool) {
			// FIXME: This is temporary, proper validation should be implemented
			if (tool == null) {
				return null;
			}
			return new AsyncToolSpecification(tool.tool(),
					(exchange, map) -> Mono
						.fromCallable(() -> tool.call().apply(new McpSyncServerExchange(exchange), map))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * 具有异步处理函数的资源规范。资源通过暴露以下数据为AI模型提供上下文：
	 * <ul>
	 * <li>文件内容
	 * <li>数据库记录
	 * <li>API响应
	 * <li>系统信息
	 * <li>应用程序状态
	 * </ul>
	 *
	 * <p>
	 * 资源规范示例：<pre>{@code
	 * new McpServerFeatures.AsyncResourceSpecification(
	 *     new Resource("docs", "文档文件", "text/markdown"),
	 *     (exchange, request) ->
	 *         Mono.fromSupplier(() -> readFile(request.getPath()))
	 *             .map(ReadResourceResult::new)
	 * )
	 * }</pre>
	 *
	 * @param resource 资源定义，包括名称、描述和MIME类型
	 * @param readHandler 处理资源读取请求的函数。函数的第一个参数是
	 * {@link McpAsyncServerExchange}，服务器可以通过它与已连接的客户端交互。
	 * 第二个参数是{@link io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest}。
	 */
	public record AsyncResourceSpecification(McpSchema.Resource resource,
			BiFunction<McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler) {

		static AsyncResourceSpecification fromSync(SyncResourceSpecification resource) {
			// FIXME: This is temporary, proper validation should be implemented
			if (resource == null) {
				return null;
			}
			return new AsyncResourceSpecification(resource.resource(),
					(exchange, req) -> Mono
						.fromCallable(() -> resource.readHandler().apply(new McpSyncServerExchange(exchange), req))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * 具有异步处理函数的提示模板规范。提示为AI模型交互提供结构化模板，支持：
	 * <ul>
	 * <li>一致的消息格式化
	 * <li>参数替换
	 * <li>上下文注入
	 * <li>响应格式化
	 * <li>指令模板化
	 * </ul>
	 *
	 * <p>
	 * 提示规范示例：<pre>{@code
	 * new McpServerFeatures.AsyncPromptSpecification(
	 *     new Prompt("analyze", "代码分析模板"),
	 *     (exchange, request) -> {
	 *         String code = request.getArguments().get("code");
	 *         return Mono.just(new GetPromptResult(
	 *             "分析这段代码：\n\n" + code + "\n\n提供以下反馈："
	 *         ));
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param prompt 提示定义，包括名称和描述
	 * @param promptHandler 处理提示请求并返回格式化模板的函数。函数的第一个参数是
	 * {@link McpAsyncServerExchange}，服务器可以通过它与已连接的客户端交互。
	 * 第二个参数是{@link io.modelcontextprotocol.spec.McpSchema.GetPromptRequest}。
	 */
	public record AsyncPromptSpecification(McpSchema.Prompt prompt,
			BiFunction<McpAsyncServerExchange, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler) {

		static AsyncPromptSpecification fromSync(SyncPromptSpecification prompt) {
			// FIXME: This is temporary, proper validation should be implemented
			if (prompt == null) {
				return null;
			}
			return new AsyncPromptSpecification(prompt.prompt(),
					(exchange, req) -> Mono
						.fromCallable(() -> prompt.promptHandler().apply(new McpSyncServerExchange(exchange), req))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * 具有异步执行支持的完成处理函数规范。完成功能根据提示或资源引用以及
	 * 用户提供的参数生成AI模型输出。这种抽象支持：
	 * <ul>
	 * <li>可定制的响应生成逻辑
	 * <li>参数驱动的模板扩展
	 * <li>与已连接客户端的动态交互
	 * </ul>
	 *
	 * @param referenceKey 表示完成引用的唯一键
	 * @param completionHandler 处理完成请求并返回结果的异步函数。第一个参数是
	 * {@link McpAsyncServerExchange}，用于与客户端交互。第二个参数是
	 * {@link io.modelcontextprotocol.spec.McpSchema.CompleteRequest}。
	 */
	public record AsyncCompletionSpecification(McpSchema.CompleteReference referenceKey,
			BiFunction<McpAsyncServerExchange, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler) {

		/**
		 * 通过将处理程序包装在有界弹性调度器中以实现安全的非阻塞执行，
		 * 将同步的{@link SyncCompletionSpecification}转换为
		 * {@link AsyncCompletionSpecification}。
		 * @param completion 同步完成规范
		 * @return 提供的同步规范的异步包装器，如果输入为null则返回{@code null}
		 */
		static AsyncCompletionSpecification fromSync(SyncCompletionSpecification completion) {
			if (completion == null) {
				return null;
			}
			return new AsyncCompletionSpecification(completion.referenceKey(),
					(exchange, request) -> Mono.fromCallable(
							() -> completion.completionHandler().apply(new McpSyncServerExchange(exchange), request))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * 具有同步处理函数的工具规范。工具是MCP服务器向AI模型暴露功能的主要方式。每个工具
	 * 代表一个特定的能力，例如：
	 * <ul>
	 * <li>执行计算
	 * <li>访问外部API
	 * <li>查询数据库
	 * <li>操作文件
	 * <li>执行系统命令
	 * </ul>
	 *
	 * <p>
	 * 工具规范示例：<pre>{@code
	 * new McpServerFeatures.SyncToolSpecification(
	 *     new Tool(
	 *         "calculator",
	 *         "执行数学计算",
	 *         new JsonSchemaObject()
	 *             .required("expression")
	 *             .property("expression", JsonSchemaType.STRING)
	 *     ),
	 *     (exchange, args) -> {
	 *         String expr = (String) args.get("expression");
	 *         return new CallToolResult("结果：" + evaluate(expr));
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param tool 工具定义，包括名称、描述和参数模式
	 * @param call 实现工具逻辑的函数，接收参数并返回结果。函数的第一个参数是
	 * {@link McpSyncServerExchange}，服务器可以通过它与已连接的客户端交互。
	 * 第二个参数是传递给工具的参数映射。
	 */
	public record SyncToolSpecification(McpSchema.Tool tool,
			BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> call) {
	}

	/**
	 * 具有同步处理函数的资源规范。资源通过暴露以下数据为AI模型提供上下文：
	 * <ul>
	 * <li>文件内容
	 * <li>数据库记录
	 * <li>API响应
	 * <li>系统信息
	 * <li>应用程序状态
	 * </ul>
	 *
	 * <p>
	 * 资源规范示例：<pre>{@code
	 * new McpServerFeatures.SyncResourceSpecification(
	 *     new Resource("docs", "文档文件", "text/markdown"),
	 *     (exchange, request) -> {
	 *         String content = readFile(request.getPath());
	 *         return new ReadResourceResult(content);
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param resource 资源定义，包括名称、描述和MIME类型
	 * @param readHandler 处理资源读取请求的函数。函数的第一个参数是
	 * {@link McpSyncServerExchange}，服务器可以通过它与已连接的客户端交互。
	 * 第二个参数是{@link io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest}。
	 */
	public record SyncResourceSpecification(McpSchema.Resource resource,
			BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
	}

	/**
	 * 具有同步处理函数的提示模板规范。提示为AI模型交互提供结构化模板，支持：
	 * <ul>
	 * <li>一致的消息格式化
	 * <li>参数替换
	 * <li>上下文注入
	 * <li>响应格式化
	 * <li>指令模板化
	 * </ul>
	 *
	 * <p>
	 * 提示规范示例：<pre>{@code
	 * new McpServerFeatures.SyncPromptSpecification(
	 *     new Prompt("analyze", "代码分析模板"),
	 *     (exchange, request) -> {
	 *         String code = request.getArguments().get("code");
	 *         return new GetPromptResult(
	 *             "分析这段代码：\n\n" + code + "\n\n提供以下反馈："
	 *         );
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param prompt 提示定义，包括名称和描述
	 * @param promptHandler 处理提示请求并返回格式化模板的函数。函数的第一个参数是
	 * {@link McpSyncServerExchange}，服务器可以通过它与已连接的客户端交互。
	 * 第二个参数是{@link io.modelcontextprotocol.spec.McpSchema.GetPromptRequest}。
	 */
	public record SyncPromptSpecification(McpSchema.Prompt prompt,
			BiFunction<McpSyncServerExchange, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler) {
	}

	/**
	 * 具有同步执行支持的完成处理函数规范。
	 *
	 * @param referenceKey 表示完成引用的唯一键
	 * @param completionHandler 处理完成请求并返回结果的同步函数。第一个参数是
	 * {@link McpSyncServerExchange}，用于与客户端交互。第二个参数是
	 * {@link io.modelcontextprotocol.spec.McpSchema.CompleteRequest}。
	 */
	public record SyncCompletionSpecification(McpSchema.CompleteReference referenceKey,
			BiFunction<McpSyncServerExchange, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler) {
	}

}
