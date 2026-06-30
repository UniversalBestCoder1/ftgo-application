package net.chrisrichardson.ftgo.kitchenservice.domain

import net.chrisrichardson.ftgo.common.UnsupportedStateTransitionException
import net.chrisrichardson.ftgo.kitchenservice.api.TicketDetails
import net.chrisrichardson.ftgo.kitchenservice.api.events.TicketAcceptedEvent
import net.chrisrichardson.ftgo.kitchenservice.api.events.TicketCancelled
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.*

/**
 * TEST-01 / TEST-02: Ticket 状态机回归测试
 *
 * 覆盖 BUG-01 (accept() 缺少 state=ACCEPTED) 和 BUG-02 (cancelCreate() 抛 NotYetImplementedException)。
 * 使用项目已配置的 kotlin-test-junit (JUnit 4) 而非 JUnit 5。
 */
class TicketAcceptStateTest {

    private fun createPendingTicket(id: Long = 1L): Ticket {
        // Ticket.create 是 Java 静态方法，必须用位置参数
        return Ticket.create(10L, id, TicketDetails()).result
    }

    // -----------------------------------------------------------------------
    // BUG-01: accept() 必须将状态转换为 ACCEPTED
    // -----------------------------------------------------------------------

    @Test
    fun `accept transitions state to ACCEPTED`() {
        val ticket = createPendingTicket()
        ticket.confirmCreate()

        val readyBy = LocalDateTime.now().plusHours(2)
        val events = ticket.accept(readyBy)

        // 验证事件正确发布
        assertEquals(1, events.size)
        assertTrue(events[0] is TicketAcceptedEvent)

        // ❗ BUG-01 回归：状态必须是 ACCEPTED，不能仍是 AWAITING_ACCEPTANCE
        assertEquals(
            TicketState.ACCEPTED,
            ticket.state,
            "accept() 必须将 state 转换为 ACCEPTED（BUG-01）"
        )
    }

    @Test
    fun `preparing requires ACCEPTED state — would fail if accept bug is not fixed`() {
        val ticket = createPendingTicket()
        ticket.confirmCreate()
        ticket.accept(LocalDateTime.now().plusHours(2))

        // 如果 BUG-01 未修复，state 仍是 AWAITING_ACCEPTANCE，preparing() 会抛异常
        // 修复后应正常执行
        ticket.preparing()
        assertEquals(TicketState.PREPARING, ticket.state)
    }

    @Test
    fun `accept with past readyBy throws IllegalArgumentException`() {
        val ticket = createPendingTicket()
        ticket.confirmCreate()

        val pastReadyBy = LocalDateTime.now().minusHours(1)
        assertFailsWith<IllegalArgumentException> {
            ticket.accept(pastReadyBy)
        }
    }

    @Test
    fun `accept in wrong state throws UnsupportedStateTransitionException`() {
        val ticket = createPendingTicket()
        // 尚未 confirmCreate，仍是 CREATE_PENDING

        assertFailsWith<UnsupportedStateTransitionException> {
            ticket.accept(LocalDateTime.now().plusHours(2))
        }
    }

    // -----------------------------------------------------------------------
    // BUG-01 衍生：previousState 应在 accept 之后正确保存
    // -----------------------------------------------------------------------

    @Test
    fun `cancel after accept should restore to ACCEPTED on undo`() {
        val ticket = createPendingTicket()
        ticket.confirmCreate()
        ticket.accept(LocalDateTime.now().plusHours(2))

        // accept 后 state=ACCEPTED；cancel() 保存 previousState=ACCEPTED
        ticket.cancel()
        assertEquals(TicketState.CANCEL_PENDING, ticket.state)

        // undoCancel() 应回到 ACCEPTED，而非 AWAITING_ACCEPTANCE
        ticket.undoCancel()
        assertEquals(
            TicketState.ACCEPTED,
            ticket.state,
            "undoCancel() 应恢复到 ACCEPTED，BUG-01 存在时会恢复到 AWAITING_ACCEPTANCE"
        )
    }

    // -----------------------------------------------------------------------
    // BUG-02: cancelCreate() 必须正常执行，不抛 NotYetImplementedException
    // -----------------------------------------------------------------------

    @Test
    fun `cancelCreate transitions CREATE_PENDING to CANCELLED`() {
        val ticket = createPendingTicket()
        assertEquals(TicketState.CREATE_PENDING, ticket.state)

        // ❗ BUG-02 回归：不应抛 NotYetImplementedException
        val events = ticket.cancelCreate()

        assertEquals(
            TicketState.CANCELLED,
            ticket.state,
            "cancelCreate() 必须将状态转换为 CANCELLED（BUG-02）"
        )
        assertEquals(1, events.size)
        assertTrue(events[0] is TicketCancelled)
    }

    @Test
    fun `cancelCreate in wrong state throws`() {
        val ticket = createPendingTicket()
        ticket.confirmCreate() // 进入 AWAITING_ACCEPTANCE

        assertFailsWith<UnsupportedStateTransitionException> {
            ticket.cancelCreate()
        }
    }

    // -----------------------------------------------------------------------
    // 完整正常流程（Happy Path）验证状态机完整性
    // -----------------------------------------------------------------------

    @Test
    fun `full happy path from CREATE_PENDING to PICKED_UP`() {
        val ticket = createPendingTicket()

        ticket.confirmCreate()
        assertEquals(TicketState.AWAITING_ACCEPTANCE, ticket.state)

        ticket.accept(LocalDateTime.now().plusHours(1))
        assertEquals(TicketState.ACCEPTED, ticket.state)

        ticket.preparing()
        assertEquals(TicketState.PREPARING, ticket.state)

        ticket.readyForPickup()
        assertEquals(TicketState.READY_FOR_PICKUP, ticket.state)

        ticket.pickedUp()
        assertEquals(TicketState.PICKED_UP, ticket.state)
    }
}
