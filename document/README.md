# FTGO Application — 功能文档

> FTGO 是一个食品外卖平台，由 7 个微服务组成，通过 **Eventuate Tram**（事务性发件箱 + Kafka）
> 实现异步消息传递，通过**编排式 Saga** 维护跨服务数据一致性。

---

## 功能索引

| # | 功能 | 入口服务 | 涉及 Saga |
|---|------|---------|----------|
| 1 | [注册消费者](#1-注册消费者) | Consumer | — |
| 2 | [餐厅入驻与菜单管理](#2-餐厅入驻与菜单管理) | Restaurant | — |
| 3 | [下单](#3-下单) | Order | CreateOrderSaga |
| 4 | [取消订单](#4-取消订单) | Order | CancelOrderSaga |
| 5 | [修订订单](#5-修订订单) | Order | ReviseOrderSaga |
| 6 | [后厨工单管理](#6-后厨工单管理) | Kitchen | — |
| 7 | [骑手调度与配送](#7-骑手调度与配送) | Delivery | — |
| 8 | [查询订单历史](#8-查询订单历史) | OrderHistory | — |

---

## 1. 注册消费者

**入口：** `POST /consumers`（via API Gateway → Consumer Service）

**流程：**
1. `ConsumerService.create()` 创建 `Consumer` 聚合，持久化到 MySQL。
2. 发布 `ConsumerCreated` 事件到 Kafka。
3. **Accounting Service** 订阅该事件，自动为该消费者创建对应的支付账户（`Account`）。

**关键数据：** `Consumer { id, name: PersonName }`

**参考：**
- [domain-model.md — Consumer 上下文](domain-model.md#2-consumer-上下文)
- [events.md — ConsumerCreated](events.md#consumer-service-发布)

---

## 2. 餐厅入驻与菜单管理

**入口：**
- `POST /restaurants` — 餐厅入驻
- `PUT /restaurants/{id}/menu` — 修改菜单

**流程（入驻）：**
1. `RestaurantService.create()` 创建 `Restaurant` 聚合。
2. 发布 `RestaurantCreated` 事件。
3. **Kitchen / Order / Delivery Service** 各自订阅，在本地维护餐厅副本（防腐层）。

**流程（修改菜单）：**
1. `RestaurantService.reviseMenu()` 更新 `RestaurantMenu`。
2. 发布 `RestaurantMenuRevised` 事件，下游副本自动同步。

**关键数据：** `Restaurant { id, name, menu: [ MenuItem { id, name, price } ] }`

**参考：**
- [domain-model.md — Restaurant 上下文](domain-model.md#3-restaurant-上下文)
- [events.md — Restaurant Service 发布](events.md#restaurant-service-发布)

---

## 3. 下单

**入口：** `POST /orders`（via API Gateway → Order Service）

**流程（CreateOrderSaga）：**

```
消费者                 API Gateway        Order Service
  │── POST /orders ──▶│── createOrder() ──▶ Order{APPROVAL_PENDING}
  │                   │                           │
  │                   │              ┌── Saga 步骤 ──────────────────────┐
  │                   │              │ 1. ValidateOrderByConsumer         │
  │                   │              │    → ConsumerService（验证资格）    │
  │                   │              │ 2. CreateTicket                    │
  │                   │              │    → KitchenService（创建工单）     │
  │                   │              │ 3. AuthorizeCommand                │
  │                   │              │    → AccountingService（预授权）    │
  │                   │              │ 4. ConfirmCreateTicket             │
  │                   │              │    → KitchenService（确认工单）     │
  │                   │              │ 5. ApproveOrderCommand             │
  │                   │              │    → OrderService（审批通过）       │
  │                   │              └───────────────────────────────────┘
  │                   │                           │
  │◀── orderId ───────│◀──────────────────── Order{APPROVED}
```

**成功结果：** `Order.state = APPROVED`，后厨 `Ticket` 进入 `AWAITING_ACCEPTANCE`

**失败回滚（任意步骤失败）：**
- 步骤 2 已执行 → 发送 `CancelCreateTicket` 补偿
- `Order.state = REJECTED`

**关键类：**
- `CreateOrderSaga` / `CreateOrderSagaState`
- Proxy：`ConsumerServiceProxy`, `KitchenServiceProxy`, `AccountingServiceProxy`

**参考：**
- [sagas.md — CreateOrderSaga](sagas.md#1-createordersaga--创建订单)
- [domain-model.md — Order 上下文](domain-model.md#1-order-上下文)

---

## 4. 取消订单

**入口：** `POST /orders/{orderId}/cancel`（via API Gateway → Order Service）

**流程（CancelOrderSaga）：**

```
步骤  参与方              正向操作                  补偿（失败时逆序）
 1   OrderService       Order → CANCEL_PENDING    Order 恢复 APPROVED
 2   KitchenService     Ticket → CANCEL_PENDING   Ticket 恢复 AWAITING_ACCEPTANCE
 3   AccountingService  撤销支付授权               —（不可逆步骤，此步前回滚）
 4   KitchenService     确认取消 Ticket            —
 5   OrderService       Order → CANCELLED          —
```

**成功结果：** `Order.state = CANCELLED`，`Ticket.state = CANCELLED`

**参考：**
- [sagas.md — CancelOrderSaga](sagas.md#2-cancelordersaga--取消订单)

---

## 5. 修订订单

**入口：** `PUT /orders/{orderId}`（via API Gateway → Order Service）

**典型场景：** 下单后修改菜品数量或追加商品

**流程（ReviseOrderSaga）：**

```
步骤  参与方              正向操作                        补偿
 1   OrderService       计算新金额，Order → REVISION_PENDING   Order 恢复 APPROVED
 2   KitchenService     Ticket 行项进入修订中            Ticket 恢复原状
 3   AccountingService  ReviseAuthorization（差额）      —
 4   KitchenService     确认 Ticket 修订                 —
 5   OrderService       Order → APPROVED（新行项生效）   —
```

**说明：** 步骤 1 的回复携带 `revisedOrderTotal`，用于步骤 3 的差额授权计算。

**参考：**
- [sagas.md — ReviseOrderSaga](sagas.md#3-reviseordersaga--修订订单)

---

## 6. 后厨工单管理

**入口：** Kitchen Service 内部 REST API（不经 API Gateway，由餐厅操作员直接调用）

**工单生命周期：**

```
CREATE_PENDING ──(Saga确认)──▶ AWAITING_ACCEPTANCE
                                      │
                               餐厅接受工单（accept()）
                                      ▼
                                  PREPARING ──▶ READY_FOR_PICKUP ──▶ PICKED_UP
                                      │
                               CANCEL_PENDING ──▶ CANCELLED
```

| 操作 | 触发方式 | 发布事件 |
|------|---------|---------|
| 接受工单 | 餐厅操作员调用 `accept()` | `TicketAcceptedEvent` |
| 开始备餐 | `startPreparation()` | `TicketPreparationStartedEvent` |
| 备餐完成 | `readyForPickup()` | `TicketPreparationCompletedEvent` |
| 骑手取货 | `pickedUp()` | `TicketPickedUpEvent` |
| 取消工单 | Saga 驱动 | `TicketCancelled` |

**下游影响：**
- `TicketAcceptedEvent` → Delivery Service 开始调度骑手
- `TicketPickedUpEvent` → Delivery Service 更新配送状态
- 以上两个事件同时推送到 OrderHistory

**参考：**
- [domain-model.md — Kitchen 上下文](domain-model.md#4-kitchen-上下文)
- [events.md — Kitchen Service 发布](events.md#kitchen-service-发布)

---

## 7. 骑手调度与配送

**特点：** Delivery Service 纯事件驱动，**不参与任何 Saga**，只订阅外部事件。

**事件驱动流程：**

```
事件来源              事件                    Delivery Service 响应
OrderService     OrderCreated          → 创建 Delivery 记录（待分配）
KitchenService   TicketAcceptedEvent   → 从在线骑手中分配一位，更新 Courier.plan
KitchenService   TicketPickedUpEvent   → 更新 Delivery.state = PICKED_UP
```

**核心聚合：**
- `Delivery { id, state, assignedCourier, pickupAddress, deliveryAddress, readyBy }`
- `Courier { id, available, plan: [ Action ] }`

**骑手状态切换：** 骑手通过 `noteUpdatedLocation()` / `noteAvailability()` 更新位置与在线状态。

**参考：**
- [domain-model.md — Delivery 上下文](domain-model.md#6-delivery-上下文)
- [events.md — 事件消费关系](events.md#事件消费关系)

---

## 8. 查询订单历史

**入口：** `GET /orders` / `GET /orders/{orderId}`（via API Gateway → OrderHistory Service）

**特点：** CQRS 读模型，数据存储在 **AWS DynamoDB**，与写侧完全解耦。

**数据来源（订阅事件聚合）：**

```
Order Service   → OrderCreated / OrderAuthorized / OrderRejected / OrderCancelled
Kitchen Service → TicketAcceptedEvent / TicketCancelled
Delivery Service → 配送状态变更
```

**查询能力：**
- 按订单 ID 查询完整状态（含配送信息）
- 按消费者查询历史订单列表

**参考：**
- [domain-model.md — OrderHistory 上下文](domain-model.md#7-orderhistory-上下文cqrs-读侧)

---

## 技术参考文档

| 文档 | 内容 |
|------|------|
| [bounded-contexts.md](bounded-contexts.md) | 限界上下文划分、上下文映射、集成关系类型 |
| [domain-model.md](domain-model.md) | 各服务聚合、实体、值对象、领域事件完整定义 |
| [sagas.md](sagas.md) | 三条 Saga 的步骤序列、补偿逻辑、关键类 |
| [events.md](events.md) | 全量事件目录、跨服务消费关系、事件设计规范 |
| [architecture.md](architecture.md) | 消息基础设施、持久化方案、API 层、测试策略 |
