package net.chrisrichardson.ftgo.accountingservice.domain;

import io.eventuate.Event;
import io.eventuate.ReflectiveMutableCommandProcessingAggregate;
import io.eventuate.tram.sagas.eventsourcingsupport.SagaReplyRequestedEvent;

import java.util.List;

import static io.eventuate.EventUtil.events;

public class Account extends ReflectiveMutableCommandProcessingAggregate<Account, AccountCommand> {

  public List<Event> process(CreateAccountCommand command) {
    return events(new AccountCreatedEvent());
  }

  public void apply(AccountCreatedEvent event) {

  }


  public List<Event> process(AuthorizeCommandInternal command) {
    return events(new AccountAuthorizedEvent());
  }

  public List<Event> process(ReverseAuthorizationCommandInternal command) {
    // IC-04: emit event so reversal is visible in the Eventuate event log
    return events(new AccountAuthorizationReversedEvent(command.getConsumerId(), command.getOrderId()));
  }
  public List<Event> process(ReviseAuthorizationCommandInternal command) {
    // CC-03: emit event so revision is visible in the Eventuate event log.
    // Framework-level received_messages deduplication prevents double-processing
    // on replay; the event acts as an additional audit trail.
    return events(new AccountAuthorizationRevisedEvent(
            command.getConsumerId(), command.getOrderId(), command.getOrderTotal()));
  }

  public void apply(AccountAuthorizationRevisedEvent event) {
    // State-less aggregate — balance tracking not yet implemented;
    // event presence in the log is sufficient for CC-03 fix.
  }

  public void apply(AccountAuthorizedEvent event) {

  }

  public void apply(SagaReplyRequestedEvent event) {
    // TODO - need a way to not need this method
  }


}
