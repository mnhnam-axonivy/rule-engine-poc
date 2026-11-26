package com.axonivy.utils.rule.engine.client.process;

import java.net.URI;

import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;

/**
 * Sends a call of a REST element to the runtime named by the property {@value #BASE_URI}, i.e. the variable
 * <code>RuleEngine.Url</code>. The path of the element is kept. Registered on the client <code>ruleEngine</code> in
 * rest-clients.yaml, whose own Url must have no path.
 */
public class RuntimeUrlFeature implements Feature {

  public static final String BASE_URI = "ruleengine.base.uri";

  @Override
  public boolean configure(FeatureContext context) {
    context.register(new BaseUriFilter(), Priorities.AUTHENTICATION);
    return true;
  }

  static class BaseUriFilter implements ClientRequestFilter {

    @Override
    public void filter(ClientRequestContext context) {
      Object base = context.getProperty(BASE_URI);
      if (base == null) {
        base = context.getConfiguration().getProperty(BASE_URI);
      }
      if (base == null || base.toString().isBlank()) {
        return;
      }
      String prefix = base.toString().trim();
      while (prefix.endsWith("/")) {
        prefix = prefix.substring(0, prefix.length() - 1);
      }
      URI uri = context.getUri();
      String path = uri.getRawPath() == null ? "" : uri.getRawPath();
      String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
      context.setUri(URI.create(prefix + path + query));
    }
  }
}
