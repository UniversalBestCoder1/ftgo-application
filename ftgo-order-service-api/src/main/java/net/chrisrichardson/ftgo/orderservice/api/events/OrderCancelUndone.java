package net.chrisrichardson.ftgo.orderservice.api.events;

/** IC-01: emitted when CancelOrderSaga is compensated (CANCEL_PENDING → APPROVED). */
public record OrderCancelUndone() implements OrderDomainEvent {}
