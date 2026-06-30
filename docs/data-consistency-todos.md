# FTGO 数据一致性问题 TODO 清单

> 分析日期：2026-06-30  
> 分支：jdk-21-version  
> 负责人：FJJ

---

## 一、发现的问题汇总

### 🔴 严重 Bug（会导致运行时崩溃）

| ID | 位置 | 问题描述 | 影响 |
|----|------|----------|------|
| BUG-01 | `Ticket.java:75-88` | ~~`accept()` 缺少 `this.state = TicketState.ACCEPTED`~~ | ✅ **已修复** — 补充 `this.state = TicketState.ACCEPTED` |
| BUG-02 | `Ticket.java:71` | ~~`cancelCreate()` 抛 `NotYetImplementedException`~~ | ✅ **已修复** — 实现 `CANCELLED` 状态转换 |

### 🟠 数据一致性问题（静默数据漂移）

| ID | 位置 | 问题描述 |
|----|------|----------|
| IC-01 | `Order.java:87-98,141-145` | `cancel()`/`undoPendingCancel()`/`rejectRevision()` 不发布 Domain Events，CANCEL_PENDING/REVISION_PENDING 中间状态对下游不可见 |
| IC-02 | `OrderHistoryEventHandlers.java:38-41` | 未订阅 `OrderRevisionProposed`、`OrderRevised`；`DeliveryPickedUp` 被注释掉 |
| IC-03 | `DeliveryMessageHandlers.java` | 不订阅 `OrderRevised`，修订后配送地址/明细不更新 |
| IC-04 | `Account.java:process(ReverseAuthorizationCommandInternal)` | 返回 `emptyList()`，退款无事件通知 |
| IC-05 | `KitchenService.java` | `cancel/confirmCancel/beginReviseOrder/confirmReviseTicket/undoBeginReviseOrder` 缺少 `@Transactional` |
| IC-06 | `Ticket.java:previousState` | BUG-01 存在时，`cancel()` 保存的 `previousState` 是错误的 `AWAITING_ACCEPTANCE` 而非 `ACCEPTED`，`undoCancel()` 会回滚到错误状态 |

### 🟡 并发与幂等性（低频但高影响）

| ID | 问题描述 |
|----|----------|
| ~~CC-01~~ | ~~CancelOrderSaga 与 ReviseOrderSaga 并发时都能读到 `APPROVED` 状态并启动，Order 有 `@Version` 但 Saga 协调层无冲突处理~~ ✅ **已修复** — 急切状态转换 + `@Version` 防护 |
| CC-02 | OrderService/KitchenService/DeliveryService 的 CommandHandlers 仅依赖框架级 `received_messages` 幂等，业务层无额外保障 |
| CC-03 | `ReviseOrderSaga` 的差价授权步骤在消息重放时会被重复扣款（框架幂等保护之外的金额不一致风险） |

---

## 二、开发 TODO

### DEV-01 [P0] 修复 `Ticket.accept()` 状态遗漏

**文件**: `ftgo-kitchen-service/src/main/java/.../kitchenservice/domain/Ticket.java`

```java
// 修复前（第 77 行之后缺少状态转换）
case AWAITING_ACCEPTANCE -> {
    this.acceptTime = LocalDateTime.now();
    // ❌ 缺少: this.state = TicketState.ACCEPTED;
    this.readyBy = readyBy;
    yield singletonList(new TicketAcceptedEvent(readyBy));
}

// 修复后
case AWAITING_ACCEPTANCE -> {
    this.acceptTime = LocalDateTime.now();
    if (!acceptTime.isBefore(readyBy))
        throw new IllegalArgumentException(...);
    this.readyBy = readyBy;
    this.state = TicketState.ACCEPTED;  // ✅ 添加
    yield singletonList(new TicketAcceptedEvent(readyBy));
}
```

---

### DEV-02 [P0] 实现 `Ticket.cancelCreate()`

**文件**: `Ticket.java:70-72`

