# 工作记录 — Session 02：数据一致性 TODO 实施

> 日期：2026-06-30  
> 分支：jdk-21-version  
> 操作人：FJJ  
> 关联文档：[data-consistency-todos.md](data-consistency-todos.md)

---

## 已完成事项

### IC-05 / DEV-05 — KitchenService 补充 `@Transactional`

**文件**: `ftgo-kitchen-service/src/main/java/.../kitchenservice/domain/KitchenService.java`

为以下 7 个方法补充了 `@Transactional` 注解，确保"读取-修改-发布事件"在同一事务内完成，避免数据库持久化和 Outbox 写入部分成功：

| 方法 | 原状态 |
|------|--------|
| `createTicket()` | ❌ 无注解 |
| `confirmCreateTicket()` | ❌ 无注解 |
| `cancelCreateTicket()` | ❌ 无注解 |
| `cancelTicket()` | ❌ 无注解 |
| `confirmCancelTicket()` | ❌ 无注解 |
| `undoCancel()` | ❌ 无注解 |
| `beginReviseOrder()` | ❌ 无注解 |
| `undoBeginReviseOrder()` | ❌ 无注解 |
| `confirmReviseTicket()` | ❌ 无注解 |
| `accept()` | ✅ 已有（保持） |

**风险影响**：无 @Transactional 时，如果 `domainEventPublisher.publish()` 失败，数据库中的状态已变更但 Outbox 消息未写入，导致下游服务无法感知状态变化，进而引发数据漂移。

---

### IC-01 / DEV-03 — Order 中间状态补充 Domain Events

#### 新增 API 事件类（`ftgo-order-service-api` 模块）

| 文件 | 说明 |
|------|------|
| `OrderCancelPending.java` | record，APPROVED → CANCEL_PENDING 时发布 |
| `OrderCancelUndone.java` | record，CANCEL_PENDING → APPROVED 时发布（补偿） |
| `OrderRevisionProposed.java` | 携带 currentTotal/newTotal/newDeliveryAddress，APPROVED → REVISION_PENDING |
| `OrderRevised.java` | 携带 currentTotal/newTotal/newDeliveryAddress，REVISION_PENDING → APPROVED |

> **设计决策**：`OrderRevisionProposed` 和 `OrderRevised` 原来是 domain 内部类（含 `OrderRevision` 对象，其中包含有 JPA 注解的 `DeliveryInformation`）。移入 API 模块时，改为轻量的 POJO，只携带下游服务实际需要的数据（Money 金额 + Address）。旧 domain 类已删除。

#### 更新 `Order.java`

| 方法 | 修改前 | 修改后 |
|------|--------|--------|
| `cancel()` | `yield emptyList()` | `yield singletonList(new OrderCancelPending())` |
| `undoPendingCancel()` | `yield emptyList()` | `yield singletonList(new OrderCancelUndone())` |
| `revise()` | 发布 domain `OrderRevisionProposed(OrderRevision, ...)` | 发布 API `OrderRevisionProposed(currentTotal, newTotal, addr)` |
| `confirmRevision()` | 发布 domain `OrderRevised(OrderRevision, ...)` | 发布 API `OrderRevised(currentTotal, newTotal, addr)` |

同步更新了 `OrderStateTransitionTest.kt` 中的断言（原断言 `emptyList()`，现为新事件）。

---

### IC-02 / DEV-04 — 补全 `OrderHistoryEventHandlers`

**文件**: `ftgo-order-history-service/.../messaging/OrderHistoryEventHandlers.java`

新增 5 个事件处理器：

| 事件 | 处理动作 | 幂等保障 |
|------|----------|----------|
| `OrderCancelPending` | `updateOrderState(CANCEL_PENDING)` | DynamoDB 条件写入 |
| `OrderCancelUndone` | `updateOrderState(APPROVED)` | DynamoDB 条件写入 |
| `OrderRevisionProposed` | `updateOrderState(REVISION_PENDING)` | DynamoDB 条件写入 |
| `OrderRevised` | `updateOrderState(APPROVED)` | DynamoDB 条件写入 |
| `DeliveryPickedUp` | `notePickedUp(orderId)` | DynamoDB 条件写入 |

