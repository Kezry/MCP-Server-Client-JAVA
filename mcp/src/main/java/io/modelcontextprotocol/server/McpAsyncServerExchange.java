/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * 表示与模型上下文协议(MCP)客户端的异步交互。该交互提供了与客户端交互
 * 并查询其能力的方法。
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 */
public class McpAsyncServerExchange {

	private final McpServerSession session;

	private final McpSchema.ClientCapabilities clientCapabilities;

	private final McpSchema.Implementation clientInfo;

	private volatile LoggingLevel minLoggingLevel = LoggingLevel.INFO;

	private static final TypeReference<McpSchema.CreateMessageResult> CREATE_MESSAGE_RESULT_TYPE_REF = new TypeReference<>() {
	};

	private static final TypeReference<McpSchema.ListRootsResult> LIST_ROOTS_RESULT_TYPE_REF = new TypeReference<>() {
	};

	/**
	 * 创建与客户端的新异步交互。
	 * @param session 表示1-1交互的服务器会话。
	 * @param clientCapabilities 定义支持的特性和功能的客户端能力。
	 * @param clientInfo 客户端实现信息。
	 */
	public McpAsyncServerExchange(McpServerSession session, McpSchema.ClientCapabilities clientCapabilities,
			McpSchema.Implementation clientInfo) {
		this.session = session;
		this.clientCapabilities = clientCapabilities;
		this.clientInfo = clientInfo;
	}

	/**
	 * 获取定义支持的特性和功能的客户端能力。
	 * @return 客户端能力
	 */
	public McpSchema.ClientCapabilities getClientCapabilities() {
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
	 * 使用客户端的采样功能创建新消息。模型上下文协议(MCP)为服务器提供了一种标准化的方式，
	 * 通过客户端从语言模型请求LLM采样("completions"或"generations")。这个流程允许客户端
	 * 保持对模型访问、选择和权限的控制，同时使服务器能够利用AI功能 - 无需服务器API密钥。
	 * 服务器可以请求基于文本或图像的交互，并可以选择在其提示中包含来自MCP服务器的上下文。
	 * @param createMessageRequest 创建新消息的请求
	 * @return 当消息创建完成时完成的Mono
	 * @see McpSchema.CreateMessageRequest
	 * @see McpSchema.CreateMessageResult
	 * @see <a href=
	 * "https://spec.modelcontextprotocol.io/specification/client/sampling/">采样
	 * 规范</a>
	 */
	public Mono<McpSchema.CreateMessageResult> createMessage(McpSchema.CreateMessageRequest createMessageRequest) {

		if (this.clientCapabilities == null) {
			return Mono.error(new McpError("Client must be initialized. Call the initialize method first!"));
		}
		if (this.clientCapabilities.sampling() == null) {
			return Mono.error(new McpError("Client must be configured with sampling capabilities"));
		}
		return this.session.sendRequest(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, createMessageRequest,
				CREATE_MESSAGE_RESULT_TYPE_REF);
	}

	/**
	 * 获取客户端提供的所有根目录列表。
	 * @return 发出根目录列表结果的Mono。
	 */
	public Mono<McpSchema.ListRootsResult> listRoots() {
		return this.listRoots(null);
	}

	/**
	 * 获取客户端提供的分页根目录列表。
	 * @param cursor 来自前一个列表请求的可选分页游标
	 * @return 发出包含根目录列表结果的Mono
	 */
	public Mono<McpSchema.ListRootsResult> listRoots(String cursor) {
		return this.session.sendRequest(McpSchema.METHOD_ROOTS_LIST, new McpSchema.PaginatedRequest(cursor),
				LIST_ROOTS_RESULT_TYPE_REF);
	}

	/**
	 * 向所有已连接的客户端发送日志消息通知。低于当前最小日志级别的消息将被过滤掉。
	 * @param loggingMessageNotification 要发送的日志消息
	 * @return 当通知发送完成时完成的Mono
	 */
	public Mono<Void> loggingNotification(LoggingMessageNotification loggingMessageNotification) {

		if (loggingMessageNotification == null) {
			return Mono.error(new McpError("Logging message must not be null"));
		}

		return Mono.defer(() -> {
			if (this.isNotificationForLevelAllowed(loggingMessageNotification.level())) {
				return this.session.sendNotification(McpSchema.METHOD_NOTIFICATION_MESSAGE, loggingMessageNotification);
			}
			return Mono.empty();
		});
	}

	/**
	 * 设置客户端的最小日志级别。低于此级别的消息将被过滤掉。
	 * @param minLoggingLevel 最小日志级别
	 */
	void setMinLoggingLevel(LoggingLevel minLoggingLevel) {
		Assert.notNull(minLoggingLevel, "minLoggingLevel must not be null");
		this.minLoggingLevel = minLoggingLevel;
	}

	private boolean isNotificationForLevelAllowed(LoggingLevel loggingLevel) {
		return loggingLevel.level() >= this.minLoggingLevel.level();
	}

}
