package net.chrisrichardson.ftgo.apiagateway.proxies;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.validation.constraints.NotNull;

@ConfigurationProperties(prefix = "accounting.destinations")
public class AccountingServiceDestinations {

  @NotNull
  private String accountingServiceUrl;

  public String getAccountingServiceUrl() {
    return accountingServiceUrl;
  }

  public void setAccountingServiceUrl(String accountingServiceUrl) {
    this.accountingServiceUrl = accountingServiceUrl;
  }
}
