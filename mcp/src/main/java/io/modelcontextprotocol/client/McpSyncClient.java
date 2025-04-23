/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型上下文协议(MCP)的同步客户端实现，它封装了一个{@link McpAsyncClient}来提供阻塞操作。
 *
 * <p>
 * 该客户端通过委托给异步客户端并在结果上进行阻塞来实现MCP规范。主要特性包括：
 * <ul>
 * <li>同步、阻塞式API，便于在非响应式应用中集成
 * <li>服务器提供功能的工具发现和调用
 * <li>基于URI寻址的资源访问和管理
 * <li>用于标准化AI交互的提示模板处理
 * <li>工具、资源和提示变更的实时通知
 * <li>可配置严重级别的结构化日志记录
 * </ul>
 *
 * <p>
 * 客户端遵循与其异步对应方相同的生命周期：
 * <ol>
 * <li>初始化 - 建立连接并协商功能
 * <li>正常运行 - 处理请求和通知
 * <li>优雅关闭 - 确保连接清理终止
 * </ol>
 *
 * <p>
 * 此实现实现了{@link AutoCloseable}接口用于资源清理，并提供了
 * 即时和优雅关闭选项。所有操作都会阻塞直到完成或超时，使其适用于
 * 传统的同步编程模型。
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 * @author Jihoon Kim
 * @see McpClient
 * @see McpAsyncClient
 * @see McpSchema
 */
