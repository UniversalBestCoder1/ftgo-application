package net.chrisrichardson.ftgo.kitchenservice.messagehandlers;

import io.eventuate.tram.commands.consumer.CommandHandlers;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.sagas.participant.SagaCommandHandlersBuilder;
import net.chrisrichardson.ftgo.common.UnsupportedStateTransitionException;
import net.chrisrichardson.ftgo.kitchenservice.api.*;
import net.chrisrichardson.ftgo.kitchenservice.domain.RestaurantDetailsVerificationException;
import net.chrisrichardson.ftgo.kitchenservice.domain.Ticket;
import net.chrisrichardson.ftgo.kitchenservice.domain.KitchenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withFailure;
import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withSuccess;
import static io.eventuate.tram.sagas.participant.SagaReplyMessageBuilder.withLock;

/**
 * Saga participant handlers for the Ticket aggregate.
 *
 * <p><b>CC-02 — business-layer idempotency</b>: handlers that perform
 * forward-progress state transitions wrap their domain call with a catch
 * for {@link UnsupportedStateTransitionException}. When re-delivery
 * arrives after the aggregate has already advanced past the expected state
 * we return SUCCESS so the Saga orchestrator is not confused.  Compensation
 * (undo) handlers follow the same pattern for the same reason.
 *
 * <p>The framework-level {@code received_messages} table provides primary
 * deduplication; these guards cover the residual risk after framework
 * dedup is bypassed (e.g. a different consumer instance or a rare
 * at-least-once re-delivery after DB failover).
 */
public class KitchenServiceCommandHandler {

  private static final Logger log = LoggerFactory.getLogger(KitchenServiceCommandHandler.class);

  @Autowired
  private KitchenService kitchenService;

  public CommandHandlers commandHandlers() {
    return SagaCommandHandlersBuilder
            .fromChannel(KitchenServiceChannels.COMMAND_CHANNEL)
            .onMessage(CreateTicket.class,               this::createTicket)
            .onMessage(ConfirmCreateTicket.class,         this::confirmCreateTicket)
            .onMessage(CancelCreateTicket.class,          this::cancelCreateTicket)
            .onMessage(BeginCancelTicketCommand.class,    this::beginCancelTicket)
            .onMessage(ConfirmCancelTicketCommand.class,  this::confirmCancelTicket)
            .onMessage(UndoBeginCancelTicketCommand.class,this::undoBeginCancelTicket)
            .onMessage(BeginReviseTicketCommand.class,    this::beginReviseTicket)
            .onMessage(UndoBeginReviseTicketCommand.class,this::undoBeginReviseTicket)
            .onMessage(ConfirmReviseTicketCommand.class,  this::confirmReviseTicket)
            .build();
  }

  // ─── CreateOrderSaga ────────────────────────────────────────────────────────

  private Message createTicket(CommandMessage<CreateTicket> cm) {
    CreateTicket command = cm.getCommand();
    try {
      Ticket ticket = kitchenService.createTicket(
              command.getRestaurantId(), command.getOrderId(), command.getTicketDetails());
      return withLock(Ticket.class, ticket.getId())
              .withSuccess(new CreateTicketReply(ticket.getId()));
    } catch (RestaurantDetailsVerificationException e) {
      return withFailure();
    }
  }

  private Message confirmCreateTicket(CommandMessage<ConfirmCreateTicket> cm) {
    Long ticketId = cm.getCommand().getTicketId();
    try {
      kitchenService.confirmCreateTicket(ticketId);
    } catch (UnsupportedStateTransitionException e) {
      // CC-02: ticket already confirmed (AWAITING_ACCEPTANCE or later)
      log.info("confirmCreateTicket: ticket {} already past CREATE_PENDING ({}); treating as success", ticketId, e.getMessage());
    }
    return withSuccess();
  }

