package com.axonivy.utils.rule.engine.client.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;

/**
 * Logs each call and its answer, without headers.
 */
public class RequestLogFilter implements ClientRequestFilter, ClientResponseFilter {

  private static final Logger LOG = LoggerFactory.getLogger(RequestLogFilter.class);

  @Override
  public void filter(ClientRequestContext request) {
    Object entity = request.getEntity();
    String body = entity == null ? "" : entity instanceof String text ? text : JsonConverter.toJson(entity);
    LOG.info("Rule engine request: {} {} {}", request.getMethod(), request.getUri(), body);
  }

  @Override
  public void filter(ClientRequestContext request, ClientResponseContext response) throws IOException {
    String body = "";
    if (response.hasEntity()) {
      byte[] bytes = response.getEntityStream().readAllBytes();
      // the stream can be read once: hand the client a fresh one
      response.setEntityStream(new ByteArrayInputStream(bytes));
      body = new String(bytes, StandardCharsets.UTF_8);
    }
    LOG.info("Rule engine response: {} {}", response.getStatus(), body);
  }
}
