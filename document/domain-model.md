# 领域模型（Domain Model）

## 1. Order 上下文

### 聚合根：`Order`
`net.chrisrichardson.ftgo.orderservice.domain.Order`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `Long` | 主键 |
| `state` | `OrderState` | 枚举状态机 |
| `consumerId` | `long` | 消费者 ID（引用，非对象） |
| `restaurant` | `Restaurant` | 本地餐厅副本（值引用） |
| `orderLineItems` | `OrderLineItems` | 值对象，内嵌行项集合 |
| `deliveryInformation` | `DeliveryInformation` | 配送信息值对象 |
| `paymentInformation` | `PaymentInformation` | 支付信息值对象 |
| `orderMinimum` | `Money` | 最低起送金额 |

**状态机（`OrderState`）：**
```
APPROVAL_PENDING → APPROVED → CANCEL_PENDING → CANCELLED
                ↘ REJECTED
                           → REVISION_PENDING → APPROVED
```

**值对象：**
- `OrderLineItems` — 行项列表，提供 `changeToRevised()` 方法
- `OrderRevision` / `RevisedOrder` / `LineItemQuantityChange` — 修改提案
- `DeliveryInformation` — 配送地址 + 时间
- `PaymentInformation` — 支付令牌

**本地读模型：** `Restaurant`（仅存菜单，由 Restaurant 事件驱动更新）

**仓储：** `OrderRepository`, `RestaurantRepository`

**领域服务：** `OrderService` — 创建订单、接受/拒绝、取消、修订入口

---

## 2. Consumer 上下文

### 聚合根：`Consumer`
`net.chrisrichardson.ftgo.consumerservice.domain.Consumer`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `Long` | 主键 |
| `name` | `PersonName` | 姓名值对象 |

**领域事件：** `ConsumerCreated`

**领域服务：** `ConsumerService` — `create()`, `validateOrderByConsumer()`

**Saga 参与方命令：** `ValidateOrderByConsumer`（验证消费者是否有资格下单）

---

## 3. Restaurant 上下文

### 聚合根：`Restaurant`
`net.chrisrichardson.ftgo.restaurantservice.domain.Restaurant`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `Long` | 主键 |
| `name` | `String` | 餐厅名 |
| `restaurantMenu` | `RestaurantMenu` | 菜单值对象 |

**值对象：** `RestaurantMenu`（含 `List<MenuItem>`）、`MenuItem`（`id`, `name`, `price`）

**领域事件：** `RestaurantCreated`, `RestaurantMenuRevised`

**领域服务：** `RestaurantService` — `create()`, `reviseMenu()`

---

## 4. Kitchen 上下文

### 聚合根：`Ticket`
`net.chrisrichardson.ftgo.kitchenservice.domain.Ticket`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `Long` | 对应 Order.id |
| `state` | `TicketState` | 后厨工单状态 |
| `restaurant` | `Restaurant` | 本地餐厅副本 |
| `lineItems` | `List<TicketLineItem>` | 工单行项 |
| `readyBy` | `LocalDateTime` | 预计完成时间 |
| `acceptTime` / `preparingTime` / `pickedUpTime` | `LocalDateTime` | 时间戳 |

**状态机（`TicketState`）：**
```
CREATE_PENDING → AWAITING_ACCEPTANCE → PREPARING → READY_FOR_PICKUP → PICKED_UP
              ↘ CANCEL_PENDING → CANCELLED
                              ↗ (undo)
```

**领域事件：** `TicketCreatedEvent`, `TicketAcceptedEvent`, `TicketCancelled`,
`TicketPreparationStartedEvent`, `TicketPreparationCompletedEvent`, `TicketPickedUpEvent`, `TicketRevised`

**Saga 参与方命令：**
`CreateTicket`, `ConfirmCreateTicket`, `CancelCreateTicket`,
`BeginCancelTicketCommand`, `UndoBeginCancelTicketCommand`, `ConfirmCancelTicketCommand`,
`BeginReviseTicketCommand`, `UndoBeginReviseTicketCommand`, `ConfirmReviseTicketCommand`,
`ChangeTicketLineItemQuantity`

---

## 5. Accounting 上下文

### 聚合根：`Account`（事件溯源）
`net.chrisrichardson.ftgo.accountingservice.domain.Account`

> 使用 **Eventuate Local** 事件溯源框架，状态从事件流重放得出。

**领域事件（同时作为持久化存储）：**
- `AccountCreatedEvent`
- `AccountAuthorizedEvent`
- `AccountAuthorizationFailed`

**Saga 参与方命令：**
- `AuthorizeCommand` — 预授权支付
- `ReverseAuthorizationCommand` — 撤销授权（补偿）
- `ReviseAuthorization` — 修订授权金额

**回复：** `AccountDisabledReply`（账户被禁用时拒绝）

---

## 6. Delivery 上下文

### 聚合根：`Courier`
`net.chrisrichardson.ftgo.deliveryservice.domain.Courier`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `Long` | 骑手 ID |
| `available` | `boolean` | 是否在线可接单 |
| `plan` | `Plan` | 行程计划（内嵌值对象） |

**值对象：** `Plan`（含 `List<Action>`）、`Action`（`type`, `deliveryId`, `time`）

### 聚合根：`Delivery`
`net.chrisrichardson.ftgo.deliveryservice.domain.Delivery`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `Long` | 对应 Order.id |
| `state` | `DeliveryState` | 配送状态枚举 |
| `assignedCourier` | `Long` | 当前分配的骑手 |
| `pickupAddress` / `deliveryAddress` | `Address` | 地址值对象 |
| `readyBy` / `pickUpTime` / `deliveredTime` | `LocalDateTime` | 时间戳 |

**事件驱动（消费外部事件，无 Saga 参与）：**
- 消费 `OrderCreated` → 创建 Delivery
- 消费 `TicketAcceptedEvent` → 调度骑手
- 消费 `TicketPickedUpEvent` → 更新状态

---

## 7. OrderHistory 上下文（CQRS 读侧）

### 查询模型：`OrderHistory`
`net.chrisrichardson.ftgo.cqrs.orderhistory.dynamodb.Order`（DynamoDB Item）

**订阅事件来源：**
- Order Service：`OrderCreated`, `OrderAuthorized`, `OrderRejected`, `OrderCancelled`
- Kitchen Service：`TicketAcceptedEvent`, `TicketCancelled`
- Delivery Service：配送状态变更

**DAO：** `OrderHistoryDao` / `OrderHistoryDaoDynamoDb`

---

## 共享内核（Shared Kernel）

`net.chrisrichardson.ftgo.common`

| 类 | 类型 | 说明 |
|----|------|------|
| `Money` | 值对象 | 金额，基于 `BigDecimal` |
| `Address` | 值对象 | 地址（街道、城市、邮编、州） |
| `PersonName` | 值对象 | 姓（first/last） |
| `RevisedOrderLineItem` | 值对象 | 修订行项 |
