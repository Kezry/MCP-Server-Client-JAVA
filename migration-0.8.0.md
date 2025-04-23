# MCP Java SDK 迁移指南：0.7.0 到 0.8.0

本文档概述了重大变更，并提供了如何将代码从0.7.0版本迁移到0.8.0版本的指导。

0.8.0重构引入了基于会话的服务器端MCP实现架构。
它改进了SDK处理多个并发客户端连接的能力，并提供了更好地与MCP规范保持一致的API。
主要变更包括：

1. 引入基于会话的架构
2. 新的传输提供者抽象
3. 用于客户端交互的Exchange对象
4. 重命名和重组接口
5. 更新处理程序签名

## 重大变更

### 1. 接口重命名

几个接口已被重命名以更好地反映其角色：

| 0.7.0 (旧) | 0.8.0 (新) |
|-------------|-------------|
| `ClientMcpTransport` | `McpClientTransport` |
| `ServerMcpTransport` | `McpServerTransport` |
| `DefaultMcpSession` | `McpClientSession`, `McpServerSession` |

### 2. 新的服务器传输架构

最显著的变化是引入了`McpServerTransportProvider`接口，它取代了创建服务器时直接使用`ServerMcpTransport`。这种新模式分离了以下关注点：

1. **传输提供者**：管理与客户端的连接并为每个连接创建单独的传输
2. **服务器传输**：处理与特定客户端连接的通信

| 0.7.0 (旧) | 0.8.0 (新) |
|-------------|-------------|
| `ServerMcpTransport` | `McpServerTransportProvider` + `McpServerTransport` |
| 直接传输使用 | 基于会话的传输使用 |

#### 之前 (0.7.0)：

```java
// Create a transport
ServerMcpTransport transport = new WebFluxSseServerTransport(objectMapper, "/mcp/message");

// Create a server with the transport
McpServer.sync(transport)
    .serverInfo("my-server", "1.0.0")
    .build();
```

#### 之后 (0.8.0)：

```java
// Create a transport provider
McpServerTransportProvider transportProvider = new WebFluxSseServerTransportProvider(objectMapper, "/mcp/message");

// Create a server with the transport provider
McpServer.sync(transportProvider)
    .serverInfo("my-server", "1.0.0")
    .build();
```

### 3. 处理程序方法签名变更

工具、资源和提示处理程序现在接收一个额外的`exchange`参数，该参数提供对客户端功能的访问和与客户端交互的方法：

| 0.7.0 (旧) | 0.8.0 (新) |
|-------------|-------------|
| `(args) -> result` | `(exchange, args) -> result` |

Exchange对象（`McpAsyncServerExchange`和`McpSyncServerExchange`）为当前会话提供上下文并访问会话特定的操作。

#### 之前 (0.7.0)：

```java
// Tool handler
.tool(calculatorTool, args -> new CallToolResult("Result: " + calculate(args)))

// Resource handler
.resource(fileResource, req -> new ReadResourceResult(readFile(req)))

// Prompt handler
.prompt(analysisPrompt, req -> new GetPromptResult("Analysis prompt"))
```

#### 之后 (0.8.0)：

```java
// Tool handler
.tool(calculatorTool, (exchange, args) -> new CallToolResult("Result: " + calculate(args)))

// Resource handler
.resource(fileResource, (exchange, req) -> new ReadResourceResult(readFile(req)))

// Prompt handler
.prompt(analysisPrompt, (exchange, req) -> new GetPromptResult("Analysis prompt"))
```

### 4. 注册与规范

处理程序的命名约定从"Registration"更改为"Specification"：

| 0.7.0 (旧) | 0.8.0 (新) |
|-------------|-------------|
| `AsyncToolRegistration` | `AsyncToolSpecification` |
| `SyncToolRegistration` | `SyncToolSpecification` |
| `AsyncResourceRegistration` | `AsyncResourceSpecification` |
| `SyncResourceRegistration` | `SyncResourceSpecification` |
| `AsyncPromptRegistration` | `AsyncPromptSpecification` |
| `SyncPromptRegistration` | `SyncPromptSpecification` |

