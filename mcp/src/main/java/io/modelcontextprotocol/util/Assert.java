/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.util;

import java.util.Collection;

import reactor.util.annotation.Nullable;

/**
 * 用于参数验证的断言工具类。
 *
 * @author Christian Tzolov
 */

/**
 * 提供参数验证断言方法的工具类。
 */
public final class Assert {

	/**
	 * 断言集合不为 {@code null} 且不为空。
	 * @param collection 要检查的集合
	 * @param message 断言失败时使用的异常消息
	 * @throws IllegalArgumentException 如果集合为 {@code null} 或为空
	 */
	public static void notEmpty(@Nullable Collection<?> collection, String message) {
		if (collection == null || collection.isEmpty()) {
			throw new IllegalArgumentException(message);
		}
	}

	/**
	 * 断言对象不为 {@code null}。
	 *
	 * <pre class="code">
	 * Assert.notNull(clazz, "The class must not be null");
	 * </pre>
	 * @param object 要检查的对象
	 * @param message 断言失败时使用的异常消息
	 * @throws IllegalArgumentException 如果对象为 {@code null}
	 */
	public static void notNull(@Nullable Object object, String message) {
		if (object == null) {
			throw new IllegalArgumentException(message);
		}
	}

	/**
	 * 断言给定的字符串包含有效的文本内容；即它不能为 {@code null} 且必须包含至少一个非空白字符。
	 * <pre class="code">Assert.hasText(name, "'name' must not be empty");</pre>
	 * @param text 要检查的字符串
	 * @param message 断言失败时使用的异常消息
	 * @throws IllegalArgumentException 如果文本不包含有效的文本内容
	 */
	public static void hasText(@Nullable String text, String message) {
		if (!hasText(text)) {
			throw new IllegalArgumentException(message);
		}
	}

	/**
	 * 检查给定的 {@code String} 是否包含实际的<em>文本</em>。
	 * <p>
	 * 更具体地说，如果 {@code String} 不为 {@code null}，其长度大于0，
	 * 并且包含至少一个非空白字符，则此方法返回 {@code true}。
	 * @param str 要检查的 {@code String}（可能为 {@code null}）
	 * @return 如果 {@code String} 不为 {@code null}，其长度大于0，
	 * 且不仅包含空白字符，则返回 {@code true}
	 * @see Character#isWhitespace
	 */
	public static boolean hasText(@Nullable String str) {
		return (str != null && !str.isBlank());
	}

}
