package net.chrisrichardson.ftgo.orderservice.domain;

import net.chrisrichardson.ftgo.orderservice.api.events.OrderState;

/**
 * Thrown when an operation requires the Order to be in a specific state
 * but the order is in a different state.
 *
 * <p>Used by cancel() and reviseOrder() to provide a clean 409-style error
 * before the Saga is even created.  The @Version field on Order handles the
 * true concurrent race; this exception handles the sequential wrong-state case.
 */
public class OrderNotInRequiredStateException extends RuntimeException {

    private final Long orderId;
    private final OrderState required;
    private final OrderState actual;

    public OrderNotInRequiredStateException(Long orderId, OrderState required, OrderState actual) {
        super(String.format("Order %d must be in state %s but is in state %s", orderId, required, actual));
        this.orderId = orderId;
        this.required = required;
        this.actual = actual;
    }

    public Long getOrderId()     { return orderId; }
    public OrderState getRequired() { return required; }
    public OrderState getActual()   { return actual; }
}
