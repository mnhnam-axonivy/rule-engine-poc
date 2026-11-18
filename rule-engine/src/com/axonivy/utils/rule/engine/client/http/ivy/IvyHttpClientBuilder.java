package com.axonivy.utils.rule.engine.client.http.ivy;

import java.time.Duration;

import com.axonivy.utils.rule.engine.client.http.HttpClient;
import com.axonivy.utils.rule.engine.client.http.HttpClientBuilder;

import ch.ivyteam.ivy.environment.Ivy;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.WebTarget;

public class IvyHttpClientBuilder implements HttpClientBuilder {

  /** Name of the REST client in rest-clients.yaml. */
  public static final String REST_CLIENT = "ruleEngine";

  private Duration connectTimeout;
  private Duration readTimeout;

  @Override
  public Duration connectTimeout() {
    return connectTimeout;
  }

  @Override
  public HttpClientBuilder connectTimeout(Duration timeout) {
    this.connectTimeout = timeout;
    return this;
  }

  @Override
  public Duration readTimeout() {
    return readTimeout;
  }

  @Override
  public HttpClientBuilder readTimeout(Duration timeout) {
    this.readTimeout = timeout;
    return this;
  }

  @Override
  public HttpClient build() {
    WebTarget target = Ivy.rest().client(REST_CLIENT)
        .register(new IvyHttpClient.RequestUriFilter(), Priorities.AUTHENTICATION);
    if (connectTimeout != null) {
      target = target.property("jersey.config.client.connectTimeout", millis(connectTimeout));
    }
    if (readTimeout != null) {
      target = target.property("jersey.config.client.readTimeout", millis(readTimeout));
    }
    return new IvyHttpClient(target);
  }

  private static Integer millis(Duration duration) {
    return (int) Math.min(Integer.MAX_VALUE, duration.toMillis());
  }
}
