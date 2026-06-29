# 技术架构

## 消息基础设施

| 组件 | 角色 |
|------|------|
| **Apache Kafka** | 消息总线，承载所有领域事件与 Saga 命令 |
| **Eventuate Tram** | 事务性发件箱实现；保证本地事务与消息发布的原子性 |
| **MySQL** | 业务数据 + 发件箱表（`message`、`received_messages`） |
| **CDC Service** | 监听 MySQL binlog，将发件箱消息推送到 Kafka |

### 事务性发件箱流程
```
业务代码 → 同一本地事务写 { 业务表 + outbox.message }
                          ↓
                    CDC (Debezium/Eventuate)
                          ↓
                       Kafka Topic
                          ↓
                    消费方 Handler
```

---

## 持久化方案

| 服务 | 存储 | 模式 |
|------|------|------|
| Order | MySQL + JPA | 标准 ORM |
| Consumer | MySQL + JPA | 标准 ORM |
| Restaurant | MySQL + JPA | 标准 ORM |
| Kitchen | MySQL + JPA | 标准 ORM |
| Accounting | MySQL + Eventuate Local | **事件溯源** |
| Delivery | MySQL + JPA | 标准 ORM |
| OrderHistory | **AWS DynamoDB** | CQRS 读模型 |

---

## API 层

### ftgo-api-gateway（Spring Cloud Gateway）
- 基于 **WebFlux**（响应式）
- 为每个下游服务维护 `*Proxy`（`WebClient` 封装）
- `OrderHandlers` 聚合多服务数据后返回给前端
- 路由配置通过 `OrderConfiguration` / `ConsumerConfiguration` 注册

### ftgo-api-gateway-graphql
- 提供 GraphQL 端点，与 REST 网关并列
- 通过 DataFetcher 调用各服务 REST API

### gRPC（Order Service）
- `OrderServiceServer` 暴露 gRPC 接口
- `GrpcConfiguration` 注册 Server Bean

---

## 服务间通信汇总

| 模式 | 使用场景 | 框架/工具 |
|------|---------|----------|
| 同步 REST (HTTP) | API Gateway → 各服务 | Spring WebClient |
| 同步 gRPC | 外部客户端 → Order Service | gRPC-Java |
| 异步命令/回复 | Saga 步骤 | Eventuate Tram Messaging |
| 异步事件 Pub/Sub | 跨上下文数据同步 | Eventuate Tram Events + Kafka |

---

## 测试策略

| 层次 | 模块 | 工具 |
|------|------|------|
| 单元测试 | 各服务 | JUnit 5 |
| 契约测试（生产方） | `ftgo-*-contracts` | Spring Cloud Contract |
| 消费方驱动契约 | `ftgo-*-contracts` | Pact |
| 端到端测试 | `ftgo-end-to-end-tests` | Testcontainers + REST Assured |
