package net.chrisrichardson.ftgo.orderservice.api.events;

import net.chrisrichardson.ftgo.common.Address;
import net.chrisrichardson.ftgo.common.Money;

import java.util.Optional;

/**
 * IC-01: published when a ReviseOrderSaga completes (REVISION_PENDING → APPROVED).
 *
 * Contains denormalised totals and optional new delivery address so that
 * downstream services (OrderHistoryService, DeliveryService) do not need the
 * full internal OrderRevision domain object.
 */
public class OrderRevised implements OrderDomainEvent {

    private Money currentOrderTotal;
    private Money newOrderTotal;
    /** Non-null only when the revision includes a new delivery address. */
    private Address newDeliveryAddress;

    /** Jackson no-arg constructor */
    private OrderRevised() {}

    public OrderRevised(Money currentOrderTotal, Money newOrderTotal, Address newDeliveryAddress) {
        this.currentOrderTotal  = currentOrderTotal;
        this.newOrderTotal      = newOrderTotal;
        this.newDeliveryAddress = newDeliveryAddress;
    }

    public Money getCurrentOrderTotal()    { return currentOrderTotal; }
    public Money getNewOrderTotal()        { return newOrderTotal; }
    /** May be null when the revision did not change the delivery address. */
    public Address getNewDeliveryAddress() { return newDeliveryAddress; }
    public Optional<Address> newDeliveryAddressOpt() { return Optional.ofNullable(newDeliveryAddress); }
}
