package net.chrisrichardson.ftgo.apiagateway.proxies;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class DeliveryService {

  private final DeliveryServiceDestinations destinations;
  private final WebClient client;

  public DeliveryService(DeliveryServiceDestinations destinations, WebClient client) {
    this.destinations = destinations;
    this.client = client;
  }

  public Mono<DeliveryInfo> findDeliveryByOrderId(String orderId) {
    return client.get()
            .uri(destinations.getDeliveryServiceUrl() + "/deliveries/{orderId}", orderId)
            .exchangeToMono(resp -> {
              if (resp.statusCode().equals(HttpStatus.OK))
                return resp.bodyToMono(DeliveryInfo.class);
              else if (resp.statusCode().equals(HttpStatus.NOT_FOUND))
                return Mono.error(new OrderNotFoundException());
              else
                return Mono.error(new RuntimeException("Unknown: " + resp.statusCode()));
            });
  }
}
