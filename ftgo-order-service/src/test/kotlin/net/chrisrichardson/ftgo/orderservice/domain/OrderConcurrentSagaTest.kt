package net.chrisrichardson.ftgo.orderservice.domain

import io.eventuate.tram.events.publisher.DomainEventPublisher
import io.eventuate.tram.sagas.orchestration.SagaInstanceFactory
import net.chrisrichardson.ftgo.common.RevisedOrderLineItem
import net.chrisrichardson.ftgo.orderservice.OrderDetailsMother
import net.chrisrichardson.ftgo.orderservice.RestaurantMother
import net.chrisrichardson.ftgo.orderservice.sagas.cancelorder.CancelOrderSaga
import net.chrisrichardson.ftgo.orderservice.sagas.createorder.CreateOrderSaga
import net.chrisrichardson.ftgo.orderservice.sagas.reviseorder.ReviseOrderSaga
import org.junit.Before
import org.junit.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import java.util.Optional
import kotlin.test.assertFailsWith

/**
 * CC-01 — concurrent Saga protection tests.
 *
 * Unit tests for the business-level guard that prevents two Sagas from
 * operating on the same Order simultaneously.
 *
 * The true concurrent race (two JVM threads both calling cancel() with the
 * same version) is caught by JPA's @Version mechanism at commit time, which
 * raises ObjectOptimisticLockingFailureException.  That path requires a real
 * database and belongs in an integration test.
 *
 * These tests cover:
 *  - Sequential wrong-state calls (observable without a DB)
 *  - That OrderService eagerly transitions state, making the wrong-state path
 *    reachable from cancel() / reviseOrder() (not just from Saga step handlers)
 */
class OrderConcurrentSagaTest {

    private lateinit var svc: OrderService
    private lateinit var orderRepo: OrderRepository
    private lateinit var sagaFactory: SagaInstanceFactory
    private lateinit var eventPublisher: OrderDomainEventPublisher

    @Before fun setup() {
        orderRepo     = mock(OrderRepository::class.java)
        sagaFactory   = mock(SagaInstanceFactory::class.java)
        eventPublisher = mock(OrderDomainEventPublisher::class.java)
        svc = OrderService(
            sagaFactory, orderRepo, mock(DomainEventPublisher::class.java),
            mock(RestaurantRepository::class.java),
            mock(CreateOrderSaga::class.java),
            mock(CancelOrderSaga::class.java),
            mock(ReviseOrderSaga::class.java),
            eventPublisher, Optional.empty()
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun approvedOrder() = Order(
        OrderDetailsMother.CONSUMER_ID,
        RestaurantMother.AJANTA_ID,
        OrderDetailsMother.DELIVERY_INFORMATION,
        OrderDetailsMother.chickenVindalooLineItems()
    ).also {
        it.id = OrderDetailsMother.ORDER_ID
        it.noteApproved()
    }

    private fun stub(order: Order) =
        given(orderRepo.findById(OrderDetailsMother.ORDER_ID)).willReturn(Optional.of(order))

    // ── cancel() guard ───────────────────────────────────────────────────────

    @Test fun `cancel on CANCEL_PENDING order throws OrderNotInRequiredStateException`() {
        // Simulates: first cancel() already transitioned to CANCEL_PENDING
        val order = approvedOrder().also { it.cancel() }   // state = CANCEL_PENDING
        stub(order)

        val ex = assertFailsWith<OrderNotInRequiredStateException> {
            svc.cancel(OrderDetailsMother.ORDER_ID)
        }
        assert(ex.orderId == OrderDetailsMother.ORDER_ID)
        assert(ex.actual.name == "CANCEL_PENDING")
    }

    @Test fun `cancel on CANCELLED order throws OrderNotInRequiredStateException`() {
        val order = approvedOrder().also {
            it.cancel()        // APPROVED → CANCEL_PENDING
            it.noteCancelled() // CANCEL_PENDING → CANCELLED
        }
        stub(order)
        assertFailsWith<OrderNotInRequiredStateException> {
            svc.cancel(OrderDetailsMother.ORDER_ID)
        }
    }

    // ── reviseOrder() guard ──────────────────────────────────────────────────

    @Test fun `reviseOrder on REVISION_PENDING order throws OrderNotInRequiredStateException`() {
        val revision = OrderRevision(Optional.empty(), listOf(RevisedOrderLineItem(3, "1")))
        // Simulates: first reviseOrder() already transitioned to REVISION_PENDING
        val order = approvedOrder().also { it.revise(revision) } // state = REVISION_PENDING
        stub(order)

        val ex = assertFailsWith<OrderNotInRequiredStateException> {
            svc.reviseOrder(OrderDetailsMother.ORDER_ID, revision)
        }
        assert(ex.orderId == OrderDetailsMother.ORDER_ID)
        assert(ex.actual.name == "REVISION_PENDING")
    }

    @Test fun `reviseOrder on CANCEL_PENDING order throws OrderNotInRequiredStateException`() {
        val order = approvedOrder().also { it.cancel() } // CANCEL_PENDING
        stub(order)
        val revision = OrderRevision(Optional.empty(), listOf(RevisedOrderLineItem(2, "1")))
        assertFailsWith<OrderNotInRequiredStateException> {
            svc.reviseOrder(OrderDetailsMother.ORDER_ID, revision)
        }
    }

    // ── Idempotency of domain methods (state already transitioned) ───────────

    @Test fun `Order cancel() is idempotent when state is already CANCEL_PENDING`() {
        val order = approvedOrder()
        val events1 = order.cancel()          // APPROVED → CANCEL_PENDING, returns [OrderCancelPending]
        assert(events1.isNotEmpty())

        val events2 = order.cancel()          // CANCEL_PENDING → no-op, returns []
        assert(events2.isEmpty()) { "Expected empty events for idempotent cancel(), got: $events2" }
        assert(order.state.name == "CANCEL_PENDING")
    }

    @Test fun `Order revise() is idempotent when state is already REVISION_PENDING`() {
        val order = approvedOrder()
        val revision = OrderRevision(Optional.empty(), listOf(RevisedOrderLineItem(3, "1")))

        val result1 = order.revise(revision)  // APPROVED → REVISION_PENDING, emits event
        assert(result1.events.isNotEmpty())

        val result2 = order.revise(revision)  // REVISION_PENDING → no-op, returns change only
        assert(result2.events.isEmpty()) { "Expected empty events for idempotent revise(), got: ${result2.events}" }
        assert(order.state.name == "REVISION_PENDING")
    }
}