### 5. Roots变更处理程序更新

Roots变更处理程序现在接收一个exchange参数：

#### 之前 (0.7.0)：

```java
.rootsChangeConsumers(List.of(
    roots -> {
        // 处理roots
    }
))
```

#### 之后 (0.8.0)：

```java
.rootsChangeHandlers(List.of(
    (exchange, roots) -> {
        // 使用exchange访问处理roots
    }
))
```

### 6. 服务器创建方法变更

`McpServer`工厂方法现在接受`McpServerTransportProvider`而不是`ServerMcpTransport`：

| 0.7.0 (旧) | 0.8.0 (新) |
|-------------|-------------|
| `McpServer.async(ServerMcpTransport)` | `McpServer.async(McpServerTransportProvider)` |
| `McpServer.sync(ServerMcpTransport)` | `McpServer.sync(McpServerTransportProvider)` |

创建服务器的方法名已更新：

Roots变更处理程序现在接收一个exchange对象：

| 0.7.0 (旧) | 0.8.0 (新) |
|-------------|-------------|
| `rootsChangeConsumers(List<Consumer<List<Root>>>)` | `rootsChangeHandlers(List<BiConsumer<McpSyncServerExchange, List<Root>>>)` |
| `rootsChangeConsumer(Consumer<List<Root>>)` | `rootsChangeHandler(BiConsumer<McpSyncServerExchange, List<Root>>)` |

### 7. 直接服务器方法移至Exchange

以前直接在服务器上可用的几个方法现在通过exchange对象访问：

| 0.7.0 (旧) | 0.8.0 (新) |
|-------------|-------------|
| `server.listRoots()` | `exchange.listRoots()` |
| `server.createMessage()` | `exchange.createMessage()` |
| `server.getClientCapabilities()` | `exchange.getClientCapabilities()` |
| `server.getClientInfo()` | `exchange.getClientInfo()` |

以下直接方法已弃用，将在0.9.0中移除：

- `McpSyncServer.listRoots()`
- `McpSyncServer.getClientCapabilities()`
- `McpSyncServer.getClientInfo()`
- `McpSyncServer.createMessage()`
- `McpAsyncServer.listRoots()`
- `McpAsyncServer.getClientCapabilities()`
- `McpAsyncServer.getClientInfo()`
- `McpAsyncServer.createMessage()`

## 弃用通知

以下组件在0.8.0中已弃用，将在0.9.0中移除：

- `ClientMcpTransport`接口（改用`McpClientTransport`）
- `ServerMcpTransport`接口（改用`McpServerTransport`）
- `DefaultMcpSession`类（改用`McpClientSession`）
- `WebFluxSseServerTransport`类（改用`WebFluxSseServerTransportProvider`）
- `WebMvcSseServerTransport`类（改用`WebMvcSseServerTransportProvider`）
- `StdioServerTransport`类（改用`StdioServerTransportProvider`）
- 所有`*Registration`类（改用相应的`*Specification`类）
- 用于客户端交互的直接服务器方法（改用exchange对象）

## 迁移示例

### 示例1：创建服务器

#### 之前 (0.7.0)：

```java
// 创建传输
ServerMcpTransport transport = new WebFluxSseServerTransport(objectMapper, "/mcp/message");

// 使用传输创建服务器
var server = McpServer.sync(transport)
    .serverInfo("my-server", "1.0.0")
    .tool(calculatorTool, args -> new CallToolResult("Result: " + calculate(args)))
    .rootsChangeConsumers(List.of(
        roots -> System.out.println("Roots changed: " + roots)
    ))
    .build();

// 直接从服务器获取客户端功能
ClientCapabilities capabilities = server.getClientCapabilities();
```

#### 之后 (0.8.0)：

