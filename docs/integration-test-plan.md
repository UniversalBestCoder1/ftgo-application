# FTGO 集成测试计划

> 创建日期：2026-06-30  
> 分支：jdk-21-version  
> 目标：验证 data-consistency-todos.md 中所有已修复问题的回归覆盖  
> 关联文档：[data-consistency-todos.md](./data-consistency-todos.md)

---

## 一、测试分层策略

```
┌─────────────────────────────────────────────────┐
│  T3: 数据一致性集成测试（Docker Compose + 真实 DB）  │  T-09 ~ T-12
├─────────────────────────────────────────────────┤
│  T2: OrderService 并发保护测试（Spring + Mockito）  │  T-07 ~ T-08
├─────────────────────────────────────────────────┤
│  T1: Saga 单元测试（SagaUnitTestSupport in-memory）│  T-01 ~ T-06
└─────────────────────────────────────────────────┘
```

- **T1** 不需要 Docker，几秒内完成，优先实现
- **T2** 使用 Spring 容器 + Mockito，不依赖外部服务
- **T3** 需要 `docker-compose up`，作为 CI 环境的最终门禁

---

## 二、T1：Saga 单元测试（in-memory）

参考现有 `CreateOrderSagaTest.java` 的 `SagaUnitTestSupport.given()` DSL 风格。

### T-01 / T-02 / T-03 — CancelOrderSaga

**待创建文件**：  
`ftgo-order-service/src/test/java/net/chrisrichardson/ftgo/orderservice/sagas/cancelorder/CancelOrderSagaTest.java`

CancelOrderSaga 步骤：
```
step 1: orderService.beginCancel       (补偿: undoBeginCancel)
step 2: kitchenService.beginCancel     (补偿: undoBeginCancelTicket)
step 3: accountingService.reverse      (无补偿)
step 4: kitchenService.confirmCancel
step 5: orderService.confirmCancel
```

| 编号 | 测试方法 | 模拟场景 | 预期结果 |
|------|----------|----------|---------|
| T-01 | `shouldCancelOrder` | 5步全成功 | 最终发送 `ConfirmCancelOrderCommand` |
| T-02 | `shouldCompensateWhenKitchenRejectsCancel` | step 2 返回 failure | 执行补偿：发送 `UndoBeginCancelCommand` |
| T-03 | `shouldCompensateWhenAccountingReversalFails` | step 3 返回 failure | 执行补偿：`undoBeginCancelTicket` + `undoBeginCancel` |

---

### T-04 / T-05 / T-06 — ReviseOrderSaga

**待创建文件**：  
`ftgo-order-service/src/test/java/net/chrisrichardson/ftgo/orderservice/sagas/reviseorder/ReviseOrderSagaTest.java`

ReviseOrderSaga 步骤：
```
step 1: orderService.beginRevise       (补偿: undoBeginReviseOrder)
         onReply: BeginReviseOrderReply → 提取 revisedOrderTotal
step 2: kitchenService.beginRevise     (补偿: undoBeginReviseTicket)
step 3: accountingService.authorize    (无补偿)
step 4: kitchenService.confirmRevise
step 5: orderService.confirmRevise
```

| 编号 | 测试方法 | 模拟场景 | 预期结果 |
|------|----------|----------|---------|
| T-04 | `shouldReviseOrder` | 5步全成功 | 最终发送 `ConfirmReviseOrderCommand`，金额正确 |
| T-05 | `shouldCompensateWhenAccountingAuthorizationFails` | step 3 返回 failure | 执行补偿：`undoBeginReviseTicket` + `undoBeginReviseOrder` |
| T-06 | `shouldPassRevisedOrderTotalToAuthorization` | step 1 返回含 `revisedOrderTotal` 的 reply | 验证 step 3 的 `AuthorizeCommand` 使用了修订后金额（而非原金额） |

---

## 三、T2：OrderService 并发保护测试

**修改文件**：  
`ftgo-order-service/src/test/java/net/chrisrichardson/ftgo/orderservice/domain/OrderServiceTest.java`

| 编号 | 测试方法 | 场景 | 预期结果 |
|------|----------|------|---------|
| T-07 | `shouldPreventConcurrentCancelWithOptimisticLock` | 两个线程同时调用 `cancel(orderId)` | 第二次抛 `OptimisticLockingFailureException`；Order 只进入一次 `CANCEL_PENDING` |
| T-08 | `shouldRejectCancelWhenAlreadyCancelPending` | 订单已为 `CANCEL_PENDING`，再次调用 `cancel()` | 抛 `UnsupportedStateTransitionException`（状态机防护） |

测试依赖：
- `@MockBean SagaInstanceFactory` — 避免真实启动 Saga
- `TestEntityManager` 或 `@DataJpaTest` — 测试乐观锁行为

---

## 四、T3：数据一致性集成测试

> 需要：`docker-compose -f docker-compose-integration-test.yml up`  
> 包含服务：MySQL、Kafka、Zookeeper、eventuate-cdc

