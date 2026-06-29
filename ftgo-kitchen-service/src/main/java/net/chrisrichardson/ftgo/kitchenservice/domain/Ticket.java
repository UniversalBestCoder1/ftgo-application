package net.chrisrichardson.ftgo.kitchenservice.domain;

import io.eventuate.tram.events.aggregates.ResultWithDomainEvents;
import net.chrisrichardson.ftgo.common.NotYetImplementedException;
import net.chrisrichardson.ftgo.common.RevisedOrderLineItem;
import net.chrisrichardson.ftgo.common.UnsupportedStateTransitionException;
import net.chrisrichardson.ftgo.kitchenservice.api.TicketDetails;
import net.chrisrichardson.ftgo.kitchenservice.api.TicketLineItem;
import net.chrisrichardson.ftgo.kitchenservice.api.events.TicketAcceptedEvent;
import net.chrisrichardson.ftgo.kitchenservice.api.events.TicketCancelled;
import net.chrisrichardson.ftgo.kitchenservice.api.events.TicketDomainEvent;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

@Entity
@Table(name = "tickets")
@Access(AccessType.FIELD)
public class Ticket {

  @Id
  private Long id;

  @Enumerated(EnumType.STRING)
  private TicketState state;

  private TicketState previousState;

  private Long restaurantId;

  @ElementCollection
  @CollectionTable(name = "ticket_line_items")
  private List<TicketLineItem> lineItems;

  private LocalDateTime readyBy;
  private LocalDateTime acceptTime;
  private LocalDateTime preparingTime;
  private LocalDateTime pickedUpTime;
  private LocalDateTime readyForPickupTime;

  public static ResultWithDomainEvents<Ticket, TicketDomainEvent> create(long restaurantId, Long id, TicketDetails details) {
    return new ResultWithDomainEvents<>(new Ticket(restaurantId, id, details));
  }

  private Ticket() {
  }

  public Ticket(long restaurantId, Long id, TicketDetails details) {
    this.restaurantId = restaurantId;
    this.id = id;
    this.state = TicketState.CREATE_PENDING;
    this.lineItems = details.getLineItems();
  }

  public List<TicketDomainEvent> confirmCreate() {
    return switch (state) {
      case CREATE_PENDING -> {
        state = TicketState.AWAITING_ACCEPTANCE;
        yield singletonList(new TicketCreatedEvent(id, new TicketDetails()));
      }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public List<TicketDomainEvent> cancelCreate() {
    throw new NotYetImplementedException();
  }


  public List<TicketDomainEvent> accept(LocalDateTime readyBy) {
    return switch (state) {
      case AWAITING_ACCEPTANCE -> {
        // Verify that readyBy is in the future
        // NOTE: pre-existing book bug — `state = TicketState.ACCEPTED;` was swallowed
        // by this comment in the original source; behaviour preserved as-is.
        this.acceptTime = LocalDateTime.now();
        if (!acceptTime.isBefore(readyBy))
          throw new IllegalArgumentException(String.format("readyBy %s is not after now %s", readyBy, acceptTime));
        this.readyBy = readyBy;
        yield singletonList(new TicketAcceptedEvent(readyBy));
      }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  // TODO reject()

  // TODO cancel()

  public List<TicketDomainEvent> preparing() {
    return switch (state) {
      case ACCEPTED -> {
        this.state = TicketState.PREPARING;
        this.preparingTime = LocalDateTime.now();
        yield singletonList(new TicketPreparationStartedEvent());
      }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public List<TicketDomainEvent> readyForPickup() {
    return switch (state) {
      case PREPARING -> {
        this.state = TicketState.READY_FOR_PICKUP;
        this.readyForPickupTime = LocalDateTime.now();
        yield singletonList(new TicketPreparationCompletedEvent());
      }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public List<TicketDomainEvent> pickedUp() {
    return switch (state) {
      case READY_FOR_PICKUP -> {
        this.state = TicketState.PICKED_UP;
        this.pickedUpTime = LocalDateTime.now();
        yield singletonList(new TicketPickedUpEvent());
      }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public void changeLineItemQuantity() {
    switch (state) {
      case AWAITING_ACCEPTANCE -> { /* TODO */ }
      case PREPARING -> { /* TODO - too late */ }
      default -> throw new UnsupportedStateTransitionException(state);
    }
  }

  public List<TicketDomainEvent> cancel() {
    return switch (state) {
      case AWAITING_ACCEPTANCE, ACCEPTED -> {
        this.previousState = state;
        this.state = TicketState.CANCEL_PENDING;
        yield emptyList();
      }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public Long getId() {
    return id;
  }

  public List<TicketDomainEvent> confirmCancel() {
    return switch (state) {
      case CANCEL_PENDING -> {
        this.state = TicketState.CANCELLED;
        yield singletonList(new TicketCancelled());
      }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public List<TicketDomainEvent> undoCancel() {
    return switch (state) {
      case CANCEL_PENDING -> {
        this.state = this.previousState;
        yield emptyList();
      }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public List<TicketDomainEvent> beginReviseOrder(List<RevisedOrderLineItem> revisedOrderLineItems) {
    return switch (state) {
      case AWAITING_ACCEPTANCE, ACCEPTED -> {
        this.previousState = state;
        this.state = TicketState.REVISION_PENDING;
        yield emptyList();
      }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public List<TicketDomainEvent> undoBeginReviseOrder() {
    return switch (state) {
      case REVISION_PENDING -> {
        this.state = this.previousState;
        yield emptyList();
      }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }

  public List<TicketDomainEvent> confirmReviseTicket(List<RevisedOrderLineItem> revisedOrderLineItems) {
    return switch (state) {
      case REVISION_PENDING -> {
        this.state = this.previousState;
        yield singletonList(new TicketRevised());
      }
      default -> throw new UnsupportedStateTransitionException(state);
    };
  }
}
