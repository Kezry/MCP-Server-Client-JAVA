# MCP Server

> 学习如何实现和配置Model Context Protocol (MCP)服务器

<Note>
  ### 0.8.x版本的重大变更 ⚠️

  **注意：** 0.8.x版本引入了几个重大变更，包括新的基于会话的架构。
  如果你正在从0.7.0版本升级，请参考[迁移指南](https://github.com/modelcontextprotocol/java-sdk/blob/main/migration-0.8.0.md)获取详细说明。
</Note>

## 概述

MCP服务器是Model Context Protocol (MCP)架构中的一个基础组件，为客户端提供工具、资源和功能。它实现了协议的服务器端，负责：

* 暴露客户端可以发现和执行的工具
* 使用基于URI的访问模式管理资源
* 提供提示模板并处理提示请求
* 支持与客户端的功能协商
* 实现服务器端协议操作
* 管理并发客户端连接
* 提供结构化日志和通知

<Tip>
  核心的 `io.modelcontextprotocol.sdk:mcp` 模块提供了STDIO和SSE服务器传输实现，无需外部Web框架。

  [Spring Framework](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)用户可以使用作为**可选**依赖的Spring特定传输实现 `io.modelcontextprotocol.sdk:mcp-spring-webflux`、`io.modelcontextprotocol.sdk:mcp-spring-webmvc`。
</Tip>

服务器同时支持同步和异步API，允许在不同的应用程序上下文中进行灵活集成。

    ```java
    // Create a server with custom configuration
    McpSyncServer syncServer = McpServer.sync(transportProvider)
        .serverInfo("my-server", "1.0.0")
        .capabilities(ServerCapabilities.builder()
            .resources(true)     // Enable resource support
            .tools(true)         // Enable tool support
            .prompts(true)       // Enable prompt support
            .logging()           // Enable logging support
            .completions()      // Enable completions support
            .build())
        .build();

    // Register tools, resources, and prompts
    syncServer.addTool(syncToolSpecification);
    syncServer.addResource(syncResourceSpecification);
    syncServer.addPrompt(syncPromptSpecification);

    // Close the server when done
    syncServer.close();
    ```

    ```java
    // Create an async server with custom configuration
    McpAsyncServer asyncServer = McpServer.async(transportProvider)
        .serverInfo("my-server", "1.0.0")
        .capabilities(ServerCapabilities.builder()
            .resources(true)     // Enable resource support
            .tools(true)         // Enable tool support
            .prompts(true)       // Enable prompt support
            .logging()           // Enable logging support
            .build())
        .build();

    // Register tools, resources, and prompts
    asyncServer.addTool(asyncToolSpecification)
        .doOnSuccess(v -> logger.info("Tool registered"))
        .subscribe();

    asyncServer.addResource(asyncResourceSpecification)
        .doOnSuccess(v -> logger.info("Resource registered"))
        .subscribe();

    asyncServer.addPrompt(asyncPromptSpecification)
        .doOnSuccess(v -> logger.info("Prompt registered"))
        .subscribe();

    // Close the server when done
    asyncServer.close()
        .doOnSuccess(v -> logger.info("Server closed"))
        .subscribe();
    ```
    

## Server Transport Providers

MCP SDK中的传输层负责处理客户端和服务器之间的通信。
它提供了不同的实现来支持各种通信协议和模式。
SDK包含了几个内置的传输提供者实现：

<!-- 
    <>
      Create in-process based transport: -->

      ```java
      StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(new ObjectMapper());
      ```
<!-- 
      Provides bidirectional JSON-RPC message handling over standard input/output streams with non-blocking message processing, serialization/deserialization, and graceful shutdown support.

      Key features:

      <ul>
        <li>Bidirectional communication through stdin/stdout</li>
        <li>Process-based integration support</li>
        <li>Simple setup and configuration</li>
        <li>Lightweight implementation</li>
      </ul>
    </> -->
    


      ```java
      @Configuration
      class McpConfig {
          @Bean
          WebFluxSseServerTransportProvider webFluxSseServerTransportProvider(ObjectMapper mapper) {
              return new WebFluxSseServerTransportProvider(mapper, "/mcp/message");
          }

          @Bean
          RouterFunction<?> mcpRouterFunction(WebFluxSseServerTransportProvider transportProvider) {
              return transportProvider.getRouterFunction();
          }
      }
      ```
<!-- 
      <p>实现了带有SSE传输规范的MCP HTTP，提供：</p>

      <ul>
        <li>Reactive HTTP streaming with WebFlux</li>
        <li>Concurrent client connections through SSE endpoints</li>
        <li>Message routing and session management</li>
        <li>Graceful shutdown capabilities</li>
      </ul>
    </>
    
    <>
      <p>创建基于WebMvc的SSE服务器传输。<br />需要 <code>mcp-spring-webmvc</code> 依赖。</p> -->

      ```java
      @Configuration
      @EnableWebMvc
      class McpConfig {
          @Bean
          WebMvcSseServerTransportProvider webMvcSseServerTransportProvider(ObjectMapper mapper) {
              return new WebMvcSseServerTransportProvider(mapper, "/mcp/message");
          }

          @Bean
          RouterFunction<ServerResponse> mcpRouterFunction(WebMvcSseServerTransportProvider transportProvider) {
              return transportProvider.getRouterFunction();
          }
      }
      ```
<!-- 
      <p>实现了带有SSE传输规范的MCP HTTP，提供：</p>

      <ul>
        <li>服务器端事件流</li>
        <li>与Spring WebMVC集成</li>
        <li>支持传统Web应用</li>
        <li>同步操作处理</li>
      </ul>
    </> -->
    
<!--     
    <>
      <p>
        创建基于Servlet的SSE服务器传输。它包含在核心 <code>mcp</code> 模块中。<br />
        <code>HttpServletSseServerTransport</code> 可以与任何Servlet容器一起使用。<br />
        要在Spring Web应用程序中使用它，你可以将其注册为Servlet bean:
      </p> -->

      ```java
      @Configuration
      @EnableWebMvc
      public class McpServerConfig implements WebMvcConfigurer {

          @Bean
          public HttpServletSseServerTransportProvider servletSseServerTransportProvider() {
              return new HttpServletSseServerTransportProvider(new ObjectMapper(), "/mcp/message");
          }

          @Bean
          public ServletRegistrationBean customServletBean(HttpServletSseServerTransportProvider transportProvider) {
              return new ServletRegistrationBean(transportProvider);
          }
      }
      `
<!-- 
      <p>
        使用传统的Servlet API实现带有SSE传输规范的MCP HTTP，提供：
      </p>

      <ul>
        <li>使用Servlet 6.0异步支持的异步消息处理</li>
        <li>多客户端连接的会话管理</li>

        <li>
          两种类型的端点：

          <ul>
            <li>用于服务器到客户端事件的SSE端点 (<code>/sse</code>)</li>
            <li>用于客户端到服务器请求的消息端点（可配置）</li>
          </ul>
        </li>

        <li>错误处理和响应格式化</li>
        <li>优雅关闭支持</li>
      </ul>
    </> -->
    

## 服务器功能

服务器可以配置各种功能：

```java
var capabilities = ServerCapabilities.builder()
    .resources(false, true)  // 资源支持，带有列表变更通知
    .tools(true)            // 工具支持，带有列表变更通知
    .prompts(true)          // 提示支持，带有列表变更通知
    .logging()              // 启用日志支持（默认启用，日志级别为INFO）
    .build();
```

### 日志支持

服务器提供结构化日志功能，允许向客户端发送不同严重级别的日志消息：

```java
// Send a log message to clients
server.loggingNotification(LoggingMessageNotification.builder()
    .level(LoggingLevel.INFO)
    .logger("custom-logger")
    .data("Custom log message")
    .build());
```

客户端可以通过 `mcpClient.setLoggingLevel(level)` 请求控制他们接收的最低日志级别。低于设置级别的消息将被过滤掉。
支持的日志级别（按严重程度递增排序）：DEBUG (0)、INFO (1)、NOTICE (2)、WARNING (3)、ERROR (4)、CRITICAL (5)、ALERT (6)、EMERGENCY (7)

### 工具规范

Model Context Protocol允许服务器[暴露工具](/specification/2024-11-05/server/tools/)，这些工具可以被语言模型调用。
Java SDK允许实现带有处理函数的工具规范。
工具使AI模型能够执行计算、访问外部API、查询数据库和操作文件：

    ```java
    // Sync tool specification
    var schema = """
                {
                  "type" : "object",
                  "id" : "urn:jsonschema:Operation",
                  "properties" : {
                    "operation" : {
                      "type" : "string"
                    },
                    "a" : {
                      "type" : "number"
                    },
                    "b" : {
                      "type" : "number"
                    }
                  }
                }
                """;
    var syncToolSpecification = new McpServerFeatures.SyncToolSpecification(
        new Tool("calculator", "Basic calculator", schema),
        (exchange, arguments) -> {
            // Tool implementation
            return new CallToolResult(result, false);
        }
    );
    ```

    

    ```java
    // Async tool specification
    var schema = """
                {
                  "type" : "object",
                  "id" : "urn:jsonschema:Operation",
                  "properties" : {
                    "operation" : {
                      "type" : "string"
                    },
                    "a" : {
                      "type" : "number"
                    },
                    "b" : {
                      "type" : "number"
                    }
                  }
                }
                """;
    var asyncToolSpecification = new McpServerFeatures.AsyncToolSpecification(
        new Tool("calculator", "Basic calculator", schema),
        (exchange, arguments) -> {
            // Tool implementation
            return Mono.just(new CallToolResult(result, false));
        }
    );
    ```

工具规范包括带有 `name`（名称）、`description`（描述）和 `parameter schema`（参数模式）的工具定义，以及实现工具逻辑的调用处理程序。
函数的第一个参数是用于客户端交互的 `McpAsyncServerExchange`，第二个参数是工具参数的映射。

### 资源规范

资源及其处理函数的规范。
资源通过暴露如下数据为AI模型提供上下文：文件内容、数据库记录、API响应、系统信息、应用程序状态。
资源规范示例：


    ```java
    // Sync resource specification
    var syncResourceSpecification = new McpServerFeatures.SyncResourceSpecification(
        new Resource("custom://resource", "name", "description", "mime-type", null),
        (exchange, request) -> {
            // Resource read implementation
            return new ReadResourceResult(contents);
        }
    );
    ```
    
    ```java
    // Async resource specification
    var asyncResourceSpecification = new McpServerFeatures.AsyncResourceSpecification(
        new Resource("custom://resource", "name", "description", "mime-type", null),
        (exchange, request) -> {
            // Resource read implementation
            return Mono.just(new ReadResourceResult(contents));
        }
    );
    ```
    

资源规范由资源定义和资源读取处理程序组成。
资源定义包括 `name`（名称）、`description`（描述）和 `MIME type`（MIME类型）。
处理资源读取请求的函数的第一个参数是 `McpAsyncServerExchange`，服务器可以通过它与已连接的客户端交互。
第二个参数是 `McpSchema.ReadResourceRequest`。

### 提示规范

作为[提示功能](/specification/2024-11-05/server/prompts/)的一部分，MCP为服务器提供了一种标准化的方式来向客户端暴露提示模板。
提示规范是一个用于AI模型交互的结构化模板，它支持一致的消息格式化、参数替换、上下文注入、响应格式化和指令模板化。


    ```java
    // Sync prompt specification
    var syncPromptSpecification = new McpServerFeatures.SyncPromptSpecification(
        new Prompt("greeting", "description", List.of(
            new PromptArgument("name", "description", true)
        )),
        (exchange, request) -> {
            // Prompt implementation
            return new GetPromptResult(description, messages);
        }
    );
    ```
    
    ```java
    // Async prompt specification
    var asyncPromptSpecification = new McpServerFeatures.AsyncPromptSpecification(
        new Prompt("greeting", "description", List.of(
            new PromptArgument("name", "description", true)
        )),
        (exchange, request) -> {
            // Prompt implementation
            return Mono.just(new GetPromptResult(description, messages));
        }
    );
    ```
    

提示定义包括name（提示的标识符）、description（提示的目的）和参数列表（用于模板化的参数）。
处理函数处理请求并返回格式化的模板。
第一个参数是用于客户端交互的 `McpAsyncServerExchange`，第二个参数是 `GetPromptRequest` 实例。

### 完成规范

作为[完成功能](/specification/2025-03-26/server/utilities/completion)的一部分，MCP为服务器提供了一种标准化的方式来为提示和资源URI提供参数自动完成建议。


    ```java
    // Sync completion specification
    var syncCompletionSpecification = new McpServerFeatures.SyncCompletionSpecification(
    			new McpSchema.PromptReference("code_review"), (exchange, request) -> {
            
            // completion implementation ...
            
            return new McpSchema.CompleteResult(
                new CompleteResult.CompleteCompletion(
                  List.of("python", "pytorch", "pyside"), 
                  10, // total
                  false // hasMore
                ));
          }
    );

    // Create a sync server with completion capabilities
    var mcpServer = McpServer.sync(mcpServerTransportProvider)
      .capabilities(ServerCapabilities.builder()
        .completions() // enable completions support
          // ...
        .build())
      // ...
      .completions(new McpServerFeatures.SyncCompletionSpecification( // register completion specification
          new McpSchema.PromptReference("code_review"), syncCompletionSpecification))
      .build();

    ```
    
    ```java
    // Async prompt specification
    var asyncCompletionSpecification = new McpServerFeatures.AsyncCompletionSpecification(
    			new McpSchema.PromptReference("code_review"), (exchange, request) -> {

            // completion implementation ...

            return Mono.just(new McpSchema.CompleteResult(
                new CompleteResult.CompleteCompletion(
                  List.of("python", "pytorch", "pyside"), 
                  10, // total
                  false // hasMore
                )));
          }
    );

    // Create a async server with completion capabilities
    var mcpServer = McpServer.async(mcpServerTransportProvider)
      .capabilities(ServerCapabilities.builder()
        .completions() // enable completions support
          // ...
        .build())
      // ...
      .completions(new McpServerFeatures.AsyncCompletionSpecification( // register completion specification
          new McpSchema.PromptReference("code_review"), asyncCompletionSpecification))
      .build();

    ```
    

`McpSchema.CompletionReference` 定义指定了类型（`PromptRefernce` 或 `ResourceRefernce`）和完成规范的标识符（例如处理程序）。
处理程序函数处理请求并返回完成响应。
第一个参数是用于客户端交互的 `McpAsyncServerExchange`，第二个参数是 `CompleteRequest` 实例。

查看[使用完成功能](/sdk/java/mcp-client#using-completion)了解如何在客户端使用完成功能。

### 从服务器使用采样

要使用[采样功能](/specification/2024-11-05/client/sampling/)，请连接到支持采样的客户端。
不需要特殊的服务器配置，但在发出请求之前要验证客户端的采样支持。
了解[客户端采样支持](./mcp-client#sampling-support)。

连接到兼容的客户端后，服务器可以请求语言模型生成：

<Tabs>
  <Tab title="Sync API">
    ```java
    // Create a server
    McpSyncServer server = McpServer.sync(transportProvider)
        .serverInfo("my-server", "1.0.0")
        .build();

    // Define a tool that uses sampling
    var calculatorTool = new McpServerFeatures.SyncToolSpecification(
        new Tool("ai-calculator", "Performs calculations using AI", schema),
        (exchange, arguments) -> {
            // Check if client supports sampling
            if (exchange.getClientCapabilities().sampling() == null) {
                return new CallToolResult("Client does not support AI capabilities", false);
            }
            
            // Create a sampling request
            McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
                .messages(List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER,
                    new McpSchema.TextContent("Calculate: " + arguments.get("expression")))
                .modelPreferences(McpSchema.ModelPreferences.builder()
                    .hints(List.of(
                        McpSchema.ModelHint.of("claude-3-sonnet"),
                        McpSchema.ModelHint.of("claude")
                    ))
                    .intelligencePriority(0.8)  // Prioritize intelligence
                    .speedPriority(0.5)         // Moderate speed importance
                    .build())
                .systemPrompt("You are a helpful calculator assistant. Provide only the numerical answer.")
                .maxTokens(100)
                .build();
            
            // Request sampling from the client
            McpSchema.CreateMessageResult result = exchange.createMessage(request);
            
            // Process the result
            String answer = result.content().text();
            return new CallToolResult(answer, false);
        }
    );

    // Add the tool to the server
    server.addTool(calculatorTool);
    ```
    
    ```java
    // Create a server
    McpAsyncServer server = McpServer.async(transportProvider)
        .serverInfo("my-server", "1.0.0")
        .build();

    // Define a tool that uses sampling
    var calculatorTool = new McpServerFeatures.AsyncToolSpecification(
        new Tool("ai-calculator", "Performs calculations using AI", schema),
        (exchange, arguments) -> {
            // Check if client supports sampling
            if (exchange.getClientCapabilities().sampling() == null) {
                return Mono.just(new CallToolResult("Client does not support AI capabilities", false));
            }
            
            // Create a sampling request
            McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
                .content(new McpSchema.TextContent("Calculate: " + arguments.get("expression")))
                .modelPreferences(McpSchema.ModelPreferences.builder()
                    .hints(List.of(
                        McpSchema.ModelHint.of("claude-3-sonnet"),
                        McpSchema.ModelHint.of("claude")
                    ))
                    .intelligencePriority(0.8)  // Prioritize intelligence
                    .speedPriority(0.5)         // Moderate speed importance
                    .build())
                .systemPrompt("You are a helpful calculator assistant. Provide only the numerical answer.")
                .maxTokens(100)
                .build();
            
            // Request sampling from the client
            return exchange.createMessage(request)
                .map(result -> {
                    // Process the result
                    String answer = result.content().text();
                    return new CallToolResult(answer, false);
                });
        }
    );

    // Add the tool to the server
    server.addTool(calculatorTool)
        .subscribe();
    ```
    

`CreateMessageRequest` 对象允许你指定：
- `Content` - 模型的输入文本或图像
- `Model Preferences` - 模型选择的提示和优先级
- `System Prompt` - 模型行为的指令
- `Max Tokens` - 生成响应的最大长度

### 日志支持

服务器提供结构化日志功能，允许向客户端发送不同严重级别的日志消息。日志通知只能在现有的客户端会话中发送，比如工具、资源和提示调用。

例如，我们可以从工具处理函数中发送日志消息。在客户端，你可以注册日志消费者来接收服务器的日志消息，并设置最低日志级别来过滤消息。

```java
var mcpClient = McpClient.sync(transport)
        .loggingConsumer(notification -> {
            System.out.println("收到日志消息: " + notification.data());
        })
        .build();

mcpClient.initialize();

mcpClient.setLoggingLevel(McpSchema.LoggingLevel.INFO);

// 调用发送日志通知的工具
CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest("logging-test", Map.of()));
```

服务器可以使用工具/资源/提示处理函数中的 `McpAsyncServerExchange`/`McpSyncServerExchange` 对象发送日志消息：

```java
var tool = new McpServerFeatures.AsyncToolSpecification(
    new McpSchema.Tool("logging-test", "测试日志通知", emptyJsonSchema),
    (exchange, request) -> {  

      exchange.loggingNotification( // 使用exchange发送日志消息
          McpSchema.LoggingMessageNotification.builder()
            .level(McpSchema.LoggingLevel.DEBUG)
            .logger("test-logger")
            .data("调试消息")
            .build())
        .block();

      return Mono.just(new CallToolResult("日志测试完成", false));
    });

var mcpServer = McpServer.async(mcpServerTransportProvider)
  .serverInfo("test-server", "1.0.0")
  .capabilities(
    ServerCapabilities.builder()
      .logging() // 启用日志支持
      .tools(true)
      .build())
  .tools(tool)
  .build();
```

客户端可以通过 `mcpClient.setLoggingLevel(level)` 请求控制他们接收的最低日志级别。低于设置级别的消息将被过滤掉。
支持的日志级别（按严重程度递增排序）：DEBUG (0)、INFO (1)、NOTICE (2)、WARNING (3)、ERROR (4)、CRITICAL (5)、ALERT (6)、EMERGENCY (7)

## 错误处理

SDK通过McpError类提供全面的错误处理，涵盖协议兼容性、传输通信、JSON-RPC消息传递、工具执行、资源管理、提示处理、超时和连接问题。这种统一的错误处理方法确保了同步和异步操作中的一致和可靠的错误管理。