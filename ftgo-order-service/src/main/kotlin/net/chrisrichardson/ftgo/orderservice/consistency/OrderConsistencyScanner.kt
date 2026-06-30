package net.chrisrichardson.ftgo.orderservice.consistency

import net.chrisrichardson.ftgo.orderservice.api.events.OrderState
import net.chrisrichardson.ftgo.orderservice.domain.OrderRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * OPS-05: 数据一致性周期扫描（运维工具）。
 *
 * 与 OrderConsistencyChecker（实时告警）不同，该扫描器执行更重的跨服务 SQL 查询，
 * 默认每小时运行一次，结果写入 consistency_scan_results 表供运维查阅。
 *
 * 启用方式：application.properties 中设置 ftgo.consistency.scanner.enabled=true
 */
@Component
class OrderConsistencyScanner(
    private val jdbc: JdbcTemplate,
    private val orderRepository: OrderRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // -----------------------------------------------------------------------
    // 1. Order-Ticket 不一致扫描
    // -----------------------------------------------------------------------

    @Scheduled(cron = "\${ftgo.consistency.scanner.cron:0 0 * * * *}")  // 默认每小时整点
    @Transactional(readOnly = true)
    fun scanOrderTicketConsistency() {
        log.info("[ConsistencyScanner] 开始 Order-Ticket 一致性扫描...")

        val mismatches = findOrderTicketMismatches()
        if (mismatches.isEmpty()) {
            log.info("[ConsistencyScanner] Order-Ticket 一致性：正常，无异常记录")
            return
        }

        log.warn("[ConsistencyScanner] 发现 {} 条 Order-Ticket 状态不一致记录:", mismatches.size)
        mismatches.forEach { m ->
            log.warn(
                "  - Order#{}: order.state={} ticket.state={} createdAt={}",
                m.orderId, m.orderState, m.ticketState,
            )
        }

        persistScanResult("ORDER_TICKET", mismatches.size, buildSummary(mismatches))
    }

    // -----------------------------------------------------------------------
    // 2. Order-Delivery 不一致扫描
    // -----------------------------------------------------------------------

    @Scheduled(cron = "\${ftgo.consistency.scanner.cron:0 0 * * * *}")
    @Transactional(readOnly = true)
    fun scanOrderDeliveryConsistency() {
        log.info("[ConsistencyScanner] 开始 Order-Delivery 一致性扫描...")

        val sql = """
            SELECT o.id     AS order_id,
                   o.state  AS order_state,
                   d.state  AS delivery_state,
                   o.created_time
            FROM orders o
            JOIN deliveries d ON d.id = o.id
            WHERE (
                    o.state = 'CANCELLED' AND d.state != 'CANCELLED'
                OR  o.state = 'APPROVED'  AND d.state IS NULL
            )
            AND TIMESTAMPDIFF(MINUTE, o.created_time, NOW()) > 30
        """.trimIndent()

        val mismatches = jdbc.query(sql) { rs, _ ->
            mapOf(
                "orderId"       to rs.getLong("order_id"),
                "orderState"    to rs.getString("order_state"),
                "deliveryState" to (rs.getString("delivery_state") ?: "NULL"),
                "createdTime"   to rs.getTimestamp("created_time").toLocalDateTime(),
            )
        }

        if (mismatches.isEmpty()) {
            log.info("[ConsistencyScanner] Order-Delivery 一致性：正常")
            return
        }

        log.warn("[ConsistencyScanner] 发现 {} 条 Order-Delivery 状态不一致记录:", mismatches.size)
        mismatches.forEach { m ->
            log.warn(
                "  - Order#{}: order.state={} delivery.state={} createdAt={}",
                m["orderId"], m["orderState"], m["deliveryState"], m["createdTime"],
            )
        }

        persistScanResult("ORDER_DELIVERY", mismatches.size, mismatches.toString())
    }

    // -----------------------------------------------------------------------
    // 3. 卡死 Saga 扫描（eventuate_tram DB 中的 saga_instance 表）
    // -----------------------------------------------------------------------

    @Scheduled(cron = "\${ftgo.consistency.scanner.cron:0 0 * * * *}")
    fun scanStuckSagas() {
        log.info("[ConsistencyScanner] 开始卡死 Saga 扫描...")

        // 注意：saga_instance 表由 Eventuate Tram 框架管理，需确保 JDBC DataSource 可访问
        val sql = """
            SELECT saga_type, saga_id, state_name, last_request_id
            FROM saga_instance
            WHERE state_name NOT IN ('Complete', 'Failed')
              AND TIMESTAMPDIFF(MINUTE, last_request_time, NOW()) > 30
        """.trimIndent()

        try {
            val stuckSagas = jdbc.query(sql) { rs, _ ->
                StuckSaga(
                    sagaType      = rs.getString("saga_type"),
                    sagaId        = rs.getString("saga_id"),
                    stateName     = rs.getString("state_name"),
                    lastRequestId = rs.getString("last_request_id"),
                )
            }

            if (stuckSagas.isEmpty()) {
                log.info("[ConsistencyScanner] 卡死 Saga：无异常")
                return
            }

            log.warn("[ConsistencyScanner] 发现 {} 个卡死 Saga:", stuckSagas.size)
            stuckSagas.forEach { s ->
                log.warn(
                    "  - [{}] sagaId={} state= lastRequest={}",
                    s.sagaType.substringAfterLast('.'), s.sagaId, s.stateName, s.lastRequestId,
                )
            }

            persistScanResult("STUCK_SAGA", stuckSagas.size, stuckSagas.toString())
        } catch (ex: Exception) {
            log.error("[ConsistencyScanner] 无法访问 saga_instance 表: {}", ex.message)
        }
    }

    // -----------------------------------------------------------------------
    // 内部工具
    // -----------------------------------------------------------------------

    private fun findOrderTicketMismatches(): List<OrderTicketMismatch> {
        val sql = """
            SELECT o.id      AS order_id,
                   o.state   AS order_state,
                   t.state   AS ticket_state,
                   o.created_time
            FROM orders o
            JOIN tickets t ON t.id = o.id
            WHERE (
                    o.state = 'APPROVED'   AND t.state = 'CREATE_PENDING'
                OR  o.state = 'CANCELLED'  AND t.state NOT IN ('CANCELLED','PICKED_UP')
            )
            AND TIMESTAMPDIFF(MINUTE, o.created_time, NOW()) > 60
        """.trimIndent()

        return jdbc.query(sql) { rs, _ ->
            OrderTicketMismatch(
                orderId     = rs.getLong("order_id"),
                orderState  = rs.getString("order_state"),
                ticketState = rs.getString("ticket_state"),
            )
        }
    }

    /**
     * 将扫描结果持久化到 consistency_scan_results 表（需手动创建该表）。
     *
     * DDL:
     * ```sql
     * CREATE TABLE consistency_scan_results (
     *   id          BIGINT AUTO_INCREMENT PRIMARY KEY,
     *   scan_type   VARCHAR(64)  NOT NULL,
     *   issue_count INT          NOT NULL,
     *   summary     TEXT,
     *   scanned_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
     * );
     * ```
     */
    private fun persistScanResult(scanType: String, issueCount: Int, summary: String) {
        try {
            jdbc.update(
                "INSERT INTO consistency_scan_results (scan_type, issue_count, summary) VALUES (?, ?, ?)",
                scanType, issueCount, summary.take(4000),
            )
        } catch (ex: Exception) {
            log.warn("[ConsistencyScanner] 无法写入 consistency_scan_results：{}", ex.message)
        }
    }

    private fun buildSummary(mismatches: List<OrderTicketMismatch>): String =
        mismatches.joinToString("\n") { "Order#${it.orderId}: ${it.orderState} vs Ticket:${it.ticketState}" }
}

/** 卡死 Saga 记录 DTO */
data class StuckSaga(
    val sagaType:      String,
    val sagaId:        String,
    val stateName:     String,
    val lastRequestId: String?,
)
