package net.chrisrichardson.ftgo.accountingservice.domain;

import io.eventuate.Event;
import net.chrisrichardson.ftgo.common.Money;

/**
 * CC-03: published when an account authorization is revised
 * (during ReviseOrderSaga — amount adjusted to reflect new order total).
 *
 * <p>Emitting this event makes the revision visible in the Eventuate event log
 * and acts as an idempotency marker: re-delivery of the same
 * {@code ReviseAuthorizationCommandInternal} will be deduplicated by the
 * framework-level {@code received_messages} table, so the aggregate's
 * {@code process()} is invoked at most once per command delivery attempt.
 * Downstream consumers (audit, fraud-detection) can subscribe without
 * further code changes.
 */
public class AccountAuthorizationRevisedEvent implements Event {

    private String consumerId;
    private String orderId;
    private Money  revisedOrderTotal;

    /** Jackson no-arg constructor */
    private AccountAuthorizationRevisedEvent() {}

    public AccountAuthorizationRevisedEvent(String consumerId,
                                             String orderId,
                                             Money  revisedOrderTotal) {
        this.consumerId        = consumerId;
        this.orderId           = orderId;
        this.revisedOrderTotal = revisedOrderTotal;
    }

    public String getConsumerId()        { return consumerId; }
    public String getOrderId()           { return orderId; }
    public Money  getRevisedOrderTotal() { return revisedOrderTotal; }
}
