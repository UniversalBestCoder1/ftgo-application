package net.chrisrichardson.ftgo.orderservice.consistency;

import io.eventuate.tram.spring.commands.common.TramCommandsCommonAutoConfiguration;
import io.eventuate.tram.spring.consumer.common.TramNoopDuplicateMessageDetectorConfiguration;
import io.eventuate.tram.spring.events.common.TramEventsCommonAutoConfiguration;
import io.eventuate.tram.spring.messaging.common.TramMessagingCommonAutoConfiguration;
import io.eventuate.tram.commands.consumer.CommandHandlers;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.ChannelMapping;
import io.eventuate.tram.messaging.common.DefaultChannelMapping;
import io.eventuate.tram.sagas.participant.SagaCommandDispatcher;
import io.eventuate.tram.sagas.participant.SagaCommandDispatcherFactory;
import io.eventuate.tram.sagas.participant.SagaCommandHandlersBuilder;
import io.eventuate.tram.spring.jdbckafka.TramJdbcKafkaConfiguration;
import io.eventuate.util.test.async.Eventually;
import net.chrisrichardson.ftgo.accountservice.api.AccountingServiceChannels;
import net.chrisrichardson.ftgo.accountservice.api.AuthorizeCommand;
import net.chrisrichardson.ftgo.accountservice.api.ReviseAuthorization;
import net.chrisrichardson.ftgo.accountservice.api.ReverseAuthorizationCommand;
import net.chrisrichardson.ftgo.common.Money;
import net.chrisrichardson.ftgo.common.RevisedOrderLineItem;
import net.chrisrichardson.ftgo.consumerservice.api.ConsumerServiceChannels;
import net.chrisrichardson.ftgo.consumerservice.api.ValidateOrderByConsumer;
import net.chrisrichardson.ftgo.kitchenservice.messagehandlers.KitchenServiceMessageHandlersConfiguration;
import net.chrisrichardson.ftgo.orderservice.OrderDetailsMother;
import net.chrisrichardson.ftgo.orderservice.RestaurantMother;
import net.chrisrichardson.ftgo.orderservice.api.events.OrderState;
import net.chrisrichardson.ftgo.orderservice.api.web.CreateOrderRequest;
import net.chrisrichardson.ftgo.orderservice.api.web.CreateOrderResponse;
import net.chrisrichardson.ftgo.orderservice.api.web.ReviseOrderRequest;
import net.chrisrichardson.ftgo.kitchenservice.domain.KitchenService;
import net.chrisrichardson.ftgo.kitchenservice.domain.RestaurantMenu;
import net.chrisrichardson.ftgo.orderservice.domain.OrderRepository;
import net.chrisrichardson.ftgo.orderservice.domain.OrderService;
import net.chrisrichardson.ftgo.orderservice.domain.RestaurantRepository;
import net.chrisrichardson.ftgo.orderservice.messaging.OrderServiceMessagingConfiguration;
import net.chrisrichardson.ftgo.orderservice.service.OrderCommandHandlersConfiguration;
import net.chrisrichardson.ftgo.orderservice.web.GetOrderResponse;
import net.chrisrichardson.ftgo.orderservice.web.OrderWebConfiguration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.client.RestTemplate;

