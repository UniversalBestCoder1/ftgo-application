# 单元测试工作报告

## 第一部分：原版单元测试

| 模块 | 测试类 | 方法数 | 覆盖层次 |
|------|--------|--------|---------|
| ftgo-common | `MoneyTest` | 4 | 值对象 |
| ftgo-common | `MoneySerializationTest` | 3 | 序列化 |
| ftgo-consumer-service | `ValidateOrderByConsumerTest` | 1 | API 反序列化 |
| ftgo-consumer-service | `ConsumerServiceInMemoryIntegrationTest` | 1 | 服务集成 |
| ftgo-consumer-service | `ConsumerControllerTest` | 1 | Web 层 |
| ftgo-accounting-service | `AccountingServiceCommandHandlerTest` | 1 | 消息处理 |
| ftgo-kitchen-service | `KitchenServiceInMemoryIntegrationTest` | 1 | 服务集成 |
| ftgo-kitchen-service | `TicketDomainEventPublisherTest` | 1 | 事件通道 |
| ftgo-order-service | `OrderTest` | 4 | Order 聚合根 |
| ftgo-order-service | `OrderServiceTest` | 1 | 应用服务（仅 createOrder） |
| ftgo-order-service | `OrderDomainEventPublisherTest` | 1 | 事件通道 |
| ftgo-order-service | `OrderEventConsumerTest` | 1 | 事件消费 |
| ftgo-order-service | `CreateOrderSagaTest` | 3 | Saga |
| ftgo-order-service | `OrderControllerTest` | 2 | Web 层 |
| ftgo-restaurant-service | `RestaurantDomainEventPublisherTest` | 1 | 事件通道 |
| ftgo-restaurant-service | `RestaurantCreatedSerializationTest` | 1 | 序列化 |
| ftgo-delivery-service | `DeliveryServiceTest` | 3 | 应用服务 |
| ftgo-order-history-service | `OrderHistoryControllerTest` | 1 | Web 层 |
| ftgo-order-history-service | `OrderHistoryEventHandlersTest` | 1 | 事件处理 |
| ftgo-api-gateway | `ApiGatewayIntegrationTest` | 2 | 网关集成 |
| ftgo-api-gateway | `OrderServiceProxyIntegrationTest` | 2 | 代理契约 |
| **合计** | **22 个测试类** | **36 个方法** | |

---

## 第二部分：新增 Kotlin 单元测试

### 第一批（本次前）：聚合根 + Saga 补全

| 文件 | 模块 | 测试方法 | 覆盖重点 |
|------|------|---------|---------|
| `CancelOrderSagaTest.kt` | ftgo-order-service | 2 | CancelOrderSaga 正常 + 回滚 |
| `ReviseOrderSagaTest.kt` | ftgo-order-service | 2 | ReviseOrderSaga 正常 + 回滚 |
| `OrderStateTransitionTest.kt` | ftgo-order-service | 5 | Order 状态机（reject/cancel/undo） |
| `TicketAggregateTest.kt` | ftgo-kitchen-service | 8 | Ticket 聚合根全状态流转 |

### 第二批（本次）：服务层覆盖率提升

| 文件 | 模块 | 测试方法 | 覆盖函数 |
|------|------|---------|---------|
| `OrderServiceExtTest.kt` | ftgo-order-service | 7 | `cancel` `approveOrder` `rejectOrder` `beginCancel` `confirmCancelled` `reviseOrder` + 异常路径 |
| `KitchenServiceTest.kt` | ftgo-kitchen-service | 8 | `accept` `confirmCreateTicket` `cancelTicket` `confirmCancelTicket` `undoCancel` `beginReviseOrder` `confirmReviseTicket` + 异常路径 |
| `RestaurantServiceTest.kt` | ftgo-restaurant-service | 3 | `create` `findById` + 空结果路径 |

**本次新增合计：18 个方法 / 3 个文件**

---

### 测试结果持久化（JaCoCo）

在以下3个模块的 `build.gradle` 中添加：

```groovy
apply plugin: 'jacoco'

jacocoTestReport {
    reports { xml.required = true; html.required = true }
}
test.finalizedBy jacocoTestReport
```

每次执行 `./gradlew :ftgo-order-service:test` 后，报告自动生成至：

| 产物 | 路径 |
|------|------|
| JUnit XML 结果 | `build/test-results/test/` |
| JUnit HTML 报告 | `build/reports/tests/test/` |
| JaCoCo XML 覆盖率 | `build/reports/jacoco/test/jacocoTestReport.xml` |
| JaCoCo HTML 覆盖率 | `build/reports/jacoco/test/html/index.html` |

适用模块：`ftgo-order-service`、`ftgo-kitchen-service`、`ftgo-restaurant-service`

---

### 生产代码修复

`Ticket.java`：补充缺失的 `getState()` getter（原代码遗漏，导致测试无法访问工单状态）：
```java
public TicketState getState() { return state; }
```

---

### 全量新增测试汇总

| 批次 | Kotlin 测试类 | 方法总数 |
|------|-------------|---------|
| 第一批（聚合根 + Saga） | 4 | 17 |
| 第二批（服务层） | 3 | 18 |
| **合计** | **7** | **35** |
