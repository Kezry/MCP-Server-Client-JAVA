/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 模型上下文协议(MCP)客户端的特性和能力的表示。
 * 此类提供两种记录类型用于管理客户端特性：
 * <ul>
 * <li>{@link Async} 用于带有Project Reactor的Mono响应的非阻塞操作
 * <li>{@link Sync} 用于带有直接响应的阻塞操作
 * </ul>
 *
 * <p>
 * 每个特性规范包括：
 * <ul>
 * <li>客户端实现信息和能力
 * <li>用于资源访问的根URI映射
 * <li>用于工具、资源和提示的变更通知处理器
 * <li>日志消息消费者
 * <li>用于请求处理的消息采样处理器
 * </ul>
 *
 * <p>
 * 该类通过{@link Async#fromSync}方法支持同步和异步规范之间的转换，
 * 通过在有界弹性调度器上调度来确保在非阻塞上下文中正确处理阻塞操作。
 *
 * @author Dariusz Jędrzejczyk
 * @see McpClient
 * @see McpSchema.Implementation
 * @see McpSchema.ClientCapabilities
 */
class McpClientFeatures {

	/**
	 * 异步客户端特性规范，提供能力以及请求和通知处理器。
	 *
	 * @param clientInfo 客户端实现信息。
	 * @param clientCapabilities 客户端能力。
	 * @param roots 根目录。
	 * @param toolsChangeConsumers 工具变更消费者。
	 * @param resourcesChangeConsumers 资源变更消费者。
	 * @param promptsChangeConsumers 提示变更消费者。
	 * @param loggingConsumers 日志消费者。
	 * @param samplingHandler 采样处理器。
	 */
	record Async(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
			Map<String, McpSchema.Root> roots, List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers,
			List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers,
			List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers,
			List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers,
			Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler) {

		/**
		 * 创建实例并验证参数。
		 * @param clientCapabilities 客户端能力。
		 * @param roots 根目录。
		 * @param toolsChangeConsumers 工具变更消费者。
		 * @param resourcesChangeConsumers 资源变更消费者。
		 * @param promptsChangeConsumers 提示变更消费者。
		 * @param loggingConsumers 日志消费者。
		 * @param samplingHandler 采样处理器。
		 */
		public Async(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
				Map<String, McpSchema.Root> roots,
				List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers,
				List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers,
				List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers,
				List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers,
				Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler) {

			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			this.clientCapabilities = (clientCapabilities != null) ? clientCapabilities
					: new McpSchema.ClientCapabilities(null,
							!Utils.isEmpty(roots) ? new McpSchema.ClientCapabilities.RootCapabilities(false) : null,
							samplingHandler != null ? new McpSchema.ClientCapabilities.Sampling() : null);
			this.roots = roots != null ? new ConcurrentHashMap<>(roots) : new ConcurrentHashMap<>();

			this.toolsChangeConsumers = toolsChangeConsumers != null ? toolsChangeConsumers : List.of();
			this.resourcesChangeConsumers = resourcesChangeConsumers != null ? resourcesChangeConsumers : List.of();
			this.promptsChangeConsumers = promptsChangeConsumers != null ? promptsChangeConsumers : List.of();
			this.loggingConsumers = loggingConsumers != null ? loggingConsumers : List.of();
			this.samplingHandler = samplingHandler;
		}

		/**
		 * 将同步规范转换为异步规范，并提供阻塞代码卸载，
		 * 以防止意外阻塞非阻塞传输。
		 * @param syncSpec 可能阻塞的同步规范。
		 * @return 受保护免受用户指定的阻塞调用影响的规范。
		 */
		public static Async fromSync(Sync syncSpec) {
			List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.Tool>> consumer : syncSpec.toolsChangeConsumers()) {
				toolsChangeConsumers.add(t -> Mono.<Void>fromRunnable(() -> consumer.accept(t))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.Resource>> consumer : syncSpec.resourcesChangeConsumers()) {
				resourcesChangeConsumers.add(r -> Mono.<Void>fromRunnable(() -> consumer.accept(r))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers = new ArrayList<>();

			for (Consumer<List<McpSchema.Prompt>> consumer : syncSpec.promptsChangeConsumers()) {
				promptsChangeConsumers.add(p -> Mono.<Void>fromRunnable(() -> consumer.accept(p))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers = new ArrayList<>();
			for (Consumer<McpSchema.LoggingMessageNotification> consumer : syncSpec.loggingConsumers()) {
				loggingConsumers.add(l -> Mono.<Void>fromRunnable(() -> consumer.accept(l))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler = r -> Mono
				.fromCallable(() -> syncSpec.samplingHandler().apply(r))
				.subscribeOn(Schedulers.boundedElastic());
			return new Async(syncSpec.clientInfo(), syncSpec.clientCapabilities(), syncSpec.roots(),
					toolsChangeConsumers, resourcesChangeConsumers, promptsChangeConsumers, loggingConsumers,
					samplingHandler);
		}
	}

	/**
	 * 同步客户端特性规范，提供能力以及请求和通知处理器。
	 *
	 * @param clientInfo 客户端实现信息。
	 * @param clientCapabilities 客户端能力。
	 * @param roots 根目录。
	 * @param toolsChangeConsumers 工具变更消费者。
	 * @param resourcesChangeConsumers 资源变更消费者。
	 * @param promptsChangeConsumers 提示变更消费者。
	 * @param loggingConsumers 日志消费者。
	 * @param samplingHandler 采样处理器。
	 */
	public record Sync(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
			Map<String, McpSchema.Root> roots, List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers,
			List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers,
			List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers,
			List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers,
			Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler) {

		/**
		 * 创建实例并验证参数。
		 * @param clientInfo 客户端实现信息。
		 * @param clientCapabilities 客户端能力。
		 * @param roots 根目录。
		 * @param toolsChangeConsumers 工具变更消费者。
		 * @param resourcesChangeConsumers 资源变更消费者。
		 * @param promptsChangeConsumers 提示变更消费者。
		 * @param loggingConsumers 日志消费者。
		 * @param samplingHandler 采样处理器。
		 */
		public Sync(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
				Map<String, McpSchema.Root> roots, List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers,
				List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers,
				List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers,
				List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers,
				Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler) {

			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			this.clientCapabilities = (clientCapabilities != null) ? clientCapabilities
					: new McpSchema.ClientCapabilities(null,
							!Utils.isEmpty(roots) ? new McpSchema.ClientCapabilities.RootCapabilities(false) : null,
							samplingHandler != null ? new McpSchema.ClientCapabilities.Sampling() : null);
			this.roots = roots != null ? new HashMap<>(roots) : new HashMap<>();

			this.toolsChangeConsumers = toolsChangeConsumers != null ? toolsChangeConsumers : List.of();
			this.resourcesChangeConsumers = resourcesChangeConsumers != null ? resourcesChangeConsumers : List.of();
			this.promptsChangeConsumers = promptsChangeConsumers != null ? promptsChangeConsumers : List.of();
			this.loggingConsumers = loggingConsumers != null ? loggingConsumers : List.of();
			this.samplingHandler = samplingHandler;
		}
	}

}
