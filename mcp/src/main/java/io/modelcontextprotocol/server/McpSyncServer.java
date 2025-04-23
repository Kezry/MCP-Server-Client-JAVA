/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.util.Assert;

/**
 * 模型上下文协议(MCP)服务器的同步实现，它封装了{@link McpAsyncServer}来提供阻塞操作。
 * 该类将所有操作委托给底层的异步服务器实例，同时为不需要响应式编程的场景提供更简单的
 * 同步API。
 *
 * <p>
 * MCP服务器使AI模型能够通过标准化接口暴露工具、资源和提示。通过此同步API提供的
 * 主要功能包括：
 * <ul>
 * <li>用于扩展AI模型能力的工具注册和管理
 * <li>基于URI寻址的资源处理以提供上下文
 * <li>用于标准化交互的提示模板管理
 * <li>状态变更的实时客户端通知
 * <li>可配置严重级别的结构化日志记录
 * <li>支持客户端AI模型采样
 * </ul>
 *
 * <p>
 * 虽然{@link McpAsyncServer}使用Project Reactor的Mono和Flux类型进行非阻塞操作，
 * 但此类将它们转换为阻塞调用，使其更适合：
 * <ul>
 * <li>传统的同步应用
 * <li>简单的脚本场景
 * <li>测试和调试
 * <li>响应式编程增加不必要复杂性的情况
 * </ul>
 *
 * <p>
 * 服务器支持通过{@link #addTool}、{@link #addResource}和{@link #addPrompt}等方法
 * 在运行时修改其功能，并在配置时自动通知已连接的客户端变更。
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpAsyncServer
 * @see McpSchema
 */
public class McpSyncServer {

	/**
	 * 要封装的异步服务器。
	 */
	private final McpAsyncServer asyncServer;

	/**
	 * 创建一个新的同步服务器，封装提供的异步服务器。
	 * @param asyncServer 要封装的异步服务器
	 */
	public McpSyncServer(McpAsyncServer asyncServer) {
		Assert.notNull(asyncServer, "Async server must not be null");
		this.asyncServer = asyncServer;
	}

	/**
	 * 添加新的工具处理程序。
	 * @param toolHandler 要添加的工具处理程序
	 */
	public void addTool(McpServerFeatures.SyncToolSpecification toolHandler) {
		this.asyncServer.addTool(McpServerFeatures.AsyncToolSpecification.fromSync(toolHandler)).block();
	}

	/**
	 * 移除工具处理程序。
	 * @param toolName 要移除的工具处理程序的名称
	 */
	public void removeTool(String toolName) {
		this.asyncServer.removeTool(toolName).block();
	}

	/**
	 * 添加新的资源处理程序。
	 * @param resourceHandler 要添加的资源处理程序
	 */
	public void addResource(McpServerFeatures.SyncResourceSpecification resourceHandler) {
		this.asyncServer.addResource(McpServerFeatures.AsyncResourceSpecification.fromSync(resourceHandler)).block();
	}

	/**
	 * 移除资源处理程序。
	 * @param resourceUri 要移除的资源处理程序的URI
	 */
	public void removeResource(String resourceUri) {
		this.asyncServer.removeResource(resourceUri).block();
	}

	/**
	 * 添加新的提示处理程序。
	 * @param promptSpecification 要添加的提示规范
	 */
	public void addPrompt(McpServerFeatures.SyncPromptSpecification promptSpecification) {
		this.asyncServer.addPrompt(McpServerFeatures.AsyncPromptSpecification.fromSync(promptSpecification)).block();
	}

	/**
	 * 移除提示处理程序。
	 * @param promptName 要移除的提示处理程序的名称
	 */
	public void removePrompt(String promptName) {
		this.asyncServer.removePrompt(promptName).block();
	}

	/**
	 * 通知客户端可用工具列表已更改。
	 */
	public void notifyToolsListChanged() {
		this.asyncServer.notifyToolsListChanged().block();
	}

	/**
	 * 获取定义支持的特性和功能的服务器能力。
	 * @return 服务器能力
	 */
	public McpSchema.ServerCapabilities getServerCapabilities() {
		return this.asyncServer.getServerCapabilities();
	}

	/**
	 * 获取服务器实现信息。
	 * @return 服务器实现详情
	 */
	public McpSchema.Implementation getServerInfo() {
		return this.asyncServer.getServerInfo();
	}

	/**
	 * 通知客户端可用资源列表已更改。
	 */
	public void notifyResourcesListChanged() {
		this.asyncServer.notifyResourcesListChanged().block();
	}

	/**
	 * 通知客户端可用提示列表已更改。
	 */
	public void notifyPromptsListChanged() {
		this.asyncServer.notifyPromptsListChanged().block();
	}

	/**
	 * 此实现会错误地将日志消息广播给所有已连接的客户端，对所有客户端使用单一的
	 * minLoggingLevel。与采样和根目录类似，日志级别应该按客户端会话设置，并使用
	 * ServerExchange将日志消息发送给正确的客户端。
	 * @param loggingMessageNotification 要发送的日志消息
	 * @deprecated 请改用
	 * {@link McpSyncServerExchange#loggingNotification(LoggingMessageNotification)}
	 */
	@Deprecated
	public void loggingNotification(LoggingMessageNotification loggingMessageNotification) {
		this.asyncServer.loggingNotification(loggingMessageNotification).block();
	}

	/**
	 * 优雅地关闭服务器。
	 */
	public void closeGracefully() {
		this.asyncServer.closeGracefully().block();
	}

	/**
	 * 立即关闭服务器。
	 */
	public void close() {
		this.asyncServer.close();
	}

	/**
	 * 获取底层异步服务器实例。
	 * @return 被封装的异步服务器
	 */
	public McpAsyncServer getAsyncServer() {
		return this.asyncServer;
	}

}