public class McpSyncClient implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(McpSyncClient.class);

	// TODO: 考虑提供客户端配置来正确设置这个值
	// 这目前只是一个问题，因为使用了AutoCloseable - 也许这
	// 不是必需的？
	private static final long DEFAULT_CLOSE_TIMEOUT_MS = 10_000L;

	private final McpAsyncClient delegate;

	/**
	 * 使用给定的委托创建新的McpSyncClient。
	 * @param delegate 异步内核，此同步客户端在其之上提供阻塞式API。
	 */
	McpSyncClient(McpAsyncClient delegate) {
		Assert.notNull(delegate, "The delegate can not be null");
		this.delegate = delegate;
	}

	/**
	 * 获取定义支持的特性和功能的服务器功能。
	 * @return 服务器功能
	 */
	public McpSchema.ServerCapabilities getServerCapabilities() {
		return this.delegate.getServerCapabilities();
	}

	/**
	 * 获取为客户端提供如何与此服务器交互指导的服务器指令。
	 * @return 指令
	 */
	public String getServerInstructions() {
		return this.delegate.getServerInstructions();
	}

	/**
	 * 获取服务器实现信息。
	 * @return 服务器实现详情
	 */
	public McpSchema.Implementation getServerInfo() {
		return this.delegate.getServerInfo();
	}

	/**
	 * 获取定义支持的特性和功能的客户端功能。
	 * @return 客户端功能
	 */
	public ClientCapabilities getClientCapabilities() {
		return this.delegate.getClientCapabilities();
	}

	/**
	 * 获取客户端实现信息。
	 * @return 客户端实现详情
	 */
	public McpSchema.Implementation getClientInfo() {
		return this.delegate.getClientInfo();
	}

	@Override
	public void close() {
		this.delegate.close();
	}

	public boolean closeGracefully() {
		try {
			this.delegate.closeGracefully().block(Duration.ofMillis(DEFAULT_CLOSE_TIMEOUT_MS));
		}
		catch (RuntimeException e) {
			logger.warn("Client didn't close within timeout of {} ms.", DEFAULT_CLOSE_TIMEOUT_MS, e);
			return false;
		}
		return true;
	}

	/**
	 * 初始化阶段必须是客户端和服务器之间的第一次交互。
	 * 在此阶段，客户端和服务器：
	 * <ul>
	 * <li>建立协议版本兼容性</li>
	 * <li>交换和协商功能</li>
	 * <li>共享实现细节</li>
	 * </ul>
	 * <br/>
	 * 客户端必须通过发送包含以下内容的初始化请求来启动此阶段：
	 * <ul>
	 * <li>客户端支持的协议版本</li>
	 * <li>客户端的功能</li>
	 * <li>客户端实现信息</li>
	 * </ul>
	 *
	 * 服务器必须响应其自身的功能和信息：
	 * {@link McpSchema.ServerCapabilities}。<br/>
	 * 初始化成功后，客户端必须发送已初始化通知
	 * 以表明它已准备好开始正常操作。
	 *
	 * <br/>
	 *
	 * <a href=
	 * "https://github.com/modelcontextprotocol/specification/blob/main/docs/specification/basic/lifecycle.md#initialization">初始化
	 * 规范</a>
	 * @return 初始化结果。
	 */
	public McpSchema.InitializeResult initialize() {
		// TODO: 这里block不带参数，因为我们假设异步客户端
		// 始终配置了requestTimeout
		return this.delegate.initialize().block();
	}

	/**
	 * 发送roots/list_changed通知。
	 */
	public void rootsListChangedNotification() {
		this.delegate.rootsListChangedNotification().block();
	}

	/**
	 * 动态添加根目录。
	 */
	public void addRoot(McpSchema.Root root) {
		this.delegate.addRoot(root).block();
	}

	/**
	 * 动态移除根目录。
	 */
	public void removeRoot(String rootUri) {
		this.delegate.removeRoot(rootUri).block();
	}

	/**
	 * 发送同步ping请求。
	 * @return ping响应
	 */
	public Object ping() {
		return this.delegate.ping().block();
	}

	// --------------------------
	// 工具
	// --------------------------
	/**
	 * 调用服务器提供的工具。工具使服务器能够暴露可执行的
	 * 功能，这些功能可以与外部系统交互、执行计算并在
	 * 真实世界中采取行动。
	 * @param callToolRequest 请求包含：- name：要调用的工具名称
	 * （必须匹配tools/list中的工具名称）- arguments：符合工具
	 * 输入模式的参数
	 * @return 工具执行结果包含：- content：表示工具输出的内容项列表
	 * （文本、图像或嵌入资源）- isError：指示执行是否失败（true）
	 * 或成功（false/absent）的布尔值
	 */
	public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest) {
		return this.delegate.callTool(callToolRequest).block();
	}

	/**
	 * 获取服务器提供的所有工具列表。
	 * @return 工具列表结果包含：- tools：可用工具列表，每个工具
	 * 都有名称、描述和输入模式 - nextCursor：如果有更多工具可用，
	 * 则为可选的分页游标
	 */
	public McpSchema.ListToolsResult listTools() {
		return this.delegate.listTools().block();
	}

	/**
	 * 获取服务器提供的分页工具列表。
	 * @param cursor 来自前一个列表请求的可选分页游标
	 * @return 工具列表结果包含：- tools：可用工具列表，每个工具
	 * 都有名称、描述和输入模式 - nextCursor：如果有更多工具可用，
	 * 则为可选的分页游标
	 */
	public McpSchema.ListToolsResult listTools(String cursor) {
		return this.delegate.listTools(cursor).block();
	}

	// --------------------------
	// 资源
	// --------------------------

	/**
	 * 发送resources/list请求。
	 * @param cursor 游标
	 * @return 资源列表结果。
	 */
	public McpSchema.ListResourcesResult listResources(String cursor) {
		return this.delegate.listResources(cursor).block();
	}

	/**
	 * 发送resources/list请求。
	 * @return 资源列表结果。
	 */
	public McpSchema.ListResourcesResult listResources() {
		return this.delegate.listResources().block();
	}

	/**
	 * 发送resources/read请求。
	 * @param resource 要读取的资源
	 * @return 资源内容。
	 */
	public McpSchema.ReadResourceResult readResource(McpSchema.Resource resource) {
		return this.delegate.readResource(resource).block();
	}

	/**
	 * 发送resources/read请求。
	 * @param readResourceRequest 读取资源请求。
	 * @return 资源内容。
	 */
	public McpSchema.ReadResourceResult readResource(McpSchema.ReadResourceRequest readResourceRequest) {
		return this.delegate.readResource(readResourceRequest).block();
	}

	/**
	 * 资源模板允许服务器使用URI模板暴露参数化资源。
	 * 参数可以通过自动完成API自动补全。
	 *
	 * 请求服务器拥有的资源模板列表。
	 * @param cursor 游标
	 * @return 资源模板列表结果。
	 */
	public McpSchema.ListResourceTemplatesResult listResourceTemplates(String cursor) {
		return this.delegate.listResourceTemplates(cursor).block();
	}

	/**
	 * 请求服务器拥有的资源模板列表。
	 * @return 资源模板列表结果。
	 */
	public McpSchema.ListResourceTemplatesResult listResourceTemplates() {
		return this.delegate.listResourceTemplates().block();
	}

	/**
	 * 订阅。协议支持对资源变更的可选订阅。
	 * 客户端可以订阅特定资源，并在资源发生变更时
	 * 接收通知。
	 *
	 * 发送resources/subscribe请求。
	 * @param subscribeRequest 订阅请求包含要订阅的资源的uri。
	 */
	public void subscribeResource(McpSchema.SubscribeRequest subscribeRequest) {
		this.delegate.subscribeResource(subscribeRequest).block();
	}

	/**
	 * 发送resources/unsubscribe请求。
	 * @param unsubscribeRequest 取消订阅请求包含要取消订阅的资源的uri。
	 */
	public void unsubscribeResource(McpSchema.UnsubscribeRequest unsubscribeRequest) {
		this.delegate.unsubscribeResource(unsubscribeRequest).block();
	}

	// --------------------------
	// 提示
	// --------------------------
	public ListPromptsResult listPrompts(String cursor) {
		return this.delegate.listPrompts(cursor).block();
	}

	public ListPromptsResult listPrompts() {
		return this.delegate.listPrompts().block();
	}

	public GetPromptResult getPrompt(GetPromptRequest getPromptRequest) {
		return this.delegate.getPrompt(getPromptRequest).block();
	}

	/**
	 * 客户端可以设置它想从服务器接收的最小日志级别。
	 * @param loggingLevel 最小日志级别
	 */
	public void setLoggingLevel(McpSchema.LoggingLevel loggingLevel) {
		this.delegate.setLoggingLevel(loggingLevel).block();
	}

	/**
	 * 发送completion/complete请求。
	 * @param completeRequest 完成请求包含提示或资源引用
	 * 以及用于生成建议的参数。
	 * @return 包含建议值的完成结果。
	 */
	public McpSchema.CompleteResult completeCompletion(McpSchema.CompleteRequest completeRequest) {
		return this.delegate.completeCompletion(completeRequest).block();
	}

}
