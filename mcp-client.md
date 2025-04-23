# MCP 客户端

> 了解如何使用 Model Context Protocol (MCP) 客户端与 MCP 服务器交互

# Model Context Protocol 客户端

MCP 客户端是 Model Context Protocol (MCP) 架构中的关键组件，负责建立和管理与 MCP 服务器的连接。它实现了协议的客户端部分，处理以下内容：

* 协议版本协商以确保与服务器的兼容性
* 功能协商以确定可用特性
* 消息传输和 JSON-RPC 通信
* 工具发现和执行
* 资源访问和管理
* 提示系统交互
* 可选功能，如根目录管理和采样支持

<Tip>
  核心 `io.modelcontextprotocol.sdk:mcp` 模块提供 STDIO 和 SSE 客户端传输实现，无需外部 Web 框架。

  Spring 特定的传输实现作为 **可选** 依赖 `io.modelcontextprotocol.sdk:mcp-spring-webflux` 提供给 [Spring Framework](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html) 用户。
</Tip>

客户端提供同步和异步 API，以适应不同应用场景的灵活性需求。

<Tabs>
  <Tab title="同步 API">
    ```java
    // 创建带有自定义配置的同步客户端
    McpSyncClient client = McpClient.sync(transport)
        .requestTimeout(Duration.ofSeconds(10))
        .capabilities(ClientCapabilities.builder()
            .roots(true)      // 启用根目录功能
            .sampling()       // 启用采样功能
            .build())
        .sampling(request -> new CreateMessageResult(response))
        .build();

    // 初始化连接
    client.initialize();

    // 列出可用工具
    ListToolsResult tools = client.listTools();

    // 调用工具
    CallToolResult result = client.callTool(
        new CallToolRequest("calculator", 
            Map.of("operation", "add", "a", 2, "b", 3))
    );

    // 列出和读取资源
    ListResourcesResult resources = client.listResources();
    ReadResourceResult resource = client.readResource(
        new ReadResourceRequest("resource://uri")
    );

    // 列出和使用提示
    ListPromptsResult prompts = client.listPrompts();
    GetPromptResult prompt = client.getPrompt(
        new GetPromptRequest("greeting", Map.of("name", "Spring"))
    );

    // 添加/删除根目录
    client.addRoot(new Root("file:///path", "description"));
    client.removeRoot("file:///path");

    // 关闭客户端
    client.closeGracefully();
    ```
  </Tab>

  <Tab title="异步 API">
    ```java
    // 创建带有自定义配置的异步客户端
    McpAsyncClient client = McpClient.async(transport)
        .requestTimeout(Duration.ofSeconds(10))
        .capabilities(ClientCapabilities.builder()
            .roots(true)      // 启用根目录功能
            .sampling()       // 启用采样功能
            .build())
        .sampling(request -> Mono.just(new CreateMessageResult(response)))
        .toolsChangeConsumer(tools -> Mono.fromRunnable(() -> {
            logger.info("工具已更新: {}", tools);
        }))
        .resourcesChangeConsumer(resources -> Mono.fromRunnable(() -> {
            logger.info("资源已更新: {}", resources);
        }))
        .promptsChangeConsumer(prompts -> Mono.fromRunnable(() -> {
            logger.info("提示已更新: {}", prompts);
        }))
        .build();

    // 初始化连接并使用功能
    client.initialize()
        .flatMap(initResult -> client.listTools())
        .flatMap(tools -> {
            return client.callTool(new CallToolRequest(
                "calculator", 
                Map.of("operation", "add", "a", 2, "b", 3)
            ));
        })
        .flatMap(result -> {
            return client.listResources()
                .flatMap(resources -> 
                    client.readResource(new ReadResourceRequest("resource://uri"))
                );
        })
        .flatMap(resource -> {
            return client.listPrompts()
                .flatMap(prompts ->
                    client.getPrompt(new GetPromptRequest(
                        "greeting", 
                        Map.of("name", "Spring")
                    ))
                );
        })
        .flatMap(prompt -> {
            return client.addRoot(new Root("file:///path", "description"))
                .then(client.removeRoot("file:///path"));            
        })
        .doFinally(signalType -> {
            client.closeGracefully().subscribe();
        })
        .subscribe();
    ```
  </Tab>
</Tabs>

## 客户端传输

传输层处理 MCP 客户端和服务器之间的通信，为不同的使用场景提供不同的实现。客户端传输管理消息序列化、连接建立和特定协议的通信模式。

<Tabs>
  <Tab title="STDIO">
    创建基于进程内通信的传输

    ```java
    ServerParameters params = ServerParameters.builder("npx")
        .args("-y", "@modelcontextprotocol/server-everything", "dir")
        .build();
    McpTransport transport = new StdioClientTransport(params);
    ```
  </Tab>

  <Tab title="SSE (HttpClient)">
    创建框架无关（纯 Java API）的 SSE 客户端传输。包含在核心 mcp 模块中。

    ```java
    McpTransport transport = new HttpClientSseClientTransport("http://your-mcp-server");
    ```
  </Tab>

  <Tab title="SSE (WebFlux)">
    创建基于 WebFlux 的 SSE 客户端传输。需要 mcp-webflux-sse-transport 依赖。

    ```java
    WebClient.Builder webClientBuilder = WebClient.builder()
        .baseUrl("http://your-mcp-server");
    McpTransport transport = new WebFluxSseClientTransport(webClientBuilder);
    ```
  </Tab>
</Tabs>

## 客户端功能

客户端可以配置各种功能：

