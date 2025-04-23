# MCP Java SDK
[![Build Status](https://github.com/modelcontextprotocol/java-sdk/actions/workflows/publish-snapshot.yml/badge.svg)](https://github.com/modelcontextprotocol/java-sdk/actions/workflows/publish-snapshot.yml)

这是一组为[Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/architecture)提供Java SDK集成的项目。
该SDK使Java应用程序能够通过标准化接口与AI模型和工具进行交互，支持同步和异步通信模式。

## 📚 参考文档

#### MCP Java SDK 文档
有关完整指南和SDK API文档，请访问[MCP Java SDK参考文档](https://modelcontextprotocol.io/sdk/java/mcp-overview)。

#### Spring AI MCP 文档
[Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)通过Spring Boot集成扩展了MCP Java SDK，提供了[客户端](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)和[服务器端](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)启动器。使用[Spring Initializer](https://start.spring.io)引导您的AI应用程序以支持MCP。

## 开发

### 从源代码构建

```bash
./mvnw clean install -DskipTests
```

### 运行测试

运行测试需要预先安装`Docker`和`npx`。

```bash
./mvnw test
```

## 贡献

欢迎贡献！请：

1. Fork本仓库
2. 创建功能分支
3. 提交Pull Request

## 团队

- Christian Tzolov
- Dariusz Jędrzejczyk

## 链接

- [GitHub仓库](https://github.com/modelcontextprotocol/java-sdk)
- [问题追踪](https://github.com/modelcontextprotocol/java-sdk/issues)
- [CI/CD](https://github.com/modelcontextprotocol/java-sdk/actions)

## 许可证

本项目采用[MIT许可证](LICENSE)授权。
