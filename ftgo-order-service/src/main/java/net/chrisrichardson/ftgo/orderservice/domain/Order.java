package net.chrisrichardson.ftgo.orderservice.domain;

import io.eventuate.tram.events.aggregates.ResultWithDomainEvents;
import net.chrisrichardson.ftgo.common.Address;
import net.chrisrichardson.ftgo.common.Money;
import net.chrisrichardson.ftgo.common.UnsupportedStateTransitionException;
import net.chrisrichardson.ftgo.orderservice.api.events.*;

import jakarta.persistence.*;
import java.util.List;

import static net.chrisrichardson.ftgo.orderservice.api.events.OrderState.APPROVED;
import static net.chrisrichardson.ftgo.orderservice.api.events.OrderState.APPROVAL_PENDING;
import static net.chrisrichardson.ftgo.orderservice.api.events.OrderState.REJECTED;
import static net.chrisrichardson.ftgo.orderservice.api.events.OrderState.REVISION_PENDING;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

@Entity
@Table(name = "orders")
@Access(AccessType.FIELD)
public class Order {

  public static ResultWithDomainEvents<Order, OrderDomainEvent>
  createOrder(long consumerId, Restaurant restaurant, DeliveryInformation deliveryInformation, List<OrderLineItem> orderLineItems) {
    Order order = new Order(consumerId, restaurant.getId(), deliveryInformation, orderLineItems);
    List<OrderDomainEvent> events = singletonList(new OrderCreatedEvent(
            new OrderDetails(consumerId, restaurant.getId(), orderLineItems,
                    order.getOrderTotal()),
            deliveryInformation.getDeliveryAddress(),
            restaurant.getName()));
    return new ResultWithDomainEvents<>(order, events);
  }

  @Id
  @GeneratedValue
  private Long id;

  @Version
  private Long version;

  @Enumerated(EnumType.STRING)
  private OrderState state;

  private Long consumerId;
  private Long restaurantId;

  @Embedded
  private OrderLineItems orderLineItems;

  @Embedded
  private DeliveryInformation deliveryInformation;

  @Embedded
  private PaymentInformation paymentInformation;

  @Embedded
  private Money orderMinimum = new Money(Integer.MAX_VALUE);

  private Order() {
  }

  public Order(long consumerId, long restaurantId, DeliveryInformation deliveryInformation, List<OrderLineItem> orderLineItems) {
    this.consumerId = consumerId;
    this.restaurantId = restaurantId;
    this.deliveryInformation = deliveryInformation;
    this.orderLineItems = new OrderLineItems(orderLineItems);
    this.state = APPROVAL_PENDING;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public DeliveryInformation getDeliveryInformation() {
    return deliveryInformation;
  }

  public Money getOrderTotal() {
    return orderLineItems.orderTotal();
  }

  public List<OrderDomainEvent> cancel() {
    return switch (state) {
      // IC-01: emit OrderCancelPending so downstream (OrderHistory) tracks this transition
      case APPROVED -> { this.state = OrderState.CANCEL_PENDING; yield singletonList(new OrderCancelPending()); }
      // CC-01: idempotent — the eager state transition in OrderService.cancel() already moved
      //        us to CANCEL_PENDING; the BeginCancelCommand Saga step finds us here and is a no-op.
      case CANCEL_PENDING -> emptyList();
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public List<OrderDomainEvent> undoPendingCancel() {
    return switch (state) {
      // IC-01: emit OrderCancelUndone for CancelOrderSaga compensation visibility
      case CANCEL_PENDING -> { this.state = OrderState.APPROVED; yield singletonList(new OrderCancelUndone()); }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public List<OrderDomainEvent> noteCancelled() {
    return switch (state) {
      case CANCEL_PENDING -> { this.state = OrderState.CANCELLED; yield singletonList(new OrderCancelled()); }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public List<OrderDomainEvent> noteApproved() {
    return switch (state) {
      case APPROVAL_PENDING -> { this.state = APPROVED; yield singletonList(new OrderAuthorized()); }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public List<OrderDomainEvent> noteRejected() {
    return switch (state) {
      case APPROVAL_PENDING -> { this.state = REJECTED; yield singletonList(new OrderRejected()); }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }


  public List<OrderDomainEvent> noteReversingAuthorization() {
    // Compensation step: authorization is being reversed, no state change needed
    // (order will subsequently be rejected via noteRejected)
    return emptyList();
  }

  public ResultWithDomainEvents<LineItemQuantityChange, OrderDomainEvent> revise(OrderRevision orderRevision) {
    return switch (state) {
      case APPROVED -> {
        LineItemQuantityChange change = orderLineItems.lineItemQuantityChange(orderRevision);
        if (change.newOrderTotal.isGreaterThanOrEqual(orderMinimum)) throw new OrderMinimumNotMetException();
        this.state = REVISION_PENDING;
        // IC-01: emit API-module event so OrderHistoryService / DeliveryService can consume it
        Address newAddr = orderRevision.getDeliveryInformation()
                .map(DeliveryInformation::getDeliveryAddress).orElse(null);
        yield new ResultWithDomainEvents<>(change,
                singletonList(new OrderRevisionProposed(change.currentOrderTotal, change.newOrderTotal, newAddr)));
      }
      // CC-01: idempotent — OrderService.reviseOrder() already transitioned to REVISION_PENDING;
      //        the BeginReviseOrderCommand Saga step computes the price change without re-emitting events.
      case REVISION_PENDING -> {
        LineItemQuantityChange change = orderLineItems.lineItemQuantityChange(orderRevision);
        yield new ResultWithDomainEvents<>(change, emptyList());
      }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public List<OrderDomainEvent> rejectRevision() {
    return switch (state) {
      // IC-01: keep emptyList for now — no active subscriber yet
      case REVISION_PENDING -> { this.state = APPROVED; yield emptyList(); }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public List<OrderDomainEvent> confirmRevision(OrderRevision orderRevision) {
    return switch (state) {
      case REVISION_PENDING -> {
        LineItemQuantityChange licd = orderLineItems.lineItemQuantityChange(orderRevision);
        orderRevision.getDeliveryInformation().ifPresent(newDi -> this.deliveryInformation = newDi);
        if (orderRevision.getRevisedOrderLineItems() != null && !orderRevision.getRevisedOrderLineItems().isEmpty()) {
          orderLineItems.updateLineItems(orderRevision);
        }
        this.state = APPROVED;
        // IC-01: emit API-module event with denormalised new address
        Address newAddr = orderRevision.getDeliveryInformation()
                .map(DeliveryInformation::getDeliveryAddress).orElse(null);
        yield singletonList(new OrderRevised(licd.currentOrderTotal, licd.newOrderTotal, newAddr));
      }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }


  public Long getVersion() {
    return version;
  }

  public List<OrderLineItem> getLineItems() {
    return orderLineItems.getLineItems();
  }

  public OrderState getState() {
    return state;
  }

  public long getRestaurantId() {
    return restaurantId;
  }


  public Long getConsumerId() {
    return consumerId;
  }
}

