package net.chrisrichardson.ftgo.orderservice.api.events;

import net.chrisrichardson.ftgo.common.Address;
import net.chrisrichardson.ftgo.common.Money;

import java.util.Optional;

/**
 * IC-01: published when a ReviseOrderSaga begins (APPROVED → REVISION_PENDING).
 *
 * Carries only the data downstream consumers actually need — not the full
 * internal OrderRevision domain object — so this class can live in the API
 * module without pulling in JPA / domain dependencies.
 */
public class OrderRevisionProposed implements OrderDomainEvent {

    private Money currentOrderTotal;
    private Money newOrderTotal;
    /** New delivery address when the revision changes it; null otherwise. */
    private Address newDeliveryAddress;

    /** Jackson no-arg constructor */
    private OrderRevisionProposed() {}

    public OrderRevisionProposed(Money currentOrderTotal, Money newOrderTotal, Address newDeliveryAddress) {
        this.currentOrderTotal   = currentOrderTotal;
        this.newOrderTotal       = newOrderTotal;
        this.newDeliveryAddress  = newDeliveryAddress;
    }

    public Money getCurrentOrderTotal()  { return currentOrderTotal; }
    public Money getNewOrderTotal()      { return newOrderTotal; }
    /** May be null when the revision does not change the delivery address. */
    public Address getNewDeliveryAddress() { return newDeliveryAddress; }
    public Optional<Address> newDeliveryAddressOpt() { return Optional.ofNullable(newDeliveryAddress); }
}
