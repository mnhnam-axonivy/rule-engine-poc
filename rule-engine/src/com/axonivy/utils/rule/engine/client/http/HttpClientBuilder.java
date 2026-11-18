package com.axonivy.utils.rule.engine.client.http;

import java.time.Duration;

public interface HttpClientBuilder {

  Duration connectTimeout();

  HttpClientBuilder connectTimeout(Duration timeout);

  Duration readTimeout();

  /**
   * @param timeout the read timeout. <code>null</code> means the default of the HTTP client.
   * @return this builder
   */
  HttpClientBuilder readTimeout(Duration timeout);

  HttpClient build();
}
