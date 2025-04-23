/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.client.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 使用Java的Flow API实现的服务器发送事件(SSE)客户端，用于响应式流处理。
 * 该客户端建立与SSE端点的连接，并处理传入的事件流，将SSE格式的消息解析为结构化事件。
 *
 * <p>
 * 客户端支持标准的SSE事件字段，包括：
 * <ul>
 * <li>event - 事件类型（如果未指定，默认为"message"）</li>
 * <li>id - 事件ID</li>
 * <li>data - 事件负载数据</li>
 * </ul>
 *
 * <p>
 * 事件将被传递给提供的{@link SseEventHandler}，用于处理事件和连接过程中发生的任何错误。
 *
 * @author Christian Tzolov
 * @see SseEventHandler
 * @see SseEvent
 */
public class FlowSseClient {

	private final HttpClient httpClient;

	private final HttpRequest.Builder requestBuilder;

	/**
	 * 用于从SSE数据字段行中提取数据内容的模式。匹配以"data:"开头的行并捕获剩余内容。
	 */
	private static final Pattern EVENT_DATA_PATTERN = Pattern.compile("^data:(.+)$", Pattern.MULTILINE);

	/**
	 * 用于从SSE id字段行中提取事件ID的模式。匹配以"id:"开头的行并捕获ID值。
	 */
	private static final Pattern EVENT_ID_PATTERN = Pattern.compile("^id:(.+)$", Pattern.MULTILINE);

	/**
	 * 用于从SSE事件字段行中提取事件类型的模式。匹配以"event:"开头的行并捕获事件类型。
	 */
	private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("^event:(.+)$", Pattern.MULTILINE);

	/**
	 * 表示具有标准字段的服务器发送事件的记录类。
	 *
	 * @param id 事件ID（可能为null）
	 * @param type 事件类型（如果在流中未指定，则默认为"message"）
	 * @param data 事件负载数据
	 */
	public static record SseEvent(String id, String type, String data) {
	}

	/**
	 * 用于处理SSE事件和错误的接口。实现类可以处理接收到的事件和SSE连接期间发生的任何错误。
	 */
	public interface SseEventHandler {

		/**
		 * 当接收到SSE事件时调用。
		 * @param event 包含id、type和data的接收到的SSE事件
		 */
		void onEvent(SseEvent event);

		/**
		 * 当SSE连接期间发生错误时调用。
		 * @param error 发生的错误
		 */
		void onError(Throwable error);

	}

	/**
	 * 使用指定的HTTP客户端创建新的FlowSseClient。
	 * @param httpClient 用于SSE连接的{@link HttpClient}实例
	 */
	public FlowSseClient(HttpClient httpClient) {
		this(httpClient, HttpRequest.newBuilder());
	}

	/**
	 * 使用指定的HTTP客户端和请求构建器创建新的FlowSseClient。
	 * @param httpClient 用于SSE连接的{@link HttpClient}实例
	 * @param requestBuilder 用于SSE请求的{@link HttpRequest.Builder}
	 */
	public FlowSseClient(HttpClient httpClient, HttpRequest.Builder requestBuilder) {
		this.httpClient = httpClient;
		this.requestBuilder = requestBuilder;
	}

	/**
	 * 订阅SSE端点并处理事件流。
	 *
	 * <p>
	 * 此方法建立与指定URL的连接并开始处理SSE流。事件被解析并传递给提供的事件处理器。
	 * 连接保持活动状态，直到发生错误或服务器关闭连接。
	 * @param url 要连接的SSE端点URL
	 * @param eventHandler 接收SSE事件和错误通知的处理器
	 * @throws RuntimeException 如果连接失败且状态码不是200
	 */
	public void subscribe(String url, SseEventHandler eventHandler) {
		HttpRequest request = this.requestBuilder.uri(URI.create(url))
			.header("Accept", "text/event-stream")
			.header("Cache-Control", "no-cache")
			.GET()
			.build();

		StringBuilder eventBuilder = new StringBuilder();
		AtomicReference<String> currentEventId = new AtomicReference<>();
		AtomicReference<String> currentEventType = new AtomicReference<>("message");

		Flow.Subscriber<String> lineSubscriber = new Flow.Subscriber<>() {
			private Flow.Subscription subscription;

			@Override
			public void onSubscribe(Flow.Subscription subscription) {
				this.subscription = subscription;
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(String line) {
				if (line.isEmpty()) {
					// 空行表示事件结束
					if (eventBuilder.length() > 0) {
						String eventData = eventBuilder.toString();
						SseEvent event = new SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());
						eventHandler.onEvent(event);
						eventBuilder.setLength(0);
					}
				}
				else {
					if (line.startsWith("data:")) {
						var matcher = EVENT_DATA_PATTERN.matcher(line);
						if (matcher.find()) {
							eventBuilder.append(matcher.group(1).trim()).append("\n");
						}
					}
					else if (line.startsWith("id:")) {
						var matcher = EVENT_ID_PATTERN.matcher(line);
						if (matcher.find()) {
							currentEventId.set(matcher.group(1).trim());
						}
					}
					else if (line.startsWith("event:")) {
						var matcher = EVENT_TYPE_PATTERN.matcher(line);
						if (matcher.find()) {
							currentEventType.set(matcher.group(1).trim());
						}
					}
				}
				subscription.request(1);
			}

			@Override
			public void onError(Throwable throwable) {
				eventHandler.onError(throwable);
			}

			@Override
			public void onComplete() {
				// 处理任何剩余的事件数据
				if (eventBuilder.length() > 0) {
					String eventData = eventBuilder.toString();
					SseEvent event = new SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());
					eventHandler.onEvent(event);
				}
			}
		};

		Function<Flow.Subscriber<String>, HttpResponse.BodySubscriber<Void>> subscriberFactory = subscriber -> HttpResponse.BodySubscribers
			.fromLineSubscriber(subscriber);

		CompletableFuture<HttpResponse<Void>> future = this.httpClient.sendAsync(request,
				info -> subscriberFactory.apply(lineSubscriber));

		future.thenAccept(response -> {
			int status = response.statusCode();
			if (status != 200 && status != 201 && status != 202 && status != 206) {
				throw new RuntimeException("Failed to connect to SSE stream. Unexpected status code: " + status);
			}
		}).exceptionally(throwable -> {
			eventHandler.onError(throwable);
			return null;
		});
	}

}
