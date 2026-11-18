package com.axonivy.utils.rule.engine.client.http;

/**
 * Service provider interface for the HTTP transport. Register an implementation in
 * <code>META-INF/services/com.axonivy.utils.rule.engine.client.http.HttpClientBuilderFactory</code>.
 */
public interface HttpClientBuilderFactory {

  int DEFAULT_PRIORITY = 0;

  HttpClientBuilder create();

  /**
   * @return the priority used when several factories are found. Higher values take precedence.
   */
  default int priority() {
    return DEFAULT_PRIORITY;
  }
}
