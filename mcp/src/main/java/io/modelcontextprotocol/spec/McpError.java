/*
 * Copyright 2024 - 2024 原始作者保留所有权利。
 */
package io.modelcontextprotocol.spec;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;

public class McpError extends RuntimeException {

	private JSONRPCError jsonRpcError;

	public McpError(JSONRPCError jsonRpcError) {
		super(jsonRpcError.message());
		this.jsonRpcError = jsonRpcError;
	}

	public McpError(Object error) {
		super(error.toString());
	}

	public JSONRPCError getJsonRpcError() {
		return jsonRpcError;
	}

}