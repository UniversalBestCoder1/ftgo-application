package net.chrisrichardson.ftgo.orderservice.domain;


import io.eventuate.tram.events.common.DomainEvent;

public class OrderRevisionRejected implements DomainEvent {

  private OrderRevision orderRevision;

  private OrderRevisionRejected() {
  }

  public OrderRevisionRejected(OrderRevision orderRevision) {
    this.orderRevision = orderRevision;
  }

  public OrderRevision getOrderRevision() {
    return orderRevision;
  }

}
