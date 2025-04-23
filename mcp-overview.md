# 概述

> Model Context Protocol (MCP) Java SDK 简介

[Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/architecture) 的 Java SDK
使 AI 模型和工具之间能够进行标准化集成。

<Note>
  ### 0.8.x 版本重大变更 ⚠️

  **注意:** 0.8.x 版本引入了多项重大变更，包括新的基于会话的架构。
  如果您正在从 0.7.0 升级，请参考[迁移指南](https://github.com/modelcontextprotocol/java-sdk/blob/main/migration-0.8.0.md)获取详细说明。
</Note>

## 功能特性

* MCP 客户端和服务器端实现支持:
  * 协议[版本兼容性协商](/specification/2024-11-05/basic/lifecycle/#initialization)
  * [工具](/specification/2024-11-05/server/tools/)发现、执行、列表变更通知
  * 基于 URI 模板的[资源](/specification/2024-11-05/server/resources/)管理
  * [根目录](/specification/2024-11-05/client/roots/)列表管理和通知
  * [提示](/specification/2024-11-05/server/prompts/)处理和管理
  * AI 模型交互的[采样](/specification/2024-11-05/client/sampling/)支持
* 多种传输实现:
  * 默认传输(包含在核心 `mcp` 模块中，无需外部 Web 框架):
    * 基于 Stdio 的进程间通信传输
    * 基于 Java HttpClient 的 SSE 客户端传输，用于 HTTP SSE 客户端流
    * 基于 Servlet 的 SSE 服务器传输，用于 HTTP SSE 服务器流
  * 可选的 Spring 传输(如果使用 Spring Framework 则更方便):
    * WebFlux SSE 客户端和服务器传输，用于响应式 HTTP 流
    * WebMVC SSE 传输，用于基于 servlet 的 HTTP 流
* 支持同步和异步编程范式

<Tip>
  核心 `io.modelcontextprotocol.sdk:mcp` 模块提供默认的 STDIO 和 SSE 客户端及服务器传输实现，无需外部 Web 框架。

  Spring 特定的传输实现作为可选依赖提供，方便[Spring Framework](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)用户使用。
</Tip>

## 架构

SDK 采用分层架构，职责划分清晰:

![MCP Stack Architecture](https://mintlify.s3.us-west-1.amazonaws.com/mcp/images/java/mcp-stack.svg)

* **客户端/服务器层(McpClient/McpServer)**: 都使用 McpSession 进行同步/异步操作，
  McpClient 处理客户端协议操作，McpServer 管理服务器端协议操作。
* **会话层(McpSession)**: 使用 DefaultMcpSession 实现管理通信模式和状态。
* **传输层(McpTransport)**: 通过以下方式处理 JSON-RPC 消息序列化/反序列化:
  * 核心模块中的 StdioTransport(stdin/stdout)
  * 专用传输模块中的 HTTP SSE 传输(Java HttpClient、Spring WebFlux、Spring WebMVC)

MCP 客户端是 Model Context Protocol (MCP) 架构中的关键组件，负责建立和管理与 MCP 服务器的连接。
它实现了协议的客户端部分。

![Java MCP Client Architecture](https://mintlify.s3.us-west-1.amazonaws.com/mcp/images/java/java-mcp-client-architecture.jpg)

MCP 服务器是 Model Context Protocol (MCP) 架构中的基础组件，为客户端提供工具、资源和功能。
它实现了协议的服务器端部分。

![Java MCP Server Architecture](https://mintlify.s3.us-west-1.amazonaws.com/mcp/images/java/java-mcp-server-architecture.jpg)

关键交互:

* **客户端/服务器初始化**: 传输设置、协议兼容性检查、功能协商和实现细节交换。
* **消息流**: JSON-RPC 消息处理，包括验证、类型安全的响应处理和错误处理。
* **资源管理**: 资源发现、基于 URI 模板的访问、订阅系统和内容检索。

## 依赖

将以下 Maven 依赖添加到您的项目中:

<Tabs>
  <Tab title="Maven">
    核心 MCP 功能:

    ```xml
    <dependency>
        <groupId>io.modelcontextprotocol.sdk</groupId>
        <artifactId>mcp</artifactId>
    </dependency>
    ```

    核心 `mcp` 模块已包含默认的 STDIO 和 SSE 传输实现，无需外部 Web 框架。

    如果您使用 Spring Framework 并想使用 Spring 特定的传输实现，添加以下可选依赖之一:

    ```xml
    <!-- 可选: 基于 Spring WebFlux 的 SSE 客户端和服务器传输 -->
    <dependency>
        <groupId>io.modelcontextprotocol.sdk</groupId>
        <artifactId>mcp-spring-webflux</artifactId>
    </dependency>

    <!-- 可选: 基于 Spring WebMVC 的 SSE 服务器传输 -->
    <dependency>
        <groupId>io.modelcontextprotocol.sdk</groupId>
        <artifactId>mcp-spring-webmvc</artifactId>
    </dependency>
    ```
  </Tab>

  <Tab title="Gradle">
    核心 MCP 功能:

    ```groovy
    dependencies {
      implementation platform("io.modelcontextprotocol.sdk:mcp")
      //...
    }
    ```

    核心 `mcp` 模块已包含默认的 STDIO 和 SSE 传输实现，无需外部 Web 框架。

    如果您使用 Spring Framework 并想使用 Spring 特定的传输实现，添加以下可选依赖之一:

    ```groovy
    // 可选: 基于 Spring WebFlux 的 SSE 客户端和服务器传输
    dependencies {
      implementation platform("io.modelcontextprotocol.sdk:mcp-spring-webflux")
    }

    // 可选: 基于 Spring WebMVC 的 SSE 服务器传输
    dependencies {
      implementation platform("io.modelcontextprotocol.sdk:mcp-spring-webmvc")
    }
    ```
  </Tab>
</Tabs>

### 物料清单(BOM)

物料清单(BOM)声明了特定发布版本推荐使用的所有依赖版本。
在应用程序的构建脚本中使用 BOM 可以避免您自己指定和维护依赖版本。
相反，您使用的 BOM 版本决定了使用的依赖版本。
它还确保您默认使用受支持和经过测试的依赖版本，除非您选择覆盖它们。

将 BOM 添加到您的项目中:

<Tabs>
  <Tab title="Maven">
    ```xml
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.modelcontextprotocol.sdk</groupId>
                <artifactId>mcp-bom</artifactId>
                <version>0.9.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    ```
  </Tab>

  <Tab title="Gradle">
    ```groovy
    dependencies {
      implementation platform("io.modelcontextprotocol.sdk:mcp-bom:0.9.0")
      //...
    }
    ```

    Gradle 用户也可以通过利用 Gradle (5.0+) 原生支持使用 Maven BOM 声明依赖约束来使用 Spring AI MCP BOM。
    这是通过在 Gradle 构建脚本的依赖部分添加 'platform' 依赖处理程序方法来实现的。
    如上面的代码片段所示，然后可以跟随无版本声明的 Starter Dependencies，用于您希望使用的一个或多个 spring-ai 模块，例如 spring-ai-openai。
  </Tab>
</Tabs>

将版本号替换为您想要使用的 BOM 版本。

### 可用依赖

BOM 管理的可用依赖如下:

* 核心依赖
  * `io.modelcontextprotocol.sdk:mcp` - 核心 MCP 库，提供 Model Context Protocol 实现的基本功能和 API，包括默认的 STDIO 和 SSE 客户端及服务器传输实现。无需外部 Web 框架。
* 可选传输依赖(如果使用 Spring Framework 则更方便)
  * `io.modelcontextprotocol.sdk:mcp-spring-webflux` - 基于 WebFlux 的服务器发送事件(SSE)传输实现，用于响应式应用程序。
  * `io.modelcontextprotocol.sdk:mcp-spring-webmvc` - 基于 WebMVC 的服务器发送事件(SSE)传输实现，用于基于 servlet 的应用程序。
* 测试依赖
  * `io.modelcontextprotocol.sdk:mcp-test` - MCP 应用程序的测试工具和支持。