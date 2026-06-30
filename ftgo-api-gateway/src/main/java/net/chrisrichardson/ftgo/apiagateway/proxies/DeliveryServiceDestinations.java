package net.chrisrichardson.ftgo.apiagateway.proxies;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.validation.constraints.NotNull;

@ConfigurationProperties(prefix = "delivery.destinations")
public class DeliveryServiceDestinations {

  @NotNull
  private String deliveryServiceUrl;

  public String getDeliveryServiceUrl() {
    return deliveryServiceUrl;
  }

  public void setDeliveryServiceUrl(String deliveryServiceUrl) {
    this.deliveryServiceUrl = deliveryServiceUrl;
  }
}
