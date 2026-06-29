package net.chrisrichardson.ftgo.orderservice.domain

import net.chrisrichardson.ftgo.orderservice.OrderDetailsMother
import net.chrisrichardson.ftgo.orderservice.RestaurantMother
import net.chrisrichardson.ftgo.orderservice.api.events.OrderCancelled
import net.chrisrichardson.ftgo.orderservice.api.events.OrderRejected
import net.chrisrichardson.ftgo.orderservice.api.events.OrderState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrderStateTransitionTest {

    private fun approvedOrder(): Order {
        val result = Order.createOrder(
            OrderDetailsMother.CONSUMER_ID,
            RestaurantMother.AJANTA_RESTAURANT,
            OrderDetailsMother.DELIVERY_INFORMATION,
            OrderDetailsMother.chickenVindalooLineItems()
        )
        result.result.noteApproved()
        return result.result
    }

    @Test
    fun shouldRejectOrder() {
        val result = Order.createOrder(
            OrderDetailsMother.CONSUMER_ID,
            RestaurantMother.AJANTA_RESTAURANT,
            OrderDetailsMother.DELIVERY_INFORMATION,
            OrderDetailsMother.chickenVindalooLineItems()
        )
        val events = result.result.noteRejected()
        assertEquals(listOf(OrderRejected()), events)
        assertEquals(OrderState.REJECTED, result.result.state)
    }

    @Test
    fun shouldBeginCancelFromApproved() {
        val order = approvedOrder()
        val events = order.cancel()
        assertEquals(emptyList(), events)
        assertEquals(OrderState.CANCEL_PENDING, order.state)
    }

    @Test
    fun shouldConfirmCancel() {
        val order = approvedOrder()
        order.cancel()
        val events = order.noteCancelled()
        assertEquals(listOf(OrderCancelled()), events)
        assertEquals(OrderState.CANCELLED, order.state)
    }

    @Test
    fun shouldUndoPendingCancel() {
        val order = approvedOrder()
        order.cancel()
        val events = order.undoPendingCancel()
        assertEquals(emptyList(), events)
        assertEquals(OrderState.APPROVED, order.state)
    }

    @Test
    fun shouldRejectCancelFromApprovalPending() {
        val order = Order.createOrder(
            OrderDetailsMother.CONSUMER_ID,
            RestaurantMother.AJANTA_RESTAURANT,
            OrderDetailsMother.DELIVERY_INFORMATION,
            OrderDetailsMother.chickenVindalooLineItems()
        ).result
        assertFailsWith<Exception> { order.cancel() }
    }
}