import io.eventuate.tram.events.publisher.DomainEventPublisher;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withSuccess;
import static net.chrisrichardson.ftgo.orderservice.RestaurantMother.AJANTA_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-09 / T-10 / T-11 — 数据一致性集成测试
 *
 * <p>测试前置条件：
 * <pre>
 *   docker-compose -f docker-compose-integration-test.yml up -d
 * </pre>
 *
 * <p>设计说明：
 * <ul>
 *   <li>OrderService + KitchenService 运行于同一 Spring 上下文（共用 ftgo_order_service 库）。
 *       KitchenService 的 {@code tickets} 等表由 Hibernate ddl-auto=update 在该库自动建立。
 *   <li>ConsumerService / AccountingService 用内联 Saga CommandDispatcher stub 自动回复 SUCCESS，
 *       无需启动外部服务。
 *   <li>使用真实 Kafka（{@link TramJdbcKafkaConfiguration}）——整条 Outbox → CDC → Kafka → Consumer 链路全跑通。
 *   <li>T-09：CreateOrderSaga 完成后 orders.state=APPROVED，tickets.state=AWAITING_ACCEPTANCE。
 *   <li>T-10：CancelOrderSaga 完成后 orders.state=CANCELLED，tickets.state=CANCELLED。
 *   <li>T-11：ReviseOrderSaga 完成后 orders.order_total 更新，ticket_line_items 数量更新。
 * </ul>
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
        classes = SagaConsistencyIntegrationTest.TestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // OrderService 与 KitchenService 都有 RestaurantRepository，同名 Bean 需要允许覆盖
                "spring.main.allow-bean-definition-overriding=true",
                // ── 数据源（MySQL 对外映射端口 3307，内部 3306）
                // 使用 root 用户以获得 DDL 权限（ddl-auto=update 需要 CREATE/ALTER）
                "spring.datasource.url=jdbc:mysql://localhost:3307/ftgo_order_service",
                "spring.datasource.username=root",
                "spring.datasource.password=rootpassword",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                // ── Hibernate 6 在 Spring Boot 3 需要显式指定 Dialect（否则无法从 JDBC 元数据推断）
                "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
                // ── Eventuate / Kafka
                "eventuatelocal.kafka.bootstrap.servers=localhost:9092",
                "eventuatelocal.zookeeper.connection.string=localhost:2181",
                "eventuate.database.schema=ftgo_order_service",
                // ── JPA：允许 Hibernate 在测试库里自动建 KitchenService 表
                "spring.jpa.generate-ddl=true",
                "spring.jpa.hibernate.ddl-auto=update",
        }
)
public class SagaConsistencyIntegrationTest {

    // ─────────────────────── Spring Configuration ───────────────────────────

