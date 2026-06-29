package net.chrisrichardson.ftgo.orderservice.sagas.reviseorder

import io.eventuate.tram.sagas.testing.SagaUnitTestSupport.given
import net.chrisrichardson.ftgo.accountservice.api.AccountingServiceChannels
import net.chrisrichardson.ftgo.accountservice.api.ReviseAuthorization
import net.chrisrichardson.ftgo.common.CommonJsonMapperInitializer
import net.chrisrichardson.ftgo.common.Money
import net.chrisrichardson.ftgo.common.RevisedOrderLineItem
import net.chrisrichardson.ftgo.kitchenservice.api.*
import net.chrisrichardson.ftgo.orderservice.api.OrderServiceChannels
import net.chrisrichardson.ftgo.orderservice.domain.OrderRevision
import net.chrisrichardson.ftgo.orderservice.sagaparticipants.*
import org.junit.BeforeClass
import org.junit.Test
import java.util.Optional

class ReviseOrderSagaTest {

    companion object {
        private const val ORDER_ID = 99L
        private const val CONSUMER_ID = 1511300065921L
        private const val RESTAURANT_ID = 1L
        private val REVISED_ORDER_TOTAL = Money("24.68")
        private val REVISED_LINE_ITEMS = listOf(RevisedOrderLineItem(2, "1"))
        private val ORDER_REVISION = OrderRevision(Optional.empty(), REVISED_LINE_ITEMS)

        @BeforeClass @JvmStatic
        fun initialize() = CommonJsonMapperInitializer.registerMoneyModule()
    }

    private fun makeSagaData() = ReviseOrderSagaData(CONSUMER_ID, ORDER_ID, null, ORDER_REVISION)
        .also { it.restaurantId = RESTAURANT_ID }

    private fun makeSaga() = ReviseOrderSaga().also { it.initializeSagaDefinition() }

    @Test
    fun shouldReviseOrder() {
        given()
            .saga(makeSaga(), makeSagaData())
            .expect()
                .command(BeginReviseOrderCommand(ORDER_ID, ORDER_REVISION)).to(OrderServiceChannels.COMMAND_CHANNEL)
            .andGiven().successReply(BeginReviseOrderReply(REVISED_ORDER_TOTAL))
            .expect()
                .command(BeginReviseTicketCommand(RESTAURANT_ID, ORDER_ID, REVISED_LINE_ITEMS)).to(KitchenServiceChannels.COMMAND_CHANNEL)
            .andGiven().successReply()
            .expect()
                .command(ReviseAuthorization(CONSUMER_ID, ORDER_ID, REVISED_ORDER_TOTAL)).to(AccountingServiceChannels.accountingServiceChannel)
            .andGiven().successReply()
            .expect()
                .command(ConfirmReviseTicketCommand(RESTAURANT_ID, ORDER_ID, REVISED_LINE_ITEMS)).to(KitchenServiceChannels.COMMAND_CHANNEL)
            .andGiven().successReply()
            .expect()
                .command(ConfirmReviseOrderCommand(ORDER_ID, ORDER_REVISION)).to(OrderServiceChannels.COMMAND_CHANNEL)
    }

    @Test
    fun shouldRollbackWhenTicketRevisionFails() {
        given()
            .saga(makeSaga(), makeSagaData())
            .expect()
                .command(BeginReviseOrderCommand(ORDER_ID, ORDER_REVISION)).to(OrderServiceChannels.COMMAND_CHANNEL)
            .andGiven().successReply(BeginReviseOrderReply(REVISED_ORDER_TOTAL))
            .expect()
                .command(BeginReviseTicketCommand(RESTAURANT_ID, ORDER_ID, REVISED_LINE_ITEMS)).to(KitchenServiceChannels.COMMAND_CHANNEL)
            .andGiven().failureReply()
            .expect()
                .command(UndoBeginReviseOrderCommand(ORDER_ID)).to(OrderServiceChannels.COMMAND_CHANNEL)
    }
}
