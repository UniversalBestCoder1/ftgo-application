package net.chrisrichardson.ftgo.apiagateway.proxies;

import net.chrisrichardson.ftgo.apiagateway.orders.OrderDestinations;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class OrderServiceProxy {

  private final OrderDestinations orderDestinations;
  private final WebClient client;

  public OrderServiceProxy(OrderDestinations orderDestinations, WebClient client) {
    this.orderDestinations = orderDestinations;
    this.client = client;
  }

  public Mono<OrderInfo> findOrderById(String orderId) {
    return client.get()
            .uri(orderDestinations.getOrderServiceUrl() + "/orders/{orderId}", orderId)
            .exchangeToMono(resp -> {
              if (resp.statusCode().equals(HttpStatus.OK))
                return resp.bodyToMono(OrderInfo.class);
              else if (resp.statusCode().equals(HttpStatus.NOT_FOUND))
                return Mono.error(new OrderNotFoundException());
              else
                return Mono.error(new RuntimeException("Unknown: " + resp.statusCode()));
            });
  }
}