```java
// 修复前
public List<TicketDomainEvent> cancelCreate() {
    throw new NotYetImplementedException();
}

// 修复后
public List<TicketDomainEvent> cancelCreate() {
    return switch (state) {
        case CREATE_PENDING -> {
            this.state = TicketState.CANCELLED;
            yield singletonList(new TicketCancelled());
        }
        default -> throw new UnsupportedStateTransitionException(state);
    };
}
```

---

### DEV-03 [P1] Order 中间状态补充 Domain Events

**文件**: `Order.java`

```java
// cancel() — APPROVED → CANCEL_PENDING
public List<OrderDomainEvent> cancel() {
    return switch (state) {
        case APPROVED -> {
            this.state = OrderState.CANCEL_PENDING;
            yield singletonList(new OrderCancelPending());  // ✅ 新增事件
        }
        ...
    };
}

// undoPendingCancel() — CANCEL_PENDING → APPROVED
public List<OrderDomainEvent> undoPendingCancel() {
    return switch (state) {
        case CANCEL_PENDING -> {
            this.state = OrderState.APPROVED;
            yield singletonList(new OrderCancelUndone());  // ✅ 新增事件
        }
        ...
    };
}

// rejectRevision() — REVISION_PENDING → APPROVED
public List<OrderDomainEvent> rejectRevision() {
    return switch (state) {
        case REVISION_PENDING -> {
            this.state = OrderState.APPROVED;
            yield singletonList(new OrderRevisionRejected());  // ✅ 新增事件
        }
        ...
    };
}
```

需新增的 API 事件类（放 `ftgo-order-service-api` 模块）：
- `OrderCancelPending`
- `OrderCancelUndone`
- `OrderRevisionRejected`

---

### DEV-04 [P1] 补全 `OrderHistoryEventHandlers` 订阅

**文件**: `ftgo-order-history-service/.../messaging/OrderHistoryEventHandlers.java`

补充订阅：
- `OrderRevisionProposed` → 更新状态为 `REVISION_PENDING`，记录修订详情
- `OrderRevised` → 更新状态为 `APPROVED`，更新金额和明细
- 恢复 `DeliveryPickedUp` 订阅
- `OrderCancelPending`（DEV-03 新增后）
- `OrderCancelUndone`（DEV-03 新增后）

---

### DEV-05 [P1] 为 `KitchenService` 补充 `@Transactional`

**文件**: `KitchenService.java`

以下方法需添加 `@Transactional`：
- `cancel(ticketId)`
- `confirmCancel(ticketId)`
- `undoCancel(ticketId)`
- `beginReviseOrder(ticketId, revisedLineItems)`
- `confirmReviseTicket(ticketId, revisedLineItems)`
- `undoBeginReviseOrder(ticketId)`

---

### DEV-06 [P2] DeliveryService 订阅 `OrderRevised`

**文件**: `DeliveryMessageHandlers.java`

```java
// 新增订阅
.forAggregateType("net.chrisrichardson.ftgo.orderservice.domain.Order")
.onEvent(OrderRevised.class, this::handleOrderRevised)

// 新增处理方法
private void handleOrderRevised(DomainEventEnvelope<OrderRevised> dee) {
    dee.getEvent().getOrderRevision()
       .getDeliveryInformation()
       .ifPresent(di -> deliveryService.updateDeliveryAddress(
           Long.parseLong(dee.getAggregateId()),
           di.getDeliveryAddress()));
}
```

---

### DEV-07 [P2] 一致性检查定时任务（Kotlin）

**新文件**: `ftgo-order-service/src/main/kotlin/.../consistency/OrderConsistencyChecker.kt`

见下方独立 Kotlin 文件。

---

### DEV-08 [P2] 并发 Saga 防护

**方案**: 在 `OrderService.cancel()` 和 `reviseOrder()` 中加版本号乐观锁检查：

```kotlin
// OrderService.kt 扩展
@Transactional
fun cancelWithVersion(orderId: Long, expectedVersion: Long): Order {
    val order = orderRepository.findById(orderId)
        .orElseThrow { OrderNotFoundException(orderId) }
    if (order.version != expectedVersion) {
        throw OptimisticLockingFailureException("Order $orderId version mismatch")
    }
    val events = order.cancel()
    orderAggregateEventPublisher.publish(order, events)
    return order
}
```

