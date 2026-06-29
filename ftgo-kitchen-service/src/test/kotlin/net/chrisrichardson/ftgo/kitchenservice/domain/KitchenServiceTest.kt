package net.chrisrichardson.ftgo.kitchenservice.domain

import net.chrisrichardson.ftgo.common.RevisedOrderLineItem
import net.chrisrichardson.ftgo.kitchenservice.api.TicketDetails
import net.chrisrichardson.ftgo.kitchenservice.api.events.TicketAcceptedEvent
import net.chrisrichardson.ftgo.kitchenservice.api.events.TicketCancelled
import net.chrisrichardson.ftgo.kitchenservice.api.events.TicketDomainEvent
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KitchenServiceTest {

    private val restaurantId = 1L
    private val ticketId = 99L
    private val readyBy = LocalDateTime.now().plusHours(1)

    private lateinit var svc: KitchenService
    private lateinit var ticketRepo: TicketRepository
    private lateinit var eventPublisher: TicketDomainEventPublisher

    @Before fun setup() {
        ticketRepo     = mock(TicketRepository::class.java)
        eventPublisher = mock(TicketDomainEventPublisher::class.java)
        svc = KitchenService()
        ReflectionTestUtils.setField(svc, "ticketRepository", ticketRepo)
        ReflectionTestUtils.setField(svc, "domainEventPublisher", eventPublisher)
        ReflectionTestUtils.setField(svc, "restaurantRepository", mock(RestaurantRepository::class.java))
    }

    private fun awaitingTicket(): Ticket =
        Ticket.create(restaurantId, ticketId, TicketDetails()).result
            .also { it.confirmCreate() }

    private fun stub(ticket: Ticket) =
        given(ticketRepo.findById(ticketId)).willReturn(Optional.of(ticket))

    @Test fun shouldAcceptTicket() {
        stub(awaitingTicket())
        svc.accept(ticketId, readyBy)
        verify(eventPublisher).publish(any(Ticket::class.java), any())
    }

    @Test fun shouldConfirmCreateTicket() {
        val ticket = Ticket.create(restaurantId, ticketId, TicketDetails()).result
        stub(ticket)
        svc.confirmCreateTicket(ticketId)
        assertEquals(TicketState.AWAITING_ACCEPTANCE, ticket.state)
        verify(eventPublisher).publish(eq(ticket), any())
    }

    @Test fun shouldCancelTicket() {
        val ticket = awaitingTicket().also { stub(it) }
        svc.cancelTicket(restaurantId, ticketId)
        assertEquals(TicketState.CANCEL_PENDING, ticket.state)
        verify(eventPublisher).publish(eq(ticket), any())
    }

    @Test fun shouldConfirmCancelTicket() {
        val ticket = awaitingTicket().also { it.cancel(); stub(it) }
        svc.confirmCancelTicket(restaurantId, ticketId)
        assertEquals(TicketState.CANCELLED, ticket.state)
        verify(eventPublisher).publish(eq(ticket), any())
    }

    @Test fun shouldUndoCancel() {
        val ticket = awaitingTicket().also { it.cancel(); stub(it) }
        svc.undoCancel(restaurantId, ticketId)
        assertEquals(TicketState.AWAITING_ACCEPTANCE, ticket.state)
    }

    @Test fun shouldBeginReviseOrder() {
        val ticket = awaitingTicket().also { stub(it) }
        val items = listOf(RevisedOrderLineItem(2, "1"))
        svc.beginReviseOrder(restaurantId, ticketId, items)
        assertEquals(TicketState.REVISION_PENDING, ticket.state)
    }

    @Test fun shouldConfirmReviseTicket() {
        val items = listOf(RevisedOrderLineItem(2, "1"))
        val ticket = awaitingTicket().also { it.beginReviseOrder(items); stub(it) }
        svc.confirmReviseTicket(restaurantId, ticketId, items)
        assertEquals(TicketState.AWAITING_ACCEPTANCE, ticket.state)
        verify(eventPublisher).publish(eq(ticket), any())
    }

    @Test fun shouldThrowWhenTicketNotFound() {
        given(ticketRepo.findById(ticketId)).willReturn(Optional.empty())
        assertFailsWith<TicketNotFoundException> { svc.accept(ticketId, readyBy) }
    }
}
