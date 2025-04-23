package io.modelcontextprotocol.spec;

import java.util.Map;

import reactor.core.publisher.Mono;

/**
 * 提供服务器端MCP传输的核心构建块。实现此接口以在特定的服务器端技术和MCP服务器
 * 传输层之间建立桥接。
 *
 * <p>
 * 提供者的生命周期规定它首先在应用程序启动时创建，然后传递给
 * {@link io.modelcontextprotocol.server.McpServer#sync(McpServerTransportProvider)} 或
 * {@link io.modelcontextprotocol.server.McpServer#async(McpServerTransportProvider)}。
 * 作为MCP服务器创建的结果，提供者将收到一个 {@link McpServerSession.Factory} 的通知，
 * 该工厂将用于处理新连接的客户端和服务器之间的1:1通信。提供者的职责是创建
 * {@link McpServerTransport} 的实例，会话将在其生命周期内使用这些实例。
 *
 * <p>
 * 最后，当作为正常应用程序关闭事件的一部分调用 {@link #close()} 或
 * {@link #closeGracefully()} 时，{@link McpServerTransport} 可以批量关闭。
 * 单个 {@link McpServerTransport} 也可以在每个会话的基础上关闭，其中
 * {@link McpServerSession#close()} 或 {@link McpServerSession#closeGracefully()}
 * 关闭提供的传输。
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpServerTransportProvider {

	/**
	 * 设置将用于为新客户端创建会话的会话工厂。MCP服务器的实现必须在任何MCP交互
	 * 发生之前调用此方法。
	 * @param sessionFactory 用于初始化客户端会话的会话工厂
	 */
	void setSessionFactory(McpServerSession.Factory sessionFactory);

	/**
	 * 向所有已连接的客户端发送通知。
	 * @param method 要在客户端上调用的通知方法的名称
	 * @param params 要与通知一起发送的参数
	 * @return 当通知已广播时完成的Mono
	 * @see McpSession#sendNotification(String, Map)
	 */
	Mono<Void> notifyClients(String method, Object params);

	/**
	 * 立即关闭所有已连接客户端的传输并释放任何相关资源。
	 */
	default void close() {
		this.closeGracefully().subscribe();
	}

	/**
	 * 优雅地关闭所有已连接客户端的传输，并异步释放任何相关资源。
	 * @return 当连接已关闭时完成的 {@link Mono<Void>}。
	 */
	Mono<Void> closeGracefully();

}