```java
// 创建传输提供者
McpServerTransportProvider transportProvider = new WebFluxSseServerTransportProvider(objectMapper, "/mcp/message");

// 使用传输提供者创建服务器
var server = McpServer.sync(transportProvider)
    .serverInfo("my-server", "1.0.0")
    .tool(calculatorTool, (exchange, args) -> {
        // 从exchange获取客户端功能
        ClientCapabilities capabilities = exchange.getClientCapabilities();
        return new CallToolResult("Result: " + calculate(args));
    })
    .rootsChangeHandlers(List.of(
        (exchange, roots) -> System.out.println("Roots changed: " + roots)
    ))
    .build();
```

### 示例2：实现带有客户端交互的工具

#### 之前 (0.7.0)：

```java
McpServerFeatures.SyncToolRegistration tool = new McpServerFeatures.SyncToolRegistration(
    new Tool("weather", "Get weather information", schema),
    args -> {
        String location = (String) args.get("location");
        // 无法从这里与客户端交互
        return new CallToolResult("Weather for " + location + ": Sunny");
    }
);

var server = McpServer.sync(transport)
    .tools(tool)
    .build();

// 单独调用创建消息
CreateMessageResult result = server.createMessage(new CreateMessageRequest(...));
```

#### 之后 (0.8.0)：

```java
McpServerFeatures.SyncToolSpecification tool = new McpServerFeatures.SyncToolSpecification(
    new Tool("weather", "Get weather information", schema),
    (exchange, args) -> {
        String location = (String) args.get("location");
        
        // 可以直接从工具处理程序与客户端交互
        CreateMessageResult result = exchange.createMessage(new CreateMessageRequest(...));
        
        return new CallToolResult("Weather for " + location + ": " + result.content());
    }
);

var server = McpServer.sync(transportProvider)
    .tools(tool)
    .build();
```

### 示例3：转换现有注册类

如果您有自定义的注册类实现，可以将它们转换为新的规范类：

#### 之前 (0.7.0)：

```java
McpServerFeatures.AsyncToolRegistration toolReg = new McpServerFeatures.AsyncToolRegistration(
    tool,
    args -> Mono.just(new CallToolResult("Result"))
);

McpServerFeatures.AsyncResourceRegistration resourceReg = new McpServerFeatures.AsyncResourceRegistration(
    resource,
    req -> Mono.just(new ReadResourceResult(List.of()))
);
```

#### 之后 (0.8.0)：

```java
// 选项1：直接创建新的规范
McpServerFeatures.AsyncToolSpecification toolSpec = new McpServerFeatures.AsyncToolSpecification(
    tool,
    (exchange, args) -> Mono.just(new CallToolResult("Result"))
);

// 选项2：从现有注册转换（在过渡期间）
McpServerFeatures.AsyncToolRegistration oldToolReg = /* 现有注册 */;
McpServerFeatures.AsyncToolSpecification toolSpec = oldToolReg.toSpecification();

// 资源的处理方式类似
McpServerFeatures.AsyncResourceSpecification resourceSpec = new McpServerFeatures.AsyncResourceSpecification(
    resource,
    (exchange, req) -> Mono.just(new ReadResourceResult(List.of()))
);
```

## 架构变更

### 基于会话的架构

在0.8.0中，MCP Java SDK引入了基于会话的架构，每个客户端连接都有自己的会话。这允许更好地隔离客户端并更有效地管理资源。

`McpServerTransportProvider`负责为每个会话创建`McpServerTransport`实例，而`McpServerSession`管理与特定客户端的通信。

### Exchange对象

新的exchange对象（`McpAsyncServerExchange`和`McpSyncServerExchange`）提供对客户端特定信息和方法的访问。它们作为第一个参数传递给处理程序函数，允许处理程序与发出请求的特定客户端进行交互。

## 结论

0.8.0版本的变更代表了MCP Java SDK的重要架构改进。虽然这些变更需要一些代码修改，但新的设计为构建MCP应用程序提供了更灵活和可维护的基础。

如需迁移帮助或报告问题，请在GitHub仓库上开启一个issue。
