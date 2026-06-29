package net.chrisrichardson.ftgo.orderservice.sagas.cancelorder

import io.eventuate.tram.sagas.testing.SagaUnitTestSupport.given
import net.chrisrichardson.ftgo.accountservice.api.AccountingServiceChannels
import net.chrisrichardson.ftgo.accountservice.api.ReverseAuthorizationCommand
import net.chrisrichardson.ftgo.common.CommonJsonMapperInitializer
import net.chrisrichardson.ftgo.common.Money
import net.chrisrichardson.ftgo.kitchenservice.api.*
import net.chrisrichardson.ftgo.orderservice.api.OrderServiceChannels
import net.chrisrichardson.ftgo.orderservice.sagaparticipants.*
import org.junit.BeforeClass
import org.junit.Test

class CancelOrderSagaTest {

    companion object {
        private const val ORDER_ID = 99L
        private const val CONSUMER_ID = 1511300065921L
        private const val RESTAURANT_ID = 1L
        private val ORDER_TOTAL = Money("61.70")

        @BeforeClass @JvmStatic
        fun initialize() = CommonJsonMapperInitializer.registerMoneyModule()
    }

    private fun makeSagaData() = CancelOrderSagaData(CONSUMER_ID, ORDER_ID, ORDER_TOTAL)
        .also { it.restaurantId = RESTAURANT_ID }

    private fun makeSaga() = CancelOrderSaga().also { it.initializeSagaDefinition() }

    @Test
    fun shouldCancelOrder() {
        given()
            .saga(makeSaga(), makeSagaData())
            .expect()
                .command(BeginCancelCommand(ORDER_ID)).to(OrderServiceChannels.COMMAND_CHANNEL)
            .andGiven().successReply()
            .expect()
                .command(BeginCancelTicketCommand(RESTAURANT_ID, ORDER_ID)).to(KitchenServiceChannels.COMMAND_CHANNEL)
            .andGiven().successReply()
            .expect()
                .command(ReverseAuthorizationCommand(CONSUMER_ID, ORDER_ID, ORDER_TOTAL)).to(AccountingServiceChannels.accountingServiceChannel)
            .andGiven().successReply()
            .expect()
                .command(ConfirmCancelTicketCommand(RESTAURANT_ID, ORDER_ID)).to(KitchenServiceChannels.COMMAND_CHANNEL)
            .andGiven().successReply()
            .expect()
                .command(ConfirmCancelOrderCommand(ORDER_ID)).to(OrderServiceChannels.COMMAND_CHANNEL)
    }

    @Test
    fun shouldRollbackWhenAccountingFails() {
        given()
            .saga(makeSaga(), makeSagaData())
            .expect()
                .command(BeginCancelCommand(ORDER_ID)).to(OrderServiceChannels.COMMAND_CHANNEL)
            .andGiven().successReply()
            .expect()
                .command(BeginCancelTicketCommand(RESTAURANT_ID, ORDER_ID)).to(KitchenServiceChannels.COMMAND_CHANNEL)
            .andGiven().successReply()
            .expect()
                .command(ReverseAuthorizationCommand(CONSUMER_ID, ORDER_ID, ORDER_TOTAL)).to(AccountingServiceChannels.accountingServiceChannel)
            .andGiven().failureReply()
            .expect()
                .command(UndoBeginCancelTicketCommand(RESTAURANT_ID, ORDER_ID)).to(KitchenServiceChannels.COMMAND_CHANNEL)
            .andGiven().successReply()
            .expect()
                .command(UndoBeginCancelCommand(ORDER_ID)).to(OrderServiceChannels.COMMAND_CHANNEL)
    }
}