### T-09 — CreateOrderSaga 完成后三服务状态一致

**验证点**：
- `orders` 表：`state = APPROVED`
- `tickets` 表：`state = AWAITING_ACCEPTANCE`，`order_id` 与 Order 一致
- `order_history` DynamoDB：记录存在，`status = APPROVED`

```
前置条件：Consumer 已创建，Restaurant 已注册，AccountingService mock 授权成功
触发：POST /orders
等待：Saga 完成（轮询 GET /orders/{id} 直到 state != APPROVAL_PENDING，超时 10s）
断言：上述三处状态一致
```

---

### T-10 — CancelOrderSaga 完成后三服务状态一致

**验证点**：
- `orders` 表：`state = CANCELLED`
- `tickets` 表：`state = CANCELLED`
- `order_history` DynamoDB：`status = CANCELLED`

---

### T-11 — ReviseOrderSaga 完成后金额与数量一致

**验证点**：
- `orders` 表：`order_total` = 修订后金额
- `order_line_items`（或内嵌 JSON）：数量更新
- `tickets` 表：`line_items` 数量与 Order 一致

---

### T-12 — Order 事件 → OrderHistory 最终一致性

**验证顺序**：

| 步骤 | 触发事件 | 等待 | 验证 OrderHistory 中 |
|------|----------|------|---------------------|
| 1 | OrderCreated | 500ms | status = APPROVAL_PENDING |
| 2 | OrderAuthorized | 500ms | status = APPROVED |
| 3 | OrderRevisionProposed（DEV-03 修复后） | 500ms | status = REVISION_PENDING |
| 4 | OrderRevised | 500ms | status = APPROVED，金额已更新 |
| 5 | OrderCancelPending（DEV-03 修复后） | 500ms | status = CANCEL_PENDING |
| 6 | OrderCancelled | 500ms | status = CANCELLED |

---

## 五、测试优先级与工期估算

| 优先级 | 编号 | 描述 | 依赖 | 预估工期 |
|--------|------|------|------|---------|
| P0 | T-01 ~ T-03 | CancelOrderSagaTest | 无 | 0.5 天 |
| P0 | T-04 ~ T-06 | ReviseOrderSagaTest | 无 | 0.5 天 |
| P1 | T-07 ~ T-08 | OrderService 并发测试 | DEV-08（CC-01 修复） | 0.5 天 |
| P1 | T-09 ~ T-11 | 三服务状态一致性 | Docker Compose 环境 | 1.5 天 |
| P2 | T-12 | OrderHistory 最终一致性 | DEV-03（IC-01 修复） | 1 天 |

**总计**：约 4 天

---

## 六、执行方式

### T1（本地快跑）
```bash
cd ftgo-order-service
./gradlew test --tests "*.sagas.cancelorder.*" --tests "*.sagas.reviseorder.*"
```

### T2（Spring 上下文）
```bash
cd ftgo-order-service
./gradlew test --tests "*.domain.OrderServiceTest"
```

### T3（需要 Docker）
```bash
# 启动基础设施
docker-compose -f docker-compose-integration-test.yml up -d

# 等待服务就绪后运行
./gradlew integrationTest

# 清理
docker-compose -f docker-compose-integration-test.yml down -v
```

---

## 七、进度跟踪

| 编号 | 状态 | 完成日期 | 备注 |
|------|------|---------|------|
| T-01 | ✅ 完成 | 2026-06-30 | `CancelOrderSagaTest.kt` — happy path |
| T-02 | ✅ 完成 | 2026-06-30 | `CancelOrderSagaTest.kt` — beginCancelTicket 失败补偿 |
| T-03 | ✅ 完成 | 2026-06-30 | `CancelOrderSagaTest.kt` — reverseAuthorization 失败补偿 |
| T-04 | ✅ 完成 | 2026-06-30 | `ReviseOrderSagaTest.kt` — happy path |
| T-05 | ✅ 完成 | 2026-06-30 | `ReviseOrderSagaTest.kt` — beginReviseTicket 失败补偿 |
| T-06 | ✅ 完成 | 2026-06-30 | `ReviseOrderSagaTest.kt` — reviseAuthorization 失败补偿 |
| T-07 | ✅ 完成 | 2026-06-30 | `OrderServiceTest.java` — 重复 cancel 只创建一个 Saga |
| T-08 | ✅ 完成 | 2026-06-30 | `OrderServiceTest.java` — CANCEL_PENDING 状态被拒绝 |
| T-09 | ✅ 完成 | 2026-06-30 | `SagaConsistencyIntegrationTest.java` — CreateOrderSaga 三状态一致 |
| T-10 | ✅ 完成 | 2026-06-30 | `SagaConsistencyIntegrationTest.java` — CancelOrderSaga 三状态一致 |
| T-11 | ✅ 完成 | 2026-06-30 | `SagaConsistencyIntegrationTest.java` — ReviseOrderSaga 金额/数量一致 |
| T-12 | ⬜ 待实现 | | 依赖 DEV-03 |
