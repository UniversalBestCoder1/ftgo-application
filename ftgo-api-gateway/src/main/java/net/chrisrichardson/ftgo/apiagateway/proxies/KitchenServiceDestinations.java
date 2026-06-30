package net.chrisrichardson.ftgo.apiagateway.proxies;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.validation.constraints.NotNull;

@ConfigurationProperties(prefix = "kitchen.destinations")
public class KitchenServiceDestinations {

  @NotNull
  private String kitchenServiceUrl;

  public String getKitchenServiceUrl() {
    return kitchenServiceUrl;
  }

  public void setKitchenServiceUrl(String kitchenServiceUrl) {
    this.kitchenServiceUrl = kitchenServiceUrl;
  }
}
