# 编排式 Saga（Orchestrated Sagas）

> 所有 Saga 由 **Order Service** 发起，使用 **Eventuate Tram Saga** 框架，通过 Kafka 发送命令并等待回复。
> 补偿事务（compensating transaction）在正向步骤失败时逆序执行。

---

## 1. CreateOrderSaga — 创建订单

**触发：** `OrderService.createOrder()` → `Order` 进入 `APPROVAL_PENDING`

**状态对象：** `CreateOrderSagaState`（持有 `orderId`, `orderDetails`, `ticketId`）

### 步骤序列

```
步骤  参与方              正向命令                           补偿命令
───  ─────────────────  ─────────────────────────────────  ──────────────────────
 1   ConsumerService    ValidateOrderByConsumer             —（只读，无需补偿）
 2   KitchenService     CreateTicket                        CancelCreateTicket
 3   AccountingService  AuthorizeCommand                    —（不可逆，此步成功后不补偿）
 4   KitchenService     ConfirmCreateTicket                 —
 5   OrderService       ApproveOrderCommand / Reject        —
```

**结果：**
- 全部成功 → `Order.state = APPROVED`
- 任意步骤失败 → 逆序执行补偿 → `Order.state = REJECTED`

### 关键类
- `net.chrisrichardson.ftgo.orderservice.sagas.createorder.CreateOrderSaga`
- `net.chrisrichardson.ftgo.orderservice.sagas.createorder.CreateOrderSagaState`
- Proxy 类：`ConsumerServiceProxy`, `KitchenServiceProxy`, `AccountingServiceProxy`, `OrderServiceProxy`

---

## 2. CancelOrderSaga — 取消订单

**触发：** `OrderService.cancel()` → `Order` 进入 `CANCEL_PENDING`

**状态对象：** `CancelOrderSagaData`（持有 `orderId`, `restaurantId`, `consumerId`, `orderTotal`）

### 步骤序列

```
步骤  参与方              正向命令                    补偿命令
───  ─────────────────  ──────────────────────────  ───────────────────────
 1   OrderService       BeginCancelCommand          UndoBeginCancelCommand
 2   KitchenService     BeginCancelTicketCommand    UndoBeginCancelTicketCommand
 3   AccountingService  ReverseAuthorizationCommand —
 4   KitchenService     ConfirmCancelTicketCommand  —
 5   OrderService       ConfirmCancelOrderCommand   —
```

**结果：**
- 全部成功 → `Order.state = CANCELLED`
- 失败 → 补偿回滚 → 恢复 `Order.state = APPROVED`

### 关键类
- `net.chrisrichardson.ftgo.orderservice.sagas.cancelorder.CancelOrderSaga`
- `net.chrisrichardson.ftgo.orderservice.sagas.cancelorder.CancelOrderSagaData`

---

## 3. ReviseOrderSaga — 修订订单

**触发：** `OrderService.reviseOrder()` → `Order` 进入 `REVISION_PENDING`

**状态对象：** `ReviseOrderSagaData`（持有 `orderId`, `restaurantId`, `orderRevision`, `revisedOrderTotal`）

### 步骤序列

```
步骤  参与方              正向命令                     补偿命令
───  ─────────────────  ───────────────────────────  ─────────────────────────────
 1   OrderService       BeginReviseOrderCommand      UndoBeginReviseOrderCommand
 2   KitchenService     BeginReviseTicketCommand     UndoBeginReviseTicketCommand
 3   AccountingService  ReviseAuthorization          —
 4   KitchenService     ConfirmReviseTicketCommand   —
 5   OrderService       ConfirmReviseOrderCommand    —
```

**回复处理：** 步骤 1 回复 `BeginReviseOrderReply`，携带 `revisedOrderTotal` 用于后续授权金额计算。

**结果：**
- 全部成功 → `Order.state = APPROVED`（含新行项）
- 失败 → 补偿回滚

### 关键类
- `net.chrisrichardson.ftgo.orderservice.sagas.reviseorder.ReviseOrderSaga`
- `net.chrisrichardson.ftgo.orderservice.sagas.reviseorder.ReviseOrderSagaData`

---

## Saga 基础设施

| 组件 | 说明 |
|------|------|
| **Eventuate Tram Saga** | 框架，负责持久化 Saga 状态、发送命令消息、路由回复 |
| **事务性发件箱** | 命令消息与业务数据在同一本地事务写入 MySQL，CDC 发布到 Kafka |
| `OrderCommandHandlers` | Order Service 自身也作为 Saga 参与方，处理 `ApproveOrderCommand` 等 |
| `KitchenServiceCommandHandler` | Kitchen Service Saga 参与方处理器 |
| `AccountingServiceCommandHandler` | Accounting Service Saga 参与方处理器 |
| `ConsumerServiceCommandHandlers` | Consumer Service Saga 参与方处理器 |
