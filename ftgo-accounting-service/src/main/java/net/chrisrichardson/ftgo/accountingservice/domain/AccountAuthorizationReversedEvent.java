package net.chrisrichardson.ftgo.accountingservice.domain;

import io.eventuate.Event;

/**
 * IC-04: published when an account authorization is reversed
 * (e.g. during CancelOrderSaga compensation).
 *
 * The event currently has no downstream subscriber, but its existence
 * makes the reversal visible in the Eventuate event log and allows
 * future consumers (audit, fraud-detection) to react without code changes.
 */
public class AccountAuthorizationReversedEvent implements Event {

    private String orderId;
    private String consumerId;

    /** Jackson no-arg constructor */
    private AccountAuthorizationReversedEvent() {}

    public AccountAuthorizationReversedEvent(String consumerId, String orderId) {
        this.consumerId = consumerId;
        this.orderId    = orderId;
    }

    public String getOrderId()    { return orderId; }
    public String getConsumerId() { return consumerId; }
}
