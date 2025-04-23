/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Timeout;

/**
 * 使用{@link StdioServerTransportProvider}对{@link McpSyncServer}进行测试。
 *
 * @author Christian Tzolov
 */
@Timeout(15) // 提供超出客户端超时的额外时间
class StdioMcpSyncServerTests extends AbstractMcpSyncServerTests {

	@Override
	protected McpServerTransportProvider createMcpTransportProvider() {
		return new StdioServerTransportProvider();
	}

}
