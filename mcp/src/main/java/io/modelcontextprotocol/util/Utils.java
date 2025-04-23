/*
 * Copyright 2024-2024 原始作者保留所有权利。
 */

package io.modelcontextprotocol.util;

import reactor.util.annotation.Nullable;

import java.net.URI;
import java.util.Collection;
import java.util.Map;

/**
 * 杂项工具方法。
 *
 * @author Christian Tzolov
 */

public final class Utils {

	/**
	 * 检查给定的{@code String}是否包含实际的<em>文本</em>。
	 * <p>
	 * 更具体地说，如果{@code String}不为{@code null}，其长度大于0，
	 * 并且至少包含一个非空白字符，则此方法返回{@code true}。
	 * @param str 要检查的{@code String}（可能为{@code null}）
	 * @return 如果{@code String}不为{@code null}，其长度大于0，
	 * 且不仅包含空白字符，则返回{@code true}
	 * @see Character#isWhitespace
	 */
	public static boolean hasText(@Nullable String str) {
		return (str != null && !str.isBlank());
	}

	/**
	 * 如果提供的Collection为{@code null}或为空，则返回{@code true}。
	 * 否则，返回{@code false}。
	 * @param collection 要检查的Collection
	 * @return 给定的Collection是否为空
	 */
	public static boolean isEmpty(@Nullable Collection<?> collection) {
		return (collection == null || collection.isEmpty());
	}

	/**
	 * 如果提供的Map为{@code null}或为空，则返回{@code true}。
	 * 否则，返回{@code false}。
	 * @param map 要检查的Map
	 * @return 给定的Map是否为空
	 */
	public static boolean isEmpty(@Nullable Map<?, ?> map) {
		return (map == null || map.isEmpty());
	}

	/**
	 * 根据基础URL解析给定的端点URL。
	 * <ul>
	 * <li>如果端点URL是相对的，它将根据基础URL进行解析。</li>
	 * <li>如果端点URL是绝对的，将进行验证以确保它与基础URL的方案、
	 * 权限和路径前缀匹配。</li>
	 * <li>如果绝对URL的验证失败，将抛出{@link IllegalArgumentException}。</li>
	 * </ul>
	 * @param baseUrl 基础URL（必须是绝对的）
	 * @param endpointUrl 端点URL（可以是相对的或绝对的）
	 * @return 解析后的端点URI
	 * @throws IllegalArgumentException 如果绝对端点URL与基础URL不匹配
	 * 或URI格式不正确
	 */
	public static URI resolveUri(URI baseUrl, String endpointUrl) {
		URI endpointUri = URI.create(endpointUrl);
		if (endpointUri.isAbsolute() && !isUnderBaseUri(baseUrl, endpointUri)) {
			throw new IllegalArgumentException("Absolute endpoint URL does not match the base URL.");
		}
		else {
			return baseUrl.resolve(endpointUri);
		}
	}

	/**
	 * 检查给定的绝对端点URI是否属于基础URI。它验证方案、权限（主机和端口），
	 * 并确保基础路径是端点路径的前缀。
	 * @param baseUri 基础URI
	 * @param endpointUri 要检查的端点URI
	 * @return 如果endpointUri在baseUri的层次结构内则返回true，否则返回false
	 */
	private static boolean isUnderBaseUri(URI baseUri, URI endpointUri) {
		if (!baseUri.getScheme().equals(endpointUri.getScheme())
				|| !baseUri.getAuthority().equals(endpointUri.getAuthority())) {
			return false;
		}

		URI normalizedBase = baseUri.normalize();
		URI normalizedEndpoint = endpointUri.normalize();

		String basePath = normalizedBase.getPath();
		String endpointPath = normalizedEndpoint.getPath();

		if (basePath.endsWith("/")) {
			basePath = basePath.substring(0, basePath.length() - 1);
		}
		return endpointPath.startsWith(basePath);
	}

}
