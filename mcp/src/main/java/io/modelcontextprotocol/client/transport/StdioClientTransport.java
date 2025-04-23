/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * MCP Stdio传输的实现，使用标准输入/输出流与服务器进程通信。
 * 消息以换行符分隔的JSON-RPC格式通过stdin/stdout交换，
 * 错误和调试信息通过stderr发送。
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public class StdioClientTransport implements McpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(StdioClientTransport.class);

	private final Sinks.Many<JSONRPCMessage> inboundSink;

	private final Sinks.Many<JSONRPCMessage> outboundSink;

	/** 正在通信的服务器进程 */
	private Process process;

	private ObjectMapper objectMapper;

	/** 用于处理来自服务器进程的入站消息的调度器 */
	private Scheduler inboundScheduler;

	/** 用于处理发送到服务器进程的出站消息的调度器 */
	private Scheduler outboundScheduler;

	/** 用于处理来自服务器进程的错误消息的调度器 */
	private Scheduler errorScheduler;

	/** 用于配置和启动服务器进程的参数 */
	private final ServerParameters params;

	private final Sinks.Many<String> errorSink;

	private volatile boolean isClosing = false;

	// visible for tests
	private Consumer<String> stdErrorHandler = error -> logger.info("STDERR Message received: {}", error);

	/**
	 * 使用指定参数和默认ObjectMapper创建新的StdioClientTransport。
	 * @param params 用于配置服务器进程的参数
	 */
	public StdioClientTransport(ServerParameters params) {
		this(params, new ObjectMapper());
	}

	/**
	 * 使用指定参数和ObjectMapper创建新的StdioClientTransport。
	 * @param params 用于配置服务器进程的参数
	 * @param objectMapper 用于JSON序列化/反序列化的ObjectMapper
	 */
	public StdioClientTransport(ServerParameters params, ObjectMapper objectMapper) {
		Assert.notNull(params, "The params can not be null");
		Assert.notNull(objectMapper, "The ObjectMapper can not be null");

		this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
		this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();

		this.params = params;

		this.objectMapper = objectMapper;

		this.errorSink = Sinks.many().unicast().onBackpressureBuffer();

		// Start threads
		this.inboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(), "inbound");
		this.outboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(), "outbound");
		this.errorScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(), "error");
	}

	/**
	 * 启动服务器进程并初始化消息处理流。此方法使用配置的命令、参数和环境
	 * 设置进程，然后启动入站、出站和错误处理线程。
	 * @throws RuntimeException 如果进程启动失败或进程流为空
	 */
	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		return Mono.<Void>fromRunnable(() -> {
			handleIncomingMessages(handler);
			handleIncomingErrors();

			// Prepare command and environment
			List<String> fullCommand = new ArrayList<>();
			fullCommand.add(params.getCommand());
			fullCommand.addAll(params.getArgs());

			ProcessBuilder processBuilder = this.getProcessBuilder();
			processBuilder.command(fullCommand);
			processBuilder.environment().putAll(params.getEnv());

			// Start the process
			try {
				this.process = processBuilder.start();
			}
			catch (IOException e) {
				throw new RuntimeException("Failed to start process with command: " + fullCommand, e);
			}

			// Validate process streams
			if (this.process.getInputStream() == null || process.getOutputStream() == null) {
				this.process.destroy();
				throw new RuntimeException("Process input or output stream is null");
			}

			// Start threads
			startInboundProcessing();
			startOutboundProcessing();
			startErrorProcessing();
		}).subscribeOn(Schedulers.boundedElastic());
	}

	/**
	 * 创建并返回一个新的ProcessBuilder实例。受保护以允许在测试中重写。
	 * @return 一个新的ProcessBuilder实例
	 */
	protected ProcessBuilder getProcessBuilder() {
		return new ProcessBuilder();
	}

	/**
	 * 设置用于处理传输层错误的处理器。
	 *
	 * <p>
	 * 当传输操作期间发生错误时（如连接失败或协议违规），
	 * 将调用提供的处理器。
	 * </p>
	 * @param errorHandler 处理错误消息的消费者
	 */
	public void setStdErrorHandler(Consumer<String> errorHandler) {
		this.stdErrorHandler = errorHandler;
	}

	/**
	 * 等待服务器进程退出。
	 * @throws RuntimeException 如果等待过程中进程被中断
	 */
	public void awaitForExit() {
		try {
			this.process.waitFor();
		}
		catch (InterruptedException e) {
			throw new RuntimeException("Process interrupted", e);
		}
	}

	/**
	 * 启动从进程错误流读取的错误处理线程。
	 * 错误消息被记录并发送到错误接收器。
	 */
	private void startErrorProcessing() {
		this.errorScheduler.schedule(() -> {
			try (BufferedReader processErrorReader = new BufferedReader(
					new InputStreamReader(process.getErrorStream()))) {
				String line;
				while (!isClosing && (line = processErrorReader.readLine()) != null) {
					try {
						if (!this.errorSink.tryEmitNext(line).isSuccess()) {
							if (!isClosing) {
								logger.error("Failed to emit error message");
							}
							break;
						}
					}
					catch (Exception e) {
						if (!isClosing) {
							logger.error("Error processing error message", e);
						}
						break;
					}
				}
			}
			catch (IOException e) {
				if (!isClosing) {
					logger.error("Error reading from error stream", e);
				}
			}
			finally {
				isClosing = true;
				errorSink.tryEmitComplete();
			}
		});
	}

	private void handleIncomingMessages(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> inboundMessageHandler) {
		this.inboundSink.asFlux()
			.flatMap(message -> Mono.just(message)
				.transform(inboundMessageHandler)
				.contextWrite(ctx -> ctx.put("observation", "myObservation")))
			.subscribe();
	}

	private void handleIncomingErrors() {
		this.errorSink.asFlux().subscribe(e -> {
			this.stdErrorHandler.accept(e);
		});
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		if (this.outboundSink.tryEmitNext(message).isSuccess()) {
			// TODO: essentially we could reschedule ourselves in some time and make
			// another attempt with the already read data but pause reading until
			// success
			// In this approach we delegate the retry and the backpressure onto the
			// caller. This might be enough for most cases.
			return Mono.empty();
		}
		else {
			return Mono.error(new RuntimeException("Failed to enqueue message"));
		}
	}

	/**
	 * 启动从进程输入流读取JSON-RPC消息的入站处理线程。
	 * 消息被反序列化并发送到入站接收器。
	 */
	private void startInboundProcessing() {
		this.inboundScheduler.schedule(() -> {
			try (BufferedReader processReader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				String line;
				while (!isClosing && (line = processReader.readLine()) != null) {
					try {
						JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(this.objectMapper, line);
						if (!this.inboundSink.tryEmitNext(message).isSuccess()) {
							if (!isClosing) {
								logger.error("Failed to enqueue inbound message: {}", message);
							}
							break;
						}
					}
					catch (Exception e) {
						if (!isClosing) {
							logger.error("Error processing inbound message for line: " + line, e);
						}
						break;
					}
				}
			}
			catch (IOException e) {
				if (!isClosing) {
					logger.error("Error reading from input stream", e);
				}
			}
			finally {
				isClosing = true;
				inboundSink.tryEmitComplete();
			}
		});
	}

	/**
	 * 启动将JSON-RPC消息写入进程输出流的出站处理线程。
	 * 消息被序列化为JSON并以换行符作为分隔符写入。
	 */
	private void startOutboundProcessing() {
		this.handleOutbound(messages -> messages
			// this bit is important since writes come from user threads, and we
			// want to ensure that the actual writing happens on a dedicated thread
			.publishOn(outboundScheduler)
			.handle((message, s) -> {
				if (message != null && !isClosing) {
					try {
						String jsonMessage = objectMapper.writeValueAsString(message);
						// Escape any embedded newlines in the JSON message as per spec:
						// https://spec.modelcontextprotocol.io/specification/basic/transports/#stdio
						// - Messages are delimited by newlines, and MUST NOT contain
						// embedded newlines.
						jsonMessage = jsonMessage.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");

						var os = this.process.getOutputStream();
						synchronized (os) {
							os.write(jsonMessage.getBytes(StandardCharsets.UTF_8));
							os.write("\n".getBytes(StandardCharsets.UTF_8));
							os.flush();
						}
						s.next(message);
					}
					catch (IOException e) {
						s.error(new RuntimeException(e));
					}
				}
			}));
	}

	protected void handleOutbound(Function<Flux<JSONRPCMessage>, Flux<JSONRPCMessage>> outboundConsumer) {
		outboundConsumer.apply(outboundSink.asFlux()).doOnComplete(() -> {
			isClosing = true;
			outboundSink.tryEmitComplete();
		}).doOnError(e -> {
			if (!isClosing) {
				logger.error("Error in outbound processing", e);
				isClosing = true;
				outboundSink.tryEmitComplete();
			}
		}).subscribe();
	}

	/**
	 * Gracefully closes the transport by destroying the process and disposing of the
	 * schedulers. This method sends a TERM signal to the process and waits for it to exit
	 * before cleaning up resources.
	 * @return A Mono that completes when the transport is closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			isClosing = true;
			logger.debug("Initiating graceful shutdown");
		}).then(Mono.defer(() -> {
			// First complete all sinks to stop accepting new messages
			inboundSink.tryEmitComplete();
			outboundSink.tryEmitComplete();
			errorSink.tryEmitComplete();

			// Give a short time for any pending messages to be processed
			return Mono.delay(Duration.ofMillis(100));
		})).then(Mono.defer(() -> {
			logger.debug("Sending TERM to process");
			if (this.process != null) {
				this.process.destroy();
				return Mono.fromFuture(process.onExit());
			}
			else {
				logger.warn("Process not started");
				return Mono.empty();
			}
		})).doOnNext(process -> {
			if (process.exitValue() != 0) {
				logger.warn("Process terminated with code " + process.exitValue());
			}
		}).then(Mono.fromRunnable(() -> {
			try {
				// The Threads are blocked on readLine so disposeGracefully would not
				// interrupt them, therefore we issue an async hard dispose.
				inboundScheduler.dispose();
				errorScheduler.dispose();
				outboundScheduler.dispose();

				logger.debug("Graceful shutdown completed");
			}
			catch (Exception e) {
				logger.error("Error during graceful shutdown", e);
			}
		})).then().subscribeOn(Schedulers.boundedElastic());
	}

	public Sinks.Many<String> getErrorSink() {
		return this.errorSink;
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

}
