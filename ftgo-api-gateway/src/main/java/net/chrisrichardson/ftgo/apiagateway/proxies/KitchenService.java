package net.chrisrichardson.ftgo.apiagateway.proxies;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class KitchenService {

  private final KitchenServiceDestinations destinations;
  private final WebClient client;

  public KitchenService(KitchenServiceDestinations destinations, WebClient client) {
    this.destinations = destinations;
    this.client = client;
  }

  /** ticketId in FTGO equals the orderId for a given order. */
  public Mono<TicketInfo> findTicketById(String ticketId) {
    return client.get()
            .uri(destinations.getKitchenServiceUrl() + "/tickets/{ticketId}", ticketId)
            .exchangeToMono(resp -> {
              if (resp.statusCode().equals(HttpStatus.OK))
                return resp.bodyToMono(TicketInfo.class);
              else if (resp.statusCode().equals(HttpStatus.NOT_FOUND))
                return Mono.error(new OrderNotFoundException());
              else
                return Mono.error(new RuntimeException("Unknown: " + resp.statusCode()));
            });
  }
}