    @Configuration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackages = {
            "net.chrisrichardson.ftgo.orderservice.domain",
            "net.chrisrichardson.ftgo.kitchenservice.domain",
    })
    @EntityScan(basePackages = {
            "net.chrisrichardson.ftgo.orderservice.domain",
            "net.chrisrichardson.ftgo.kitchenservice.domain",
    })
    @Import({
            // Eventuate Tram 0.30 使用旧式 spring.factories，Spring Boot 3.3 不自动处理 → 显式导入全部4个 AutoConfiguration
            TramMessagingCommonAutoConfiguration.class,   // provides ChannelMapping
            TramCommandsCommonAutoConfiguration.class,    // provides CommandNameMapping
            TramNoopDuplicateMessageDetectorConfiguration.class, // no-op dedup for tests (JDBC impl blocks in combined context)
            TramEventsCommonAutoConfiguration.class,      // provides EventsCommon beans
            OrderWebConfiguration.class,
            OrderServiceMessagingConfiguration.class,
            OrderCommandHandlersConfiguration.class,
            TramJdbcKafkaConfiguration.class,
            // KitchenService 的消息处理器（CreateTicket / ConfirmCreateTicket 等）
            KitchenServiceMessageHandlersConfiguration.class,
    })
    public static class TestConfiguration {

        /**
         * TramJdbcKafkaConfiguration 不提供 ChannelMapping；需手动注册 pass-through 实现。
         * 参见：OrderHistoryEventHandlersTest 中的相同模式。
         */
        @Bean
        public ChannelMapping channelMapping() {
            return new DefaultChannelMapping.DefaultChannelMappingBuilder().build();
        }

        /**
         * 模拟 ConsumerService：对所有 ValidateOrderByConsumer 命令自动回复 SUCCESS。
         */
        @Bean
        public SagaCommandDispatcher consumerServiceStub(SagaCommandDispatcherFactory factory) {
            CommandHandlers handlers = SagaCommandHandlersBuilder
                    .fromChannel(ConsumerServiceChannels.consumerServiceChannel)
                    .onMessage(ValidateOrderByConsumer.class,
                            (CommandMessage<ValidateOrderByConsumer> cm) -> withSuccess())
                    .build();
            return factory.make("it-consumerServiceStub", handlers);
        }

        /**
         * 模拟 AccountingService：对 Authorize / ReverseAuthorize / ReviseAuthorize 自动回复 SUCCESS。
         */
        @Bean
        public SagaCommandDispatcher accountingServiceStub(SagaCommandDispatcherFactory factory) {
            CommandHandlers handlers = SagaCommandHandlersBuilder
                    .fromChannel(AccountingServiceChannels.accountingServiceChannel)
                    .onMessage(AuthorizeCommand.class,
                            (CommandMessage<AuthorizeCommand> cm) -> withSuccess())
                    .onMessage(ReverseAuthorizationCommand.class,
                            (CommandMessage<ReverseAuthorizationCommand> cm) -> withSuccess())
                    .onMessage(ReviseAuthorization.class,
                            (CommandMessage<ReviseAuthorization> cm) -> withSuccess())
                    .build();
            return factory.make("it-accountingServiceStub", handlers);
        }
    }

    // ─────────────────────── Injected beans ─────────────────────────────────

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private DomainEventPublisher domainEventPublisher;

    @Autowired
    private OrderService orderService;

    @Autowired
    private KitchenService kitchenService;

    /** OrderService 侧的 restaurants 表（验证餐厅是否已注册）。 */
    @Autowired
    private RestaurantRepository orderServiceRestaurantRepository;

    @Autowired
    private OrderRepository orderRepository;

    /** 用于直接查询 tickets / ticket_line_items 表。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RestTemplate restTemplate = new RestTemplate();

    // ─────────────────────── Test lifecycle ─────────────────────────────────

    /**
     * 每个测试前清理旧数据，避免前次测试残留的 APPROVAL_PENDING 订单和 Saga 实例干扰。
     */
    @Before
    public void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM ticket_line_items");
        jdbcTemplate.update("DELETE FROM tickets");
        jdbcTemplate.update("DELETE FROM order_line_items");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM saga_instance_participants");
        jdbcTemplate.update("DELETE FROM saga_instance");
        jdbcTemplate.update("DELETE FROM saga_lock_table");
        jdbcTemplate.update("DELETE FROM saga_stash_table");
        jdbcTemplate.update("DELETE FROM received_messages");
    }

    // ─────────────────────── Helpers ────────────────────────────────────────

    private String ordersUrl() {
        return "http://localhost:" + port + "/orders";
    }

    /**
     * 直接写库注册 Ajanta 餐厅，避免依赖不稳定的 Outbox→CDC→Kafka 事件管道。
     * <ul>
     *   <li>OrderService 侧：{@code order_service_restaurants} 表
     *   <li>KitchenService 侧：{@code kitchen_service_restaurants} 表
     * </ul>
     */
    private void ensureRestaurantRegistered() {
        // OrderService 侧：直接调用 service 写库（幂等：若已存在则跳过）
        if (orderServiceRestaurantRepository.findById(AJANTA_ID).isEmpty()) {
            orderService.createMenu(
                    AJANTA_ID,
                    RestaurantMother.AJANTA_RESTAURANT_NAME,
                    RestaurantMother.AJANTA_RESTAURANT_MENU_ITEMS
            );
        }
        // KitchenService 侧：直接调用 service 写库
        jdbcTemplate.update(
                "INSERT IGNORE INTO kitchen_service_restaurants (id) VALUES (?)",
                AJANTA_ID
        );
        jdbcTemplate.update(
                "INSERT IGNORE INTO kitchen_service_restaurant_menu_items " +
                "(kitchen_restaurant_id, id, name, amount) VALUES (?,?,?,?)",
                AJANTA_ID,
                RestaurantMother.CHICKEN_VINDALOO_MENU_ITEM_ID,
                RestaurantMother.CHICKEN_VINDALOO,
                "12.34"  // CHICKEN_VINDALOO_PRICE = Money("12.34"), Money.amount is private
        );
    }

    /**
     * 创建一笔订单并等待 CreateOrderSaga 完成（state → APPROVED）。
     *
     * @return 新订单 ID
     */
    private long createAndApproveOrder() {
        ensureRestaurantRegistered();

        CreateOrderRequest request = new CreateOrderRequest(
                OrderDetailsMother.CONSUMER_ID,
                AJANTA_ID,
                OrderDetailsMother.DELIVERY_ADDRESS,
                OrderDetailsMother.DELIVERY_TIME,
                List.of(new CreateOrderRequest.LineItem(
                        RestaurantMother.CHICKEN_VINDALOO_MENU_ITEM_ID,
                        OrderDetailsMother.CHICKEN_VINDALOO_QUANTITY))
        );

        // POST /orders — CreateOrderResponse 是 record，用 orderId() accessor
        CreateOrderResponse response = restTemplate.postForObject(
                ordersUrl(), request, CreateOrderResponse.class);
        assertThat(response).isNotNull();
        long orderId = response.orderId();

        // 等待 Saga 完成（最多 60 秒，每 500ms 一次）
        // 注：kitchenServiceCommands consumer group 可能有积压消息需先处理
        Eventually.eventually(60, 500, TimeUnit.MILLISECONDS, () -> {
            ResponseEntity<GetOrderResponse> r = restTemplate.getForEntity(
                    ordersUrl() + "/" + orderId, GetOrderResponse.class);
            assertThat(r.getBody()).isNotNull();
            assertThat(r.getBody().getState()).isEqualTo(OrderState.APPROVED);
        });

        return orderId;
    }

    /** 直接查询 tickets.state（KitchenService 写同库）。 */
    private String ticketState(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM tickets WHERE id = ?",
                String.class,
                orderId
        );
    }

    // ─────────────────────── T-09 ───────────────────────────────────────────

    /**
     * T-09：CreateOrderSaga 完成后两服务状态一致。
     *
     * <pre>
     *   orders.state  == APPROVED
     *   tickets.state == AWAITING_ACCEPTANCE
     * </pre>
     */
    @Test
    public void t09_createOrderSaga_stateConsistentAfterApproval() {
        long orderId = createAndApproveOrder();

        // orders 表（已在 createAndApproveOrder 中通过 REST 确认）
        assertThat(orderRepository.findById(orderId))
                .isPresent()
                .hasValueSatisfying(o ->
                        assertThat(o.getState()).isEqualTo(OrderState.APPROVED));

        // tickets 表（KitchenService confirmCreate → state=AWAITING_ACCEPTANCE）
        Eventually.eventually(() ->
                assertThat(ticketState(orderId)).isEqualTo("AWAITING_ACCEPTANCE")
        );
    }

    // ─────────────────────── T-10 ───────────────────────────────────────────

    /**
     * T-10：CancelOrderSaga 完成后两服务状态一致。
     *
     * <pre>
     *   orders.state  == CANCELLED
     *   tickets.state == CANCELLED
     * </pre>
     */
    @Test
    public void t10_cancelOrderSaga_stateConsistentAfterCancellation() {
        long orderId = createAndApproveOrder();

        // POST /orders/{id}/cancel
        restTemplate.postForObject(
                ordersUrl() + "/" + orderId + "/cancel",
                null, Void.class);

        // orders 表：等待 CANCELLED（最多 60 秒，含处理积压消息时间）
        Eventually.eventually(60, 500, TimeUnit.MILLISECONDS, () -> {
            ResponseEntity<GetOrderResponse> r = restTemplate.getForEntity(
                    ordersUrl() + "/" + orderId, GetOrderResponse.class);
            assertThat(r.getBody()).isNotNull();
            assertThat(r.getBody().getState()).isEqualTo(OrderState.CANCELLED);
        });

        // tickets 表：state=CANCELLED
        Eventually.eventually(() ->
                assertThat(ticketState(orderId)).isEqualTo("CANCELLED")
        );
    }

    // ─────────────────────── T-11 ───────────────────────────────────────────

    /**
     * T-11：ReviseOrderSaga 完成后金额与数量一致。
     *
     * <p>修订：Chicken Vindaloo 数量 5 → 3，单价 12.34，修订后总价 = 37.02。
     *
     * <pre>
     *   orders.state      == APPROVED
     *   orders.orderTotal == Money("37.02")
     *   ticket_line_items.quantity == 3（对应该 ticket 行）
     * </pre>
     */
    @Test
    public void t11_reviseOrderSaga_totalAndTicketQuantityConsistentAfterRevision() {
        long orderId = createAndApproveOrder();

        // 修订：数量 5 → 3（ReviseOrderRequest 是 record）
        ReviseOrderRequest reviseRequest = new ReviseOrderRequest(
                List.of(new RevisedOrderLineItem(3, RestaurantMother.CHICKEN_VINDALOO_MENU_ITEM_ID)));
        restTemplate.postForObject(
                ordersUrl() + "/" + orderId + "/revise",
                reviseRequest, Void.class);

        // 等待订单重回 APPROVED（REVISION_PENDING → APPROVED）并验证金额
        Money expectedTotal = new Money("37.02");  // 3 × 12.34
        Eventually.eventually(60, 500, TimeUnit.MILLISECONDS, () -> {
            ResponseEntity<GetOrderResponse> r = restTemplate.getForEntity(
                    ordersUrl() + "/" + orderId, GetOrderResponse.class);
            assertThat(r.getBody()).isNotNull();
            assertThat(r.getBody().getState()).isEqualTo(OrderState.APPROVED);
            assertThat(r.getBody().getOrderTotal()).isEqualTo(expectedTotal);
        });

        // ticket_line_items 中该 ticket 的数量也已更新为 3
        Eventually.eventually(() -> {
            Integer qty = jdbcTemplate.queryForObject(
                    "SELECT quantity FROM ticket_line_items WHERE ticket_id = ?",
                    Integer.class, orderId);
            assertThat(qty).isEqualTo(3);
        });
    }
}
