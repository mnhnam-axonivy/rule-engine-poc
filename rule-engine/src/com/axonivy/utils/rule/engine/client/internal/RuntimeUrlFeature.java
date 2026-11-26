package com.axonivy.utils.rule.engine.client.internal;

import java.net.URI;

import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;

/**
 * Sends each call to the base URL in the property {@value #BASE_URI} and keeps its path. Registered on the client
 * <code>ruleEngine</code> in rest-clients.yaml, whose own Url must have no path.
 */
public class RuntimeUrlFeature implements Feature {

  public static final String BASE_URI = "ruleengine.base.uri";

  private static final String PATH_SEPARATOR = "/";
  private static final String QUERY_SEPARATOR = "?";

  @Override
  public boolean configure(FeatureContext context) {
    context.register(new BaseUriFilter(), Priorities.AUTHENTICATION);
    return true;
  }

  static class BaseUriFilter implements ClientRequestFilter {

    @Override
    public void filter(ClientRequestContext context) {
      Object base = context.getProperty(BASE_URI);
      if (base == null || base.toString().isBlank()) {
        return;
      }
      String prefix = base.toString().trim();
      while (prefix.endsWith(PATH_SEPARATOR)) {
        prefix = prefix.substring(0, prefix.length() - 1);
      }
      URI uri = context.getUri();
      String path = uri.getRawPath() == null ? "" : uri.getRawPath();
      String query = uri.getRawQuery() == null ? "" : QUERY_SEPARATOR + uri.getRawQuery();
      context.setUri(URI.create(prefix + path + query));
    }
  }
}
