# 领域事件目录与跨服务事件流

## 事件总线

所有事件通过 **Eventuate Tram**（事务性发件箱 + Apache Kafka）传递，保证至少一次投递。

---

## 事件目录

### Order Service 发布

| 事件类 | 包 | 触发时机 |
|--------|----|---------|
| `OrderCreatedEvent` | `ftgo-order-service-api` | 订单创建，进入 `APPROVAL_PENDING` |
| `OrderAuthorized` | `ftgo-order-service-api` | Saga 完成，状态变为 `APPROVED` |
| `OrderRejected` | `ftgo-order-service-api` | Saga 回滚，状态变为 `REJECTED` |
| `OrderCancelled` | `ftgo-order-service-api` | 取消 Saga 完成 |

**发布通道：** `OrderServiceChannels.ORDER_EVENT_CHANNEL`

---

### Restaurant Service 发布

| 事件类 | 包 | 触发时机 |
|--------|----|---------|
| `RestaurantCreated` | `ftgo-restaurant-service` | 餐厅注册 |
| `RestaurantMenuRevised` | `ftgo-restaurant-service` | 菜单更新 |

**发布通道：** `RestaurantServiceChannels.RESTAURANT_EVENT_CHANNEL`

---

### Kitchen Service 发布

| 事件类 | 包 | 触发时机 |
|--------|----|---------|
| `TicketCreatedEvent` | `ftgo-kitchen-service-api` | Ticket 创建（Saga 步骤） |
| `TicketAcceptedEvent` | `ftgo-kitchen-service-api` | 餐厅接受工单 |
| `TicketPreparationStartedEvent` | `ftgo-kitchen-service-api` | 开始备餐 |
| `TicketPreparationCompletedEvent` | `ftgo-kitchen-service-api` | 备餐完成 |
| `TicketPickedUpEvent` | `ftgo-kitchen-service-api` | 骑手取餐 |
| `TicketCancelled` | `ftgo-kitchen-service-api` | Ticket 取消 |
| `TicketRevised` | `ftgo-kitchen-service-api` | Ticket 修订完成 |

**发布通道：** `KitchenServiceChannels.TICKET_EVENT_CHANNEL`

---

### Consumer Service 发布

| 事件类 | 包 | 触发时机 |
|--------|----|---------|
| `ConsumerCreated` | `ftgo-consumer-service` | 消费者注册 |

---

### Delivery Service 发布

| 事件/DTO 类 | 说明 |
|------------|------|
| `DeliveryInfo`, `DeliveryStatus` | 配送状态信息（供 OrderHistory 消费） |

---

## 事件消费关系

```
发布方                 事件                        消费方 & 处理逻辑
────────────────────  ──────────────────────────  ────────────────────────────────────────────
RestaurantService     RestaurantCreated           → KitchenService: 创建本地 Restaurant 副本
                      RestaurantMenuRevised       → OrderService:   更新本地 Restaurant 副本
                                                  → DeliveryService:更新本地 Restaurant 副本

ConsumerService       ConsumerCreated             → AccountingService: 创建对应 Account

OrderService          OrderCreated                → OrderHistoryService: 创建历史记录
                      OrderAuthorized             → OrderHistoryService: 更新状态
                      OrderRejected               → OrderHistoryService: 更新状态
                      OrderCancelled              → OrderHistoryService: 更新状态

KitchenService        TicketAcceptedEvent         → DeliveryService: 调度骑手
                      TicketPickedUpEvent         → DeliveryService: 更新配送状态
                      TicketAcceptedEvent         → OrderHistoryService: 更新状态
                      TicketCancelled             → OrderHistoryService: 更新状态

DeliveryService       (配送状态变更)               → OrderHistoryService: 更新配送信息
```

---

## 事件设计规范

1. **不可变** — 事件一旦发布不可修改，消费方需做幂等处理（`SourceEvent` 去重）。
2. **最终一致性** — 跨服务的本地副本（Restaurant、Account）通过事件异步同步，短暂不一致可接受。
3. **事件溯源（Accounting）** — `Account` 聚合不存状态列，仅存事件流，状态由重放得出。
4. **防腐层** — 各服务通过 `RestaurantEventMapper` 将外部事件映射到本地模型，隔离上游变化。