```java
var capabilities = ClientCapabilities.builder()
    .roots(true)      // 启用文件系统根目录支持，带有列表变更通知
    .sampling()       // 启用LLM采样支持
    .build();
```

### 根目录支持

根目录定义了服务器可以在文件系统中操作的边界：

```java
// 动态添加根目录
client.addRoot(new Root("file:///path", "description"));

// 移除根目录
client.removeRoot("file:///path");

// 通知服务器根目录变更
client.rootsListChangedNotification();
```

根目录功能允许服务器：

* 请求可访问的文件系统根目录列表
* 在根目录列表变更时接收通知
* 了解它们可以访问哪些目录和文件

### 采样支持

采样使服务器能够通过客户端请求LLM交互（"完成"或"生成"）：

```java
// 配置采样处理程序
Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
    // 采样实现，与LLM交互
    return new CreateMessageResult(response);
};

// 创建支持采样的客户端
var client = McpClient.sync(transport)
    .capabilities(ClientCapabilities.builder()
        .sampling()
        .build())
    .sampling(samplingHandler)
    .build();
```

此功能允许：

* 服务器无需API密钥即可利用AI功能
* 客户端保持对模型访问和权限的控制
* 支持基于文本和图像的交互
* 可选择在提示中包含MCP服务器上下文

### 日志支持

客户端可以注册日志消费者来接收服务器的日志消息，并设置最低日志级别来过滤消息：

```java
var mcpClient = McpClient.sync(transport)
        .loggingConsumer(notification -> {
            System.out.println("收到日志消息: " + notification.data());
        })
        .build();

mcpClient.initialize();

mcpClient.setLoggingLevel(McpSchema.LoggingLevel.INFO);

// 调用可以发送日志通知的工具
CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest("logging-test", Map.of()));
```

客户端可以通过 `mcpClient.setLoggingLevel(level)` 请求控制他们接收的最低日志级别。低于设置级别的消息将被过滤掉。
支持的日志级别（按严重程度递增排序）：DEBUG (0)、INFO (1)、NOTICE (2)、WARNING (3)、ERROR (4)、CRITICAL (5)、ALERT (6)、EMERGENCY (7)

## 使用MCP客户端

### 工具执行

工具是客户端可以发现和执行的服务器端函数。MCP客户端提供了列出可用工具并使用特定参数执行它们的方法。每个工具都有一个唯一的名称并接受参数映射。

    ```java
    // List available tools and their names
    var tools = client.listTools();
    tools.forEach(tool -> System.out.println(tool.getName()));

    // Execute a tool with parameters
    var result = client.callTool("calculator", Map.of(
        "operation", "add",
        "a", 1,
        "b", 2
    ));
    ```


    ```java
    // List available tools asynchronously
    client.listTools()
        .doOnNext(tools -> tools.forEach(tool -> 
            System.out.println(tool.getName())))
        .subscribe();

    // Execute a tool asynchronously
    client.callTool("calculator", Map.of(
            "operation", "add",
            "a", 1,
            "b", 2
        ))
        .subscribe();
    ```



### 资源访问

资源表示客户端可以使用URI模板访问的服务器端数据源。MCP客户端提供了通过标准化接口发现可用资源并检索其内容的方法。


    ```java
    // 列出可用资源及其名称
    var resources = client.listResources();
    resources.forEach(resource -> System.out.println(resource.getName()));

    // 使用URI模板检索资源内容
    var content = client.getResource("file", Map.of(
        "path", "/path/to/file.txt"
    ));
    ```
    
    
    ```java
    // 异步列出可用资源
    client.listResources()
        .doOnNext(resources -> resources.forEach(resource -> 
            System.out.println(resource.getName())))
        .subscribe();

    // 异步检索资源内容
    client.getResource("file", Map.of(
            "path", "/path/to/file.txt"
        ))
        .subscribe();
    ```
    

### 提示系统

提示系统支持与服务器端提示模板的交互。这些模板可以被发现并使用自定义参数执行，允许基于预定义模式的动态文本生成。


    ```java
    // List available prompt templates
    var prompts = client.listPrompts();
    prompts.forEach(prompt -> System.out.println(prompt.getName()));

    // Execute a prompt template with parameters
    var response = client.executePrompt("echo", Map.of(
        "text", "Hello, World!"
    ));
    ```
    
    ```java
    // List available prompt templates asynchronously
    client.listPrompts()
        .doOnNext(prompts -> prompts.forEach(prompt -> 
            System.out.println(prompt.getName())))
        .subscribe();

    // Execute a prompt template asynchronously
    client.executePrompt("echo", Map.of(
            "text", "Hello, World!"
        ))
        .subscribe();
    ```
    

### 使用完成功能

作为[完成功能](/specification/2025-03-26/server/utilities/completion)的一部分，MCP为服务器提供了一种标准化的方式来为提示和资源URI提供参数自动完成建议。

查看[服务器完成功能](/sdk/java/mcp-server#completion-specification)了解如何在服务器端启用和配置完成功能。

在客户端，MCP客户端提供了请求自动完成的方法：


    ```java
    CompleteRequest request = new CompleteRequest(
            new PromptReference("code_review"),
            new CompleteRequest.CompleteArgument("language", "py"));

    CompleteResult result = syncMcpClient.completeCompletion(request);
    ```
    
    ```java
    CompleteRequest request = new CompleteRequest(
            new PromptReference("code_review"),
            new CompleteRequest.CompleteArgument("language", "py"));

    Mono<CompleteResult> result = mcpClient.completeCompletion(request);
    ```
    