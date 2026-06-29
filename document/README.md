# FTGO Application — DDD 文档索引

> 本文档基于 [Chris Richardson《微服务架构设计模式》](https://microservices.io/book) 示例应用静态分析生成。

## 文档目录

| 文件 | 内容 |
|------|------|
| [bounded-contexts.md](bounded-contexts.md) | 限界上下文划分与上下文映射 |
| [domain-model.md](domain-model.md) | 各服务聚合、实体、值对象、领域事件 |
| [sagas.md](sagas.md) | 三条编排式 Saga 的步骤与补偿逻辑 |
| [events.md](events.md) | 领域事件目录与跨服务事件流 |
| [architecture.md](architecture.md) | 技术架构：消息、持久化、API 网关 |

## 系统一句话描述

FTGO 是一个食品外卖平台，由 7 个微服务组成，通过 **Eventuate Tram**（事务性发件箱 + Kafka）实现异步消息传递，通过**编排式 Saga** 维护跨服务数据一致性。
