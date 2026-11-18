package com.axonivy.utils.rule.engine.client.http.ivy;

import com.axonivy.utils.rule.engine.client.http.HttpClientBuilder;
import com.axonivy.utils.rule.engine.client.http.HttpClientBuilderFactory;

public class IvyHttpClientBuilderFactory implements HttpClientBuilderFactory {

  @Override
  public HttpClientBuilder create() {
    return new IvyHttpClientBuilder();
  }
}
