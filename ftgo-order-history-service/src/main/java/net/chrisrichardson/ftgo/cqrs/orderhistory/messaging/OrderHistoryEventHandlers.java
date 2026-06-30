package net.chrisrichardson.ftgo.cqrs.orderhistory.messaging;

import io.eventuate.tram.events.subscriber.DomainEventEnvelope;
import io.eventuate.tram.events.subscriber.DomainEventHandlers;
import io.eventuate.tram.events.subscriber.DomainEventHandlersBuilder;
import net.chrisrichardson.ftgo.cqrs.orderhistory.DeliveryPickedUp;
import net.chrisrichardson.ftgo.cqrs.orderhistory.OrderHistoryDao;
import net.chrisrichardson.ftgo.cqrs.orderhistory.dynamodb.Order;
import net.chrisrichardson.ftgo.cqrs.orderhistory.dynamodb.SourceEvent;
import net.chrisrichardson.ftgo.orderservice.api.events.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Subscribes to Order aggregate domain events and projects them into the
 * read-model (DynamoDB).
 *
 * IC-02 additions:
 *  - OrderRevisionProposed → REVISION_PENDING
 *  - OrderRevised          → APPROVED (with updated total)
 *  - OrderCancelPending    → CANCEL_PENDING
 *  - OrderCancelUndone     → APPROVED
 *  - DeliveryPickedUp      → notePickedUp (re-enabled)
 */
public class OrderHistoryEventHandlers {

  private final OrderHistoryDao orderHistoryDao;
  private final Logger logger = LoggerFactory.getLogger(getClass());

  public OrderHistoryEventHandlers(OrderHistoryDao orderHistoryDao) {
    this.orderHistoryDao = orderHistoryDao;
  }

  public DomainEventHandlers domainEventHandlers() {
    return DomainEventHandlersBuilder
            .forAggregateType("net.chrisrichardson.ftgo.orderservice.domain.Order")
            // ── existing handlers ──────────────────────────────────────────
            .onEvent(OrderCreatedEvent.class,      this::handleOrderCreated)
            .onEvent(OrderAuthorized.class,        this::handleOrderAuthorized)
            .onEvent(OrderCancelled.class,         this::handleOrderCancelled)
            .onEvent(OrderRejected.class,          this::handleOrderRejected)
            // ── IC-02: new handlers ────────────────────────────────────────
            .onEvent(OrderCancelPending.class,     this::handleOrderCancelPending)
            .onEvent(OrderCancelUndone.class,      this::handleOrderCancelUndone)
            .onEvent(OrderRevisionProposed.class,  this::handleOrderRevisionProposed)
            .onEvent(OrderRevised.class,           this::handleOrderRevised)
            .onEvent(DeliveryPickedUp.class,       this::handleDeliveryPickedUp)
            .build();
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private Optional<SourceEvent> makeSourceEvent(DomainEventEnvelope<?> dee) {
    return Optional.of(new SourceEvent(
            dee.getAggregateType(), dee.getAggregateId(), dee.getEventId()));
  }

  // ── existing handlers ────────────────────────────────────────────────────

  public void handleOrderCreated(DomainEventEnvelope<OrderCreatedEvent> dee) {
    logger.debug("handleOrderCreated {}", dee);
    boolean result = orderHistoryDao.addOrder(makeOrder(dee.getAggregateId(), dee.getEvent()), makeSourceEvent(dee));
    logger.debug("handleOrderCreated result={} {}", result, dee);
  }

  public void handleOrderAuthorized(DomainEventEnvelope<OrderAuthorized> dee) {
    logger.debug("handleOrderAuthorized {}", dee);
    orderHistoryDao.updateOrderState(dee.getAggregateId(), OrderState.APPROVED, makeSourceEvent(dee));
  }

  public void handleOrderCancelled(DomainEventEnvelope<OrderCancelled> dee) {
    logger.debug("handleOrderCancelled {}", dee);
    orderHistoryDao.updateOrderState(dee.getAggregateId(), OrderState.CANCELLED, makeSourceEvent(dee));
  }

  public void handleOrderRejected(DomainEventEnvelope<OrderRejected> dee) {
    logger.debug("handleOrderRejected {}", dee);
    orderHistoryDao.updateOrderState(dee.getAggregateId(), OrderState.REJECTED, makeSourceEvent(dee));
  }

  // ── IC-02 new handlers ───────────────────────────────────────────────────

  /** IC-02: CancelOrderSaga started — APPROVED → CANCEL_PENDING */
  public void handleOrderCancelPending(DomainEventEnvelope<OrderCancelPending> dee) {
    logger.debug("handleOrderCancelPending {}", dee);
    orderHistoryDao.updateOrderState(dee.getAggregateId(), OrderState.CANCEL_PENDING, makeSourceEvent(dee));
  }

  /** IC-02: CancelOrderSaga compensated — CANCEL_PENDING → APPROVED */
  public void handleOrderCancelUndone(DomainEventEnvelope<OrderCancelUndone> dee) {
    logger.debug("handleOrderCancelUndone {}", dee);
    orderHistoryDao.updateOrderState(dee.getAggregateId(), OrderState.APPROVED, makeSourceEvent(dee));
  }

  /** IC-02: ReviseOrderSaga started — APPROVED → REVISION_PENDING */
  public void handleOrderRevisionProposed(DomainEventEnvelope<OrderRevisionProposed> dee) {
    logger.debug("handleOrderRevisionProposed {}", dee);
    orderHistoryDao.updateOrderState(dee.getAggregateId(), OrderState.REVISION_PENDING, makeSourceEvent(dee));
  }

  /** IC-02: ReviseOrderSaga completed — REVISION_PENDING → APPROVED */
  public void handleOrderRevised(DomainEventEnvelope<OrderRevised> dee) {
    logger.debug("handleOrderRevised {}", dee);
    orderHistoryDao.updateOrderState(dee.getAggregateId(), OrderState.APPROVED, makeSourceEvent(dee));
  }

  /** IC-02: re-enabled; updates pick-up status in read-model */
  public void handleDeliveryPickedUp(DomainEventEnvelope<DeliveryPickedUp> dee) {
    logger.debug("handleDeliveryPickedUp {}", dee);
    orderHistoryDao.notePickedUp(dee.getEvent().getOrderId(), makeSourceEvent(dee));
  }

  // ── private helpers ──────────────────────────────────────────────────────

  private Order makeOrder(String orderId, OrderCreatedEvent event) {
    return new Order(
            orderId,
            Long.toString(event.getOrderDetails().getConsumerId()),
            OrderState.APPROVAL_PENDING,
            event.getOrderDetails().getLineItems(),
            event.getOrderDetails().getOrderTotal(),
            event.getOrderDetails().getRestaurantId(),
            event.getRestaurantName());
  }
}
