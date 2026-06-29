package net.chrisrichardson.ftgo.kitchenservice.domain

import net.chrisrichardson.ftgo.common.RevisedOrderLineItem
import net.chrisrichardson.ftgo.kitchenservice.api.TicketDetails
import net.chrisrichardson.ftgo.kitchenservice.api.events.TicketAcceptedEvent
import net.chrisrichardson.ftgo.kitchenservice.api.events.TicketCancelled
import net.chrisrichardson.ftgo.kitchenservice.api.events.TicketDomainEvent
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TicketAggregateTest {

    private val restaurantId = 1L
    private val ticketId = 99L

    private fun createAndConfirmTicket(): Ticket {
        val ticket = Ticket.create(restaurantId, ticketId, TicketDetails()).result
        ticket.confirmCreate()
        return ticket
    }

    @Test
    fun shouldConfirmCreate() {
        val ticket = Ticket.create(restaurantId, ticketId, TicketDetails()).result
        val events = ticket.confirmCreate()
        assertTrue(events.any { it is TicketCreatedEvent })
        assertEquals(TicketState.AWAITING_ACCEPTANCE, ticket.state)
    }

    @Test
    fun shouldAcceptTicket() {
        val ticket = createAndConfirmTicket()
        val readyBy = LocalDateTime.now().plusHours(1)
        val events: List<TicketDomainEvent> = ticket.accept(readyBy)
        assertEquals(1, events.size)
        assertTrue(events[0] is TicketAcceptedEvent)
    }

    @Test
    fun shouldRejectAcceptWithPastReadyBy() {
        val ticket = createAndConfirmTicket()
        assertFailsWith<IllegalArgumentException> {
            ticket.accept(LocalDateTime.now().minusMinutes(1))
        }
    }

    @Test
    fun shouldCancelTicketFromAwaitingAcceptance() {
        val ticket = createAndConfirmTicket()
        val events = ticket.cancel()
        assertEquals(emptyList(), events)
        assertEquals(TicketState.CANCEL_PENDING, ticket.state)
    }

    @Test
    fun shouldConfirmCancel() {
        val ticket = createAndConfirmTicket()
        ticket.cancel()
        val events = ticket.confirmCancel()
        assertEquals(1, events.size)
        assertTrue(events[0] is TicketCancelled)
        assertEquals(TicketState.CANCELLED, ticket.state)
    }

    @Test
    fun shouldUndoCancel() {
        val ticket = createAndConfirmTicket()
        ticket.cancel()
        ticket.undoCancel()
        assertEquals(TicketState.AWAITING_ACCEPTANCE, ticket.state)
    }

    @Test
    fun shouldBeginReviseOrder() {
        val ticket = createAndConfirmTicket()
        val events = ticket.beginReviseOrder(listOf(RevisedOrderLineItem(2, "1")))
        assertEquals(emptyList(), events)
        assertEquals(TicketState.REVISION_PENDING, ticket.state)
    }

    @Test
    fun shouldConfirmReviseTicket() {
        val ticket = createAndConfirmTicket()
        ticket.beginReviseOrder(listOf(RevisedOrderLineItem(2, "1")))
        val events = ticket.confirmReviseTicket(listOf(RevisedOrderLineItem(2, "1")))
        assertTrue(events.any { it is TicketRevised })
        assertEquals(TicketState.AWAITING_ACCEPTANCE, ticket.state)
    }

    @Test
    fun shouldUndoBeginRevise() {
        val ticket = createAndConfirmTicket()
        ticket.beginReviseOrder(listOf(RevisedOrderLineItem(2, "1")))
        ticket.undoBeginReviseOrder()
        assertEquals(TicketState.AWAITING_ACCEPTANCE, ticket.state)
    }
}
