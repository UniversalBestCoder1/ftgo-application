package net.chrisrichardson.ftgo.orderservice.sagas.createorder

import io.eventuate.tram.sagas.testing.SagaUnitTestSupport.given
import net.chrisrichardson.ftgo.accountservice.api.AccountingServiceChannels
import net.chrisrichardson.ftgo.accountservice.api.AuthorizeCommand
import net.chrisrichardson.ftgo.common.CommonJsonMapperInitializer
import net.chrisrichardson.ftgo.consumerservice.api.ConsumerServiceChannels
import net.chrisrichardson.ftgo.consumerservice.api.ValidateOrderByConsumer
import net.chrisrichardson.ftgo.kitchenservice.api.*
import net.chrisrichardson.ftgo.orderservice.OrderDetailsMother.CHICKEN_VINDALOO_ORDER_DETAILS
import net.chrisrichardson.ftgo.orderservice.OrderDetailsMother.CHICKEN_VINDALOO_ORDER_TOTAL
import net.chrisrichardson.ftgo.orderservice.OrderDetailsMother.CONSUMER_ID
import net.chrisrichardson.ftgo.orderservice.OrderDetailsMother.ORDER_ID
import net.chrisrichardson.ftgo.orderservice.RestaurantMother.AJANTA_ID
import net.chrisrichardson.ftgo.orderservice.sagaparticipants.*
import net.chrisrichardson.ftgo.orderservice.api.OrderServiceChannels
import org.junit.BeforeClass
import org.junit.Test

/**
 * TEST-03 (补偿路径集成测试) — CreateOrderSaga
 *
 * 每个测试覆盖 Saga 中某一步骤失败后的完整补偿链。
 * 使用 Eventuate Tram 的 SagaUnitTestSupport 在内存中驱动整个状态机，
 * 无需真实的 Kafka / 数据库。
 */
class CreateOrderSagaCompensationTest {

    companion object {
        @BeforeClass @JvmStatic
        fun initialize() = CommonJsonMapperInitializer.registerMoneyModule()
    }

    private fun makeSaga() = CreateOrderSaga(
        OrderServiceProxy(),
        ConsumerServiceProxy(),
        KitchenServiceProxy(),
        AccountingServiceProxy(),
    )

    private fun makeState() = CreateOrderSagaState(ORDER_ID, CHICKEN_VINDALOO_ORDER_DETAILS)

    private fun authorizeCmd() =
        AuthorizeCommand()
            .withConsumerId(CONSUMER_ID)
            .withOrderId(ORDER_ID)
            .withOrderTotal(CHICKEN_VINDALOO_ORDER_TOTAL.asString())

    private fun validateCmd() =
        ValidateOrderByConsumer()
            .withConsumerId(CONSUMER_ID)
            .withOrderId(ORDER_ID)
            .withOrderTotal(CHICKEN_VINDALOO_ORDER_TOTAL.asString())

    // -----------------------------------------------------------------------
    // 步骤 1 (consumer validate) 失败 → reject order
    // -----------------------------------------------------------------------

    @Test
    fun `compensation - consumer validation failure rejects order`() {
        given()
            .saga(makeSaga(), makeState())
            .expect()
                .command(validateCmd()).to(ConsumerServiceChannels.consumerServiceChannel)
            .andGiven().failureReply()
            .expect()
                .command(RejectOrderCommand(ORDER_ID)).to(OrderServiceChannels.COMMAND_CHANNEL)
    }

    // -----------------------------------------------------------------------
    // 步骤 2 (kitchen create ticket) 失败 → reject order
    // -----------------------------------------------------------------------

    @Test
    fun `compensation - kitchen create ticket failure rejects order`() {
        given()
            .saga(makeSaga(), makeState())
            .expect()
                .command(validateCmd()).to(ConsumerServiceChannels.consumerServiceChannel)
            .andGiven().successReply()
            .expect()
                .command(CreateTicket(AJANTA_ID, ORDER_ID, null)).to(KitchenServiceChannels.COMMAND_CHANNEL)
            .andGiven().failureReply()
            // createTicket failure has no explicit compensation step — saga ends with reject
            .expect()
                .command(RejectOrderCommand(ORDER_ID)).to(OrderServiceChannels.COMMAND_CHANNEL)
    }

    // -----------------------------------------------------------------------
    // 步骤 3 (accounting authorize) 失败 → cancel ticket + reject order
    // -----------------------------------------------------------------------

    @Test
    fun `compensation - accounting authorization failure cancels ticket then rejects order`() {
        given()
            .saga(makeSaga(), makeState())
            .expect()
                .command(validateCmd()).to(ConsumerServiceChannels.consumerServiceChannel)
            .andGiven().successReply()
            .expect()
                .command(CreateTicket(AJANTA_ID, ORDER_ID, null)).to(KitchenServiceChannels.COMMAND_CHANNEL)
            .andGiven().successReply()
            .expect()
                .command(authorizeCmd()).to(AccountingServiceChannels.accountingServiceChannel)
            .andGiven().failureReply()
            // compensation: cancel the newly created kitchen ticket
            .expect()
                .command(CancelCreateTicket(ORDER_ID)).to(KitchenServiceChannels.COMMAND_CHANNEL)
            .andGiven().successReply()
            // compensation: reject the order
            .expect()
                .command(RejectOrderCommand(ORDER_ID)).to(OrderServiceChannels.COMMAND_CHANNEL)
    }
}
