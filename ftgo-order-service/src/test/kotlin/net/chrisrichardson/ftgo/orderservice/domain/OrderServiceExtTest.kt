package net.chrisrichardson.ftgo.orderservice.domain

import io.eventuate.tram.events.publisher.DomainEventPublisher
import io.eventuate.tram.sagas.orchestration.SagaInstanceFactory
import net.chrisrichardson.ftgo.common.RevisedOrderLineItem
import net.chrisrichardson.ftgo.orderservice.OrderDetailsMother
import net.chrisrichardson.ftgo.orderservice.RestaurantMother
import net.chrisrichardson.ftgo.orderservice.sagas.cancelorder.CancelOrderSaga
import net.chrisrichardson.ftgo.orderservice.sagas.cancelorder.CancelOrderSagaData
import net.chrisrichardson.ftgo.orderservice.sagas.createorder.CreateOrderSaga
import net.chrisrichardson.ftgo.orderservice.sagas.reviseorder.ReviseOrderSaga
import net.chrisrichardson.ftgo.orderservice.sagas.reviseorder.ReviseOrderSagaData
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.util.Optional
import kotlin.test.assertFailsWith

class OrderServiceExtTest {

    private lateinit var svc: OrderService
    private lateinit var orderRepo: OrderRepository
    private lateinit var sagaFactory: SagaInstanceFactory
    private lateinit var cancelSaga: CancelOrderSaga
    private lateinit var reviseSaga: ReviseOrderSaga
    private lateinit var eventPublisher: OrderDomainEventPublisher

    @Before fun setup() {
        orderRepo    = mock(OrderRepository::class.java)
        sagaFactory  = mock(SagaInstanceFactory::class.java)
        cancelSaga   = mock(CancelOrderSaga::class.java)
        reviseSaga   = mock(ReviseOrderSaga::class.java)
        eventPublisher = mock(OrderDomainEventPublisher::class.java)
        svc = OrderService(sagaFactory, orderRepo, mock(DomainEventPublisher::class.java),
            mock(RestaurantRepository::class.java), mock(CreateOrderSaga::class.java),
            cancelSaga, reviseSaga, eventPublisher, Optional.empty())
    }

    private fun pendingOrder() = Order(OrderDetailsMother.CONSUMER_ID, RestaurantMother.AJANTA_ID,
        OrderDetailsMother.DELIVERY_INFORMATION, OrderDetailsMother.chickenVindalooLineItems())
        .also { it.id = OrderDetailsMother.ORDER_ID }

    private fun approvedOrder() = pendingOrder().also { it.noteApproved() }

    private fun stubOrder(order: Order) =
        given(orderRepo.findById(OrderDetailsMother.ORDER_ID)).willReturn(Optional.of(order))

    @Test fun shouldCancelOrder() {
        stubOrder(approvedOrder())
        svc.cancel(OrderDetailsMother.ORDER_ID)
        verify(sagaFactory).create(eq(cancelSaga), any(CancelOrderSagaData::class.java))
    }

    @Test fun shouldApproveOrder() {
        val order = pendingOrder().also { stubOrder(it) }
        svc.approveOrder(OrderDetailsMother.ORDER_ID)
        verify(eventPublisher).publish(eq(order), any())
    }

    @Test fun shouldRejectOrder() {
        val order = pendingOrder().also { stubOrder(it) }
        svc.rejectOrder(OrderDetailsMother.ORDER_ID)
        verify(eventPublisher).publish(eq(order), any())
    }

    @Test fun shouldBeginCancel() {
        val order = approvedOrder().also { stubOrder(it) }
        svc.beginCancel(OrderDetailsMother.ORDER_ID)
        verify(eventPublisher).publish(eq(order), any())
    }

    @Test fun shouldConfirmCancelled() {
        val order = approvedOrder().also { it.cancel(); stubOrder(it) }
        svc.confirmCancelled(OrderDetailsMother.ORDER_ID)
        verify(eventPublisher).publish(eq(order), any())
    }

    @Test fun shouldReviseOrder() {
        stubOrder(approvedOrder())
        val revision = OrderRevision(Optional.empty(), listOf(RevisedOrderLineItem(3, "1")))
        svc.reviseOrder(OrderDetailsMother.ORDER_ID, revision)
        verify(sagaFactory).create(eq(reviseSaga), any(ReviseOrderSagaData::class.java))
    }

    @Test fun shouldThrowWhenOrderNotFoundOnCancel() {
        given(orderRepo.findById(OrderDetailsMother.ORDER_ID)).willReturn(Optional.empty())
        assertFailsWith<OrderNotFoundException> { svc.cancel(OrderDetailsMother.ORDER_ID) }
    }

    // ── CC-01: state-guard tests ────────────────────────────────────────────

    @Test fun `cancel should publish event and create saga for approved order`() {
        stubOrder(approvedOrder())
        svc.cancel(OrderDetailsMother.ORDER_ID)
        verify(eventPublisher).publish(any(Order::class.java), any())
        verify(sagaFactory).create(eq(cancelSaga), any(CancelOrderSagaData::class.java))
    }

    @Test fun `cancel should throw OrderNotInRequiredStateException when order is not APPROVED`() {
        // Simulate order already in CANCEL_PENDING (e.g. concurrent cancel)
        val order = approvedOrder().also { it.cancel() /* APPROVED → CANCEL_PENDING */ }
        stubOrder(order)
        assertFailsWith<OrderNotInRequiredStateException> {
            svc.cancel(OrderDetailsMother.ORDER_ID)
        }
    }

    @Test fun `reviseOrder should throw OrderNotInRequiredStateException when order is not APPROVED`() {
        val order = approvedOrder().also { it.cancel() /* APPROVED → CANCEL_PENDING */ }
        stubOrder(order)
        val revision = OrderRevision(Optional.empty(), listOf(RevisedOrderLineItem(3, "1")))
        assertFailsWith<OrderNotInRequiredStateException> {
            svc.reviseOrder(OrderDetailsMother.ORDER_ID, revision)
        }
    }

    @Test fun `reviseOrder should throw when order not found`() {
        given(orderRepo.findById(OrderDetailsMother.ORDER_ID)).willReturn(Optional.empty())
        val revision = OrderRevision(Optional.empty(), listOf(RevisedOrderLineItem(3, "1")))
        assertFailsWith<OrderNotFoundException> {
            svc.reviseOrder(OrderDetailsMother.ORDER_ID, revision)
        }
    }
}