  private Message cancelCreateTicket(CommandMessage<CancelCreateTicket> cm) {
    Long ticketId = cm.getCommand().getTicketId();
    try {
      kitchenService.cancelCreateTicket(ticketId);
    } catch (UnsupportedStateTransitionException e) {
      // CC-02: ticket already cancelled
      log.info("cancelCreateTicket: ticket {} already past CREATE_PENDING ({}); treating as success", ticketId, e.getMessage());
    }
    return withSuccess();
  }

  // ─── CancelOrderSaga ────────────────────────────────────────────────────────

  private Message beginCancelTicket(CommandMessage<BeginCancelTicketCommand> cm) {
    try {
      kitchenService.cancelTicket(cm.getCommand().getRestaurantId(), cm.getCommand().getOrderId());
    } catch (UnsupportedStateTransitionException e) {
      // CC-02: ticket already in CANCEL_PENDING or CANCELLED — idempotent
      log.info("beginCancelTicket: ticket {} not in cancellable state ({}); treating as success",
              cm.getCommand().getOrderId(), e.getMessage());
    }
    return withSuccess();
  }

  private Message confirmCancelTicket(CommandMessage<ConfirmCancelTicketCommand> cm) {
    try {
      kitchenService.confirmCancelTicket(cm.getCommand().getRestaurantId(), cm.getCommand().getOrderId());
    } catch (UnsupportedStateTransitionException e) {
      // CC-02: ticket already CANCELLED
      log.info("confirmCancelTicket: ticket {} already past CANCEL_PENDING ({}); treating as success",
              cm.getCommand().getOrderId(), e.getMessage());
    }
    return withSuccess();
  }

  private Message undoBeginCancelTicket(CommandMessage<UndoBeginCancelTicketCommand> cm) {
    try {
      kitchenService.undoCancel(cm.getCommand().getRestaurantId(), cm.getCommand().getOrderId());
    } catch (UnsupportedStateTransitionException e) {
      // CC-02: ticket already un-cancelled (compensation already applied)
      log.info("undoBeginCancelTicket: ticket {} not in CANCEL_PENDING ({}); treating as success",
              cm.getCommand().getOrderId(), e.getMessage());
    }
    return withSuccess();
  }

  // ─── ReviseOrderSaga ────────────────────────────────────────────────────────

  public Message beginReviseTicket(CommandMessage<BeginReviseTicketCommand> cm) {
    try {
      kitchenService.beginReviseOrder(
              cm.getCommand().getRestaurantId(),
              cm.getCommand().getOrderId(),
              cm.getCommand().getRevisedOrderLineItems());
    } catch (UnsupportedStateTransitionException e) {
      // CC-02: ticket already in REVISION_PENDING or later
      log.info("beginReviseTicket: ticket {} not in revisable state ({}); treating as success",
              cm.getCommand().getOrderId(), e.getMessage());
    }
    return withSuccess();
  }

  public Message undoBeginReviseTicket(CommandMessage<UndoBeginReviseTicketCommand> cm) {
    try {
      kitchenService.undoBeginReviseOrder(
              cm.getCommand().getRestaurantId(),
              cm.getCommand().getOrderId());
    } catch (UnsupportedStateTransitionException e) {
      // CC-02: ticket no longer in REVISION_PENDING (compensation already applied)
      log.info("undoBeginReviseTicket: ticket {} not in REVISION_PENDING ({}); treating as success",
              cm.getCommand().getOrderId(), e.getMessage());
    }
    return withSuccess();
  }

  public Message confirmReviseTicket(CommandMessage<ConfirmReviseTicketCommand> cm) {
    try {
      kitchenService.confirmReviseTicket(
              cm.getCommand().getRestaurantId(),
              cm.getCommand().getOrderId(),
              cm.getCommand().getRevisedOrderLineItems());
    } catch (UnsupportedStateTransitionException e) {
      // CC-02: ticket already confirmed (revision committed)
      log.info("confirmReviseTicket: ticket {} already past REVISION_PENDING ({}); treating as success",
              cm.getCommand().getOrderId(), e.getMessage());
    }
    return withSuccess();
  }
}