---

## 三、测试 TODO

### TEST-01 [P0] `Ticket.accept()` Bug 回归测试

**新文件**: `ftgo-kitchen-service/src/test/kotlin/.../domain/TicketAcceptStateTest.kt`

见下方独立 Kotlin 文件。

---

### TEST-02 [P0] `Ticket.cancelCreate()` 补偿测试

**新文件**: `ftgo-kitchen-service/src/test/kotlin/.../domain/TicketCancelCreateTest.kt`

---

### TEST-03 [P1] Saga 补偿路径集成测试

对每个 Saga 的每个步骤模拟失败，验证补偿路径正确执行：

| Saga | 模拟失败步骤 | 预期补偿 |
|------|-------------|----------|
| CreateOrderSaga | `kitchenService.create` 失败 | 调用 `orderService.reject` |
| CreateOrderSaga | `accountingService.authorize` 失败 | 调用 `kitchenService.cancel`、`orderService.reject` |
| CancelOrderSaga | `reverseAuthorization` 失败 | 调用 `undoBeginCancelTicket`、`undoBeginCancel` |
| ReviseOrderSaga | `confirmTicketRevision` 失败 | 调用 `undoBeginReviseTicket`、`undoBeginReviseOrder` |

---

### TEST-04 [P1] 幂等性测试（重放相同事件）

**新文件**: `ftgo-order-history-service/src/test/kotlin/.../IdempotencyTest.kt`

- 重复发送 `OrderCreatedEvent` → 验证 DynamoDB 中只有一条记录
- 重复发送 `OrderAuthorized` → 验证状态只更新一次

---

### TEST-05 [P1] 并发 Saga 测试

```kotlin
// 测试场景：同时发起 Cancel 和 Revise
@Test
fun `concurrent cancel and revise should not corrupt order state`() {
    val orderId = createApprovedOrder()
    
    val cancelFuture = CompletableFuture.runAsync { orderService.cancel(orderId) }
    val reviseFuture = CompletableFuture.runAsync { 
        orderService.reviseOrder(orderId, revision) 
    }
    
    // 至少一个应成功，另一个应抛 OptimisticLockingFailure
    val results = listOf(cancelFuture, reviseFuture)
        .map { runCatching { it.join() } }
    
    val failures = results.count { it.isFailure }
    assertTrue(failures <= 1, "最多一个 Saga 应失败")
    
    // 验证 Order 状态一致
    val order = orderRepository.findById(orderId).get()
    assertThat(order.state).isIn(OrderState.CANCELLED, OrderState.APPROVED)
}
```

---

### TEST-06 [P2] Order → OrderHistory 最终一致性测试

- 发送 `OrderRevisionProposed` → 等待消费 → 验证 History 中状态为 `REVISION_PENDING`
- 发送 `OrderRevised` → 等待消费 → 验证 History 中金额已更新

---

### TEST-07 [P2] Order/Ticket/Delivery 状态同步测试

验证正常流程完成后三者状态符合预期：

| Order 状态 | Ticket 状态 | Delivery 状态 |
|-----------|------------|--------------|
| APPROVED | AWAITING_ACCEPTANCE | PENDING |
| APPROVED | ACCEPTED | SCHEDULED |
| CANCELLED | CANCELLED | CANCELLED |

---

## 四、运维 TODO

### OPS-01 [P0] Saga 卡死告警

**检测逻辑**：查询 `saga_instance` 表中 `last_request_id` 超过阈值时间未更新的记录。

```sql
-- 检测超过 30 分钟未更新的 Saga（每 5 分钟运行）
SELECT saga_type, saga_id, last_request_id, state_name
FROM saga_instance
WHERE last_request_id IS NOT NULL
  AND TIMESTAMPDIFF(MINUTE, updated_at, NOW()) > 30;
```

告警阈值：
- Warning: 30 分钟
- Critical: 2 小时

---

### OPS-02 [P1] CDC 服务健康监控