`DeliveryPickedUp` 从注释中恢复，OrderHistory 现在可以追踪取餐完成状态。

---

### IC-03 / DEV-06 — DeliveryService 订阅 `OrderRevised`

**修改文件**：
- `DeliveryMessageHandlers.java` — 新增 `OrderRevised` 订阅 + `handleOrderRevisedEvent()`
- `DeliveryService.java` — 新增 `updateDeliveryAddress(orderId, addr)` 方法（`@Transactional`）
- `Delivery.java` — 新增 `setDeliveryAddress()` setter

**效果**：当 ReviseOrderSaga 完成且修订包含新配送地址时，DeliveryService 会自动更新 Delivery 记录中的配送目标地址。

---

### IC-04 — AccountingService `ReverseAuthorization` 补充事件

**修改文件**：
- 新增 `AccountAuthorizationReversedEvent.java`（携带 consumerId、orderId）
- `Account.java`：`process(ReverseAuthorizationCommandInternal)` 改为返回该事件（原返回 `emptyList()`）

**当前状态**：事件已发布到 Eventuate 事件日志，暂无下游消费者。未来的审计/欺诈检测服务可订阅该事件，无需修改 AccountingService 代码。

---

### TEST-03 — Saga 补偿路径测试（Kotlin）

**新文件**: `ftgo-order-service/src/test/kotlin/.../sagas/createorder/CreateOrderSagaCompensationTest.kt`

覆盖 CreateOrderSaga 的 3 条补偿路径：

| 测试 | 失败步骤 | 验证补偿链 |
|------|----------|-----------|
| `compensation - consumer validation failure rejects order` | step 1（ConsumerService）| → `RejectOrderCommand` |
| `compensation - kitchen create ticket failure rejects order` | step 2（KitchenService 创建）| → `RejectOrderCommand` |
| `compensation - accounting authorization failure cancels ticket then rejects order` | step 3（AccountingService）| → `CancelCreateTicket` → `RejectOrderCommand` |

> CancelOrderSaga 和 ReviseOrderSaga 的补偿路径已由现有 `shouldRollbackWhen...` 测试覆盖。

---

## 验证结果

```
:ftgo-kitchen-service:test BUILD SUCCESSFUL  (含 TicketAcceptStateTest 8个用例)
:ftgo-order-service:test   BUILD SUCCESSFUL  (含 CreateOrderSagaCompensationTest + OrderStateTransitionTest 共 31 个用例)
:ftgo-accounting-service:compileJava BUILD SUCCESSFUL
:ftgo-delivery-service:compileJava   BUILD SUCCESSFUL
:ftgo-order-history-service:compileJava BUILD SUCCESSFUL
```

---

## 遗留/待处理

| ID | 内容 | 优先级 | 说明 |
|----|------|--------|------|
| CC-01 | 并发 Saga 竞争防护 | P2 | 需在 OrderService.cancel/revise 层加分布式锁或业务层冲突检测 |
| CC-02/03 | 业务层幂等性 | P2 | OrderService/KitchenService CommandHandlers 仅依赖框架 received_messages |
| TEST-04 | 幂等性测试 | P1 | 需要 DynamoDB Local 或 Mock |
| TEST-05 | 并发 Saga 测试 | P2 | 需集成测试环境 |
| OPS-01 | Saga 卡死告警 | P0 | 已有 SQL，需接入监控系统 |
| OPS-02/03 | CDC/Kafka 监控 | P1 | 已有 Prometheus 指标名，需接入 Grafana |
| OPS-04/05/06 | DLQ/扫描/Runbook | P2 | 已有代码，需部署接入 |
