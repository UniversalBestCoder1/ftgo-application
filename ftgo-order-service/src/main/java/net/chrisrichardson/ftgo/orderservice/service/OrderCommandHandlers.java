package net.chrisrichardson.ftgo.orderservice.service;

import io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder;
import net.chrisrichardson.ftgo.orderservice.domain.OrderRepository;
import net.chrisrichardson.ftgo.orderservice.domain.OrderRevision;
import net.chrisrichardson.ftgo.orderservice.domain.OrderService;
import net.chrisrichardson.ftgo.orderservice.domain.RevisedOrder;
import net.chrisrichardson.ftgo.orderservice.sagaparticipants.*;
import io.eventuate.tram.commands.consumer.CommandHandlers;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.events.publisher.DomainEventPublisher;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.sagas.participant.SagaCommandHandlersBuilder;
import net.chrisrichardson.ftgo.common.UnsupportedStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withFailure;
import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withSuccess;

/**
 * Saga participant handlers for the Order aggregate.
 *
 * <p><b>CC-02 — business-layer idempotency</b>: every handler catches
 * {@link UnsupportedStateTransitionException} and returns SUCCESS.  The
 * Eventuate framework-level {@code received_messages} table provides the
 * primary deduplication; these guards cover the rare case where a command
 * arrives after the aggregate has already advanced past the expected state
 * (e.g. a redelivered Saga step).  Returning SUCCESS is always safe here
 * because the Saga orchestrator drives state-machine forward progress — if
 * the Order is already in the post-command state, the Saga was already
 * told SUCCESS once and will not restart the step.
 */
public class OrderCommandHandlers {

  private static final Logger log = LoggerFactory.getLogger(OrderCommandHandlers.class);

  @Autowired
  private OrderService orderService;

  public CommandHandlers commandHandlers() {
    return SagaCommandHandlersBuilder
          .fromChannel("orderService")
          .onMessage(ApproveOrderCommand.class,       this::approveOrder)
          .onMessage(RejectOrderCommand.class,         this::rejectOrder)
          .onMessage(BeginCancelCommand.class,         this::beginCancel)
          .onMessage(UndoBeginCancelCommand.class,     this::undoCancel)
          .onMessage(ConfirmCancelOrderCommand.class,  this::confirmCancel)
          .onMessage(BeginReviseOrderCommand.class,    this::beginReviseOrder)
          .onMessage(UndoBeginReviseOrderCommand.class,this::undoPendingRevision)
          .onMessage(ConfirmReviseOrderCommand.class,  this::confirmRevision)
          .build();
  }

  // ─── CreateOrderSaga ────────────────────────────────────────────────────────

  public Message approveOrder(CommandMessage<ApproveOrderCommand> cm) {
    long orderId = cm.getCommand().getOrderId();
    try {
      orderService.approveOrder(orderId);
    } catch (UnsupportedStateTransitionException e) {
      // CC-02: already APPROVED (or past) — idempotent success
      log.info("approveOrder: order {} already past APPROVAL_PENDING ({}); treating as success", orderId, e.getMessage());
    }
    return withSuccess();
  }

  public Message rejectOrder(CommandMessage<RejectOrderCommand> cm) {
    long orderId = cm.getCommand().getOrderId();
    try {
      orderService.rejectOrder(orderId);
    } catch (UnsupportedStateTransitionException e) {
      // CC-02: already REJECTED (or compensated past this step)
      log.info("rejectOrder: order {} already past APPROVAL_PENDING ({}); treating as success", orderId, e.getMessage());
    }
    return withSuccess();
  }

  // ─── CancelOrderSaga ────────────────────────────────────────────────────────

  public Message beginCancel(CommandMessage<BeginCancelCommand> cm) {
    long orderId = cm.getCommand().getOrderId();
    try {
      orderService.beginCancel(orderId);
      return withSuccess();
    } catch (UnsupportedStateTransitionException e) {
      return withFailure();
    }
  }

  public Message undoCancel(CommandMessage<UndoBeginCancelCommand> cm) {
    long orderId = cm.getCommand().getOrderId();
    try {
      orderService.undoCancel(orderId);
    } catch (UnsupportedStateTransitionException e) {
      // CC-02: already APPROVED — compensation already applied or never needed
      log.info("undoCancel: order {} not in CANCEL_PENDING ({}); treating as success", orderId, e.getMessage());
    }
    return withSuccess();
  }

  public Message confirmCancel(CommandMessage<ConfirmCancelOrderCommand> cm) {
    long orderId = cm.getCommand().getOrderId();
    try {
      orderService.confirmCancelled(orderId);
    } catch (UnsupportedStateTransitionException e) {
      // CC-02: already CANCELLED
      log.info("confirmCancel: order {} already past CANCEL_PENDING ({}); treating as success", orderId, e.getMessage());
    }
    return withSuccess();
  }

  // ─── ReviseOrderSaga ────────────────────────────────────────────────────────

  public Message beginReviseOrder(CommandMessage<BeginReviseOrderCommand> cm) {
    long orderId = cm.getCommand().getOrderId();
    OrderRevision revision = cm.getCommand().getRevision();
    try {
      return orderService.beginReviseOrder(orderId, revision)
              .map(result -> withSuccess(new BeginReviseOrderReply(result.getChange().getNewOrderTotal())))
              .orElseGet(CommandHandlerReplyBuilder::withFailure);
    } catch (UnsupportedStateTransitionException e) {
      return withFailure();
    }
  }

  public Message undoPendingRevision(CommandMessage<UndoBeginReviseOrderCommand> cm) {
    long orderId = cm.getCommand().getOrderId();
    try {
      orderService.undoPendingRevision(orderId);
    } catch (UnsupportedStateTransitionException e) {
      // CC-02: already APPROVED — compensation already applied
      log.info("undoPendingRevision: order {} not in REVISION_PENDING ({}); treating as success", orderId, e.getMessage());
    }
    return withSuccess();
  }

  public Message confirmRevision(CommandMessage<ConfirmReviseOrderCommand> cm) {
    long orderId = cm.getCommand().getOrderId();
    OrderRevision revision = cm.getCommand().getRevision();
    try {
      orderService.confirmRevision(orderId, revision);
    } catch (UnsupportedStateTransitionException e) {
      // CC-02: already APPROVED (revision confirmed on a previous delivery)
      log.info("confirmRevision: order {} not in REVISION_PENDING ({}); treating as success", orderId, e.getMessage());
    }
    return withSuccess();
  }
}
