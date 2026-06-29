package net.chrisrichardson.ftgo.orderservice.api.web;

import net.chrisrichardson.ftgo.common.RevisedOrderLineItem;

import java.util.List;

public record ReviseOrderRequest(List<RevisedOrderLineItem> revisedOrderLineItems) {}
