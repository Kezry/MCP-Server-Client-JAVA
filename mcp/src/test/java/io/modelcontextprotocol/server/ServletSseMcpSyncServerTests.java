/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Timeout;

/**
 * 使用{@link HttpServletSseServerTransportProvider}对{@link McpSyncServer}进行测试。
 *
 * @author Christian Tzolov
 */
@Timeout(15) // 提供超出客户端超时的额外时间
class ServletSseMcpSyncServerTests extends AbstractMcpSyncServerTests {

	@Override
	protected McpServerTransportProvider createMcpTransportProvider() {
		return HttpServletSseServerTransportProvider.builder().messageEndpoint("/mcp/message").build();
	}

}
