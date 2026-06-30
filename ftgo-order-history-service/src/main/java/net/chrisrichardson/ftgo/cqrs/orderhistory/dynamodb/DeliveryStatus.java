package net.chrisrichardson.ftgo.cqrs.orderhistory.dynamodb;

public enum DeliveryStatus {
  PREPARING,
  READY_FOR_PICKUP,
  PICKED_UP,
  DELIVERED
}
