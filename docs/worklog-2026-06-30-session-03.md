# 工作日志 — 2026-06-30 Session 03

## 概述

本次会话完成 CC-01（并发 Saga 竞争防护）。

---

## CC-01 — 并发 Saga 竞争防护

### 问题

`OrderService.cancel()` 和 `reviseOrder()` 只是读取 Order 后立即创建 Saga，没有在同一事务内修改 Order 实体。若两个线程同时发起取消/修订请求，两者都能通过，产生两个并发 Saga：

```
Thread A: cancel() → reads APPROVED → creates CancelOrderSaga A
Thread B: cancel() → reads APPROVED → creates CancelOrderSaga B
  → Saga A: beginCancel APPROVED→CANCEL_PENDING ✅
  → Saga B: beginCancel 失败(CANCEL_PENDING≠APPROVED) → 补偿 undoBeginCancel
                                    → CANCEL_PENDING→APPROVED ← 撤销了 Saga A 的进度！
```

### 解决方案

**急切状态转换（Eager State Transition）+ JPA `@Version` 乐观锁**

1. `OrderService.cancel()` 在创建 Saga 之前就在同一事务内调用 `order.cancel()`
   - 状态 APPROVED → CANCEL_PENDING，发布 `OrderCancelPending` 事件
   - JPA `@Version` 在提交时递增；若两个线程都持有同一版本，第二个提交会抛 `ObjectOptimisticLockingFailureException`，Saga 根本不会被创建

2. `OrderService.reviseOrder()` 同理，急切调用 `order.revise()` → REVISION_PENDING

3. `Order.cancel()` 增加 CANCEL_PENDING 幂等分支（返回 `emptyList()`）
   - Saga 的 `beginCancel` 步骤仍能正常执行：order 已是 CANCEL_PENDING，返回空事件列表，回复 `withSuccess()`

4. `Order.revise()` 增加 REVISION_PENDING 幂等分支
   - `beginReviseOrder` 步骤仍可计算 `LineItemQuantityChange` 并放入 `BeginReviseOrderReply`

5. 新增 `OrderNotInRequiredStateException`：order 不在预期状态时抛出（顺序调用的错误状态场景）

### 修改的文件

| 文件 | 变更摘要 |
|------|---------|
| `Order.java` | `cancel()` 增加 CANCEL_PENDING 幂等分支；`revise()` 增加 REVISION_PENDING 幂等分支 |
| `OrderService.java` | `cancel()` 急切状态转换 + 状态守卫；`reviseOrder()` 同理 |
| `OrderNotInRequiredStateException.java` | 新建，携带 orderId / required / actual 状态 |
| `OrderServiceExtTest.kt` | 新增 4 个状态守卫测试用例（cancel 状态守卫×2，reviseOrder 守卫×2） |
| `OrderConcurrentSagaTest.kt` | 新建，6 个测试：cancel/revise 守卫（×4）+ 领域层幂等（×2） |

### 测试结果

```
OrderConcurrentSagaTest  : 6 个用例，0 失败
OrderServiceExtTest      : 11 个用例，0 失败
全量 ftgo-order-service  : 41 个用例（2 个预先 skip），0 失败
```

### 遗留说明

真正的并发竞态（两个 JVM 线程同版本提交）需要真实数据库做集成测试（TEST-05），
`ObjectOptimisticLockingFailureException` 在单元测试中无法复现。
目前的守卫防止了：
- 顺序的错误状态调用（`OrderNotInRequiredStateException`）
- 在同一进程/事务内的并发写冲突（JPA `@Version`）
