package net.chrisrichardson.ftgo.orderservice.api.events;

/** IC-01: emitted when a CancelOrderSaga begins (APPROVED → CANCEL_PENDING). */
public record OrderCancelPending() implements OrderDomainEvent {}
