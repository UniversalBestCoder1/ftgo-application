package net.chrisrichardson.ftgo.orderservice.consistency

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * DEV-07: 数据一致性检查定时任务。
 *
 * 检测长时间停留在中间状态（APPROVAL_PENDING / CANCEL_PENDING / REVISION_PENDING）
 * 的订单并输出告警日志。通过 ftgo.consistency.warn-threshold-minutes 和
 * ftgo.consistency.critical-threshold-minutes 配置阈值（默认 30 / 120 分钟）。
 *
 * 所有查询均使用 JDBC，无需 OrderRepository 派生方法。
 */
@Component
class OrderConsistencyChecker(
    private val jdbc: JdbcTemplate,
    private val props: ConsistencyCheckerProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // -----------------------------------------------------------------------
    // 定时扫描：每 5 分钟一次（fixedDelay 避免任务重叠）
    // -----------------------------------------------------------------------

    @Scheduled(fixedDelayString = "\${ftgo.consistency.scan-interval-ms:300000}")
    fun checkStuckOrders() {
        checkMiddleState("APPROVAL_PENDING")
        checkMiddleState("CANCEL_PENDING")
        checkMiddleState("REVISION_PENDING")
    }

    private fun checkMiddleState(state: String) {
        // 使用 INFORMATION_SCHEMA-safe 方式：查询存在 updated_time 则用它，否则 created_time
        // Order 实体表 orders 只有 created_time；如果以后加了 updated_time 也能自适应
        val warnMinutes    = props.warnThreshold.toMinutes()
        val critMinutes    = props.criticalThreshold.toMinutes()

        data class StuckOrder(val id: Long, val version: Long, val ageMinutes: Long)

        val sql = """
            SELECT id, version,
                   TIMESTAMPDIFF(MINUTE, COALESCE(updated_time, created_time, NOW()), NOW()) AS age_minutes
            FROM orders
            WHERE state = ?
            HAVING age_minutes >= ?
            ORDER BY age_minutes DESC
        """.trimIndent()

        val rows = jdbc.query(sql, { rs, _ ->
            StuckOrder(
                id         = rs.getLong("id"),
                version    = rs.getLong("version"),
                ageMinutes = rs.getLong("age_minutes"),
            )
        }, state, warnMinutes)

        if (rows.isEmpty()) return

        rows.forEach { o ->
            val level = if (o.ageMinutes >= critMinutes) "CRITICAL" else "WARN"
            log.warn(
                "[ConsistencyChecker] [$level] Order #{} stuck in {} for {} min (version={})",
                o.id, state, o.ageMinutes, o.version,
            )
        }
        log.warn("[ConsistencyChecker] {} orders stuck in {}: {}", rows.size, state, rows.map { it.id })
    }

    // -----------------------------------------------------------------------
    // 跨服务快照对比（只读，供运维调用）
    // -----------------------------------------------------------------------

    fun findOrderTicketMismatches(): List<OrderTicketMismatch> {
        val sql = """
            SELECT o.id      AS order_id,
                   o.state   AS order_state,
                   t.state   AS ticket_state
            FROM orders o
            JOIN tickets t ON t.id = o.id
            WHERE (
                    o.state = 'APPROVED'   AND t.state = 'CREATE_PENDING'
                OR  o.state = 'CANCELLED'  AND t.state NOT IN ('CANCELLED','PICKED_UP')
            )
            AND TIMESTAMPDIFF(MINUTE, COALESCE(o.updated_time, o.created_time, NOW()), NOW())
                >= ?
        """.trimIndent()

        return jdbc.query(sql, { rs, _ ->
            OrderTicketMismatch(
                orderId     = rs.getLong("order_id"),
                orderState  = rs.getString("order_state"),
                ticketState = rs.getString("ticket_state"),
            )
        }, props.mismatchMinutes)
    }
}

/** 跨服务状态不一致记录 DTO */
data class OrderTicketMismatch(
    val orderId:     Long,
    val orderState:  String,
    val ticketState: String,
)