监控 `eventuate-cdc` 服务：
- `message` 表积压量（未发布消息数）
- `received_messages` 表幂等记录大小（定期清理 > 7 天的记录）
- Binlog 延迟（CDC 读取 MySQL Binlog 的延迟）

Prometheus 指标（Grafana 看板）：
```
eventuate_cdc_published_messages_total
eventuate_cdc_unpublished_messages_gauge  # 应接近 0
eventuate_cdc_binlog_lag_seconds
```

---

### OPS-03 [P1] Kafka 消费延迟告警

对以下 Consumer Group 设置延迟告警（阈值 1000 条）：

| Consumer Group | Topic | 告警阈值 |
|---------------|-------|---------|
| ftgo-order-history-service | orderService | 1000 |
| ftgo-delivery-service | kitchenService | 500 |
| ftgo-order-service-sagaparticipant | all | 500 |

---

### OPS-04 [P1] 死信队列（DLQ）处理规程

当消息处理持续失败时：
1. 消息进入 `{topic}.DLT`（Dead Letter Topic）
2. 告警触发 On-call
3. 分析失败原因（通常是 BUG-01/BUG-02 类型的状态异常）
4. 修复代码后，从 DLT 重放消息：
   ```bash
   # 重放 DLT 消息到原 Topic
   kafka-consumer-groups.sh --reset-offsets --to-earliest \
     --group ftgo-order-service-sagaparticipant \
     --topic orderService.DLT --execute
   ```

---

### OPS-05 [P2] 数据一致性周期扫描（Kotlin）

**新文件**: `ftgo-order-service/src/main/kotlin/.../consistency/OrderConsistencyScanner.kt`

见下方独立 Kotlin 文件。

---

### OPS-06 [P2] 数据修复 Runbook

**场景 A：Order 为 APPROVED 但 Ticket 为 AWAITING_ACCEPTANCE 超过 1 小时**

```sql
-- 查询异常订单
SELECT o.id, o.state, t.state AS ticket_state, o.created_time
FROM orders o
JOIN tickets t ON t.id = o.id
WHERE o.state = 'APPROVED'
  AND t.state = 'AWAITING_ACCEPTANCE'
  AND TIMESTAMPDIFF(HOUR, o.created_time, NOW()) > 1;
```

修复步骤：
1. 确认 `saga_instance` 中对应 Saga 状态
2. 若 Saga 已完成但 Ticket 未更新 → 手动发送 `ConfirmCreateTicket` 命令
3. 若 Saga 卡死 → 触发补偿重放

---

**场景 B：Order 为 CANCEL_PENDING 超过 30 分钟**

```sql
SELECT id, state, version FROM orders
WHERE state = 'CANCEL_PENDING'
  AND TIMESTAMPDIFF(MINUTE, updated_at, NOW()) > 30;
```

修复步骤：
1. 查找对应 CancelOrderSaga 实例
2. 恢复卡死的 Saga 步骤（通常是 `reverseAuthorization` 超时）
3. 手动重放对应消息

---

## 五、优先级汇总

| 优先级 | 内容 | 预估工期 |
|-------|------|--------|
| P0 | BUG-01 Ticket.accept() fix | 0.5 天 |
| P0 | BUG-02 cancelCreate() 实现 | 0.5 天 |
| P0 | TEST-01/02 回归测试 | 0.5 天 |
| P0 | OPS-01 Saga 卡死告警 | 1 天 |
| P1 | IC-01 Order 中间状态 Events | 1 天 |
| P1 | IC-02 OrderHistory 补充订阅 | 1 天 |
| P1 | IC-05 KitchenService @Transactional | 0.5 天 |
| P1 | TEST-03 Saga 补偿路径测试 | 2 天 |
| P1 | TEST-04 幂等性测试 | 1 天 |
| P1 | OPS-02/03 监控告警 | 1 天 |
| P2 | IC-03 DeliveryService 订阅 | 0.5 天 |
| P2 | IC-04 AccountingService 退款事件 | 0.5 天 |
| P2 | DEV-07/08 一致性检查/并发防护 | 2 天 |
| P2 | OPS-04/05/06 DLQ/扫描/Runbook | 2 天 |
