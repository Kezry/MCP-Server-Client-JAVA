/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;

/**
 * 表示与模型上下文协议(MCP)客户端的同步交互。该交互提供了与客户端交互
 * 并查询其能力的方法。
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 */
public class McpSyncServerExchange {

	private final McpAsyncServerExchange exchange;

	/**
	 * 使用提供的异步实现作为委托创建与客户端的新同步交互。
	 * @param exchange 要委托的异步交互。
	 */
	public McpSyncServerExchange(McpAsyncServerExchange exchange) {
		this.exchange = exchange;
	}

	/**
	 * 获取定义支持的特性和功能的客户端能力。
	 * @return 客户端能力
	 */
	public McpSchema.ClientCapabilities getClientCapabilities() {
		return this.exchange.getClientCapabilities();
	}

	/**
	 * 获取客户端实现信息。
	 * @return 客户端实现详情
	 */
	public McpSchema.Implementation getClientInfo() {
		return this.exchange.getClientInfo();
	}

	/**
	 * 使用客户端的采样功能创建新消息。模型上下文协议(MCP)为服务器提供了一种标准化的方式，
	 * 通过客户端从语言模型请求LLM采样("completions"或"generations")。这个流程允许客户端
	 * 保持对模型访问、选择和权限的控制，同时使服务器能够利用AI功能—无需服务器API密钥。
	 * 服务器可以请求基于文本或图像的交互，并可以选择在其提示中包含来自MCP服务器的上下文。
	 * @param createMessageRequest 创建新消息的请求
	 * @return 包含采样响应详情的结果
	 * @see McpSchema.CreateMessageRequest
	 * @see McpSchema.CreateMessageResult
	 * @see <a href=
	 * "https://spec.modelcontextprotocol.io/specification/client/sampling/">采样
	 * 规范</a>
	 */
	public McpSchema.CreateMessageResult createMessage(McpSchema.CreateMessageRequest createMessageRequest) {
		return this.exchange.createMessage(createMessageRequest).block();
	}

	/**
	 * 获取客户端提供的所有根目录列表。
	 * @return 根目录列表结果。
	 */
	public McpSchema.ListRootsResult listRoots() {
		return this.exchange.listRoots().block();
	}

	/**
	 * 获取客户端提供的分页根目录列表。
	 * @param cursor 来自前一个列表请求的可选分页游标
	 * @return 根目录列表结果
	 */
	public McpSchema.ListRootsResult listRoots(String cursor) {
		return this.exchange.listRoots(cursor).block();
	}

	/**
	 * 向所有已连接的客户端发送日志消息通知。低于当前最小日志级别的消息将被过滤掉。
	 * @param loggingMessageNotification 要发送的日志消息
	 */
	public void loggingNotification(LoggingMessageNotification loggingMessageNotification) {
		this.exchange.loggingNotification(loggingMessageNotification).block();
	}

}
