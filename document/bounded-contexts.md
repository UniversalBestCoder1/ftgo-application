# 限界上下文（Bounded Contexts）

## 上下文一览

| 限界上下文 | 服务模块 | 核心职责 |
|-----------|---------|---------|
| **Order** | `ftgo-order-service` | 订单生命周期管理，Saga 编排中心 |
| **Consumer** | `ftgo-consumer-service` | 消费者身份与资格验证 |
| **Restaurant** | `ftgo-restaurant-service` | 餐厅信息与菜单管理 |
| **Kitchen** | `ftgo-kitchen-service` | 后厨工单（Ticket）管理 |
| **Accounting** | `ftgo-accounting-service` | 支付授权，事件溯源账户 |
| **Delivery** | `ftgo-delivery-service` | 骑手调度与配送跟踪 |
| **OrderHistory** | `ftgo-order-history-service` | CQRS 查询侧，DynamoDB 读模型 |

---

## 上下文映射（Context Map）

```
                        ┌─────────────────────┐
                        │   API Gateway        │
                        │  (ftgo-api-gateway)  │
                        └────────┬────────────┘
                                 │ HTTP (WebClient)
          ┌──────────────────────┼──────────────────────┐
          ▼                      ▼                      ▼
  ┌───────────────┐     ┌───────────────┐     ┌───────────────┐
  │  Order Ctx    │     │ Consumer Ctx  │     │Restaurant Ctx │
  │ (Saga 发起方) │     │               │     │               │
  └───────┬───────┘     └───────────────┘     └───────┬───────┘
          │ Saga 命令/回复 (Kafka)                     │ RestaurantCreated
          ├──────────────────────────────┐             │ (事件发布)
          ▼                              ▼             ▼
  ┌───────────────┐             ┌────────────────┐  ┌──────────────┐
  │  Kitchen Ctx  │             │ Accounting Ctx │  │ Delivery Ctx │
  │  (Ticket)     │             │ (Account,ES)   │  │(Courier+Del) │
  └───────────────┘             └────────────────┘  └──────────────┘
          │                              │                │
          └──────────────────────────────┴────────────────┘
                          │ 领域事件 (Kafka)
                          ▼
                ┌──────────────────┐
                │ OrderHistory Ctx │
                │ (CQRS DynamoDB)  │
                └──────────────────┘
```

---

## 集成关系类型

### 客户/供应商（Customer/Supplier）
- **Order** 作为 Saga 编排者，向 Consumer / Kitchen / Accounting 发送命令 → 下游服务作为 **Saga 参与方** 提供回复。

### 发布/订阅（Pub/Sub，防腐层）
- **Restaurant** 发布 `RestaurantCreated` / `RestaurantMenuRevised`，Kitchen / Order / Delivery 各自维护**本地副本**（anti-corruption layer，`RestaurantEventMapper`）。
- **Consumer** 发布 `ConsumerCreated`，Accounting 订阅后创建 Account（conformist 映射）。

### CQRS 读写分离
- Order / Kitchen / Delivery 的写侧事件被 **OrderHistory** 消费，构建 DynamoDB 只读视图。

### Shared Kernel
- `ftgo-common`：`Money`、`Address`、`PersonName` 被所有服务共享。
