package com.axonivy.utils.rule.engine.client.http.ivy;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;

import com.axonivy.utils.rule.engine.client.http.HttpClient;
import com.axonivy.utils.rule.engine.client.http.HttpException;
import com.axonivy.utils.rule.engine.client.http.HttpMethod;
import com.axonivy.utils.rule.engine.client.http.HttpRequest;
import com.axonivy.utils.rule.engine.client.http.SuccessfulHttpResponse;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status.Family;

/**
 * Executes requests with the REST client of Axon Ivy (Jersey). Calls show up in the tracing tools of the
 * engine and administrators can tune the client <code>ruleEngine</code> in rest-clients.yaml, e.g. its TLS setup.
 */
public class IvyHttpClient implements HttpClient {

  static final String REQUEST_URI = "ruleengine.request.uri";

  private final WebTarget target;

  public IvyHttpClient(WebTarget target) {
    this.target = target;
  }

  @Override
  public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException, RuntimeException {
    var headers = new MultivaluedHashMap<String, Object>();
    request.headers().forEach((name, values) -> values.forEach(value -> headers.add(name, value)));
    String contentType = contentType(headers);

    var invocation = target.request()
        .property(REQUEST_URI, request.url())
        .headers(headers);
    boolean hasBody = request.body() != null && request.method() == HttpMethod.POST;
    try (Response response = hasBody
        ? invocation.method(request.method().name(), Entity.entity(request.body(), contentType))
        : invocation.method(request.method().name())) {
      String body = response.hasEntity() ? response.readEntity(String.class) : null;
      if (Family.SUCCESSFUL.equals(response.getStatusInfo().getFamily())) {
        return SuccessfulHttpResponse.builder()
            .statusCode(response.getStatus())
            .headers(response.getStringHeaders())
            .body(body)
            .build();
      }
      throw new HttpException(response.getStatus(), body);
    }
  }

  /**
   * Reads a file that the runtime serves as it is, e.g. the script of its DMN editor.
   *
   * @return the content, or null if the runtime has no such file
   */
  public byte[] download(String url) throws HttpException {
    try (Response response = target.request().property(REQUEST_URI, url).get()) {
      if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
        return null;
      }
      if (!Family.SUCCESSFUL.equals(response.getStatusInfo().getFamily())) {
        throw new HttpException(response.getStatus(), null);
      }
      return response.readEntity(byte[].class);
    }
  }

  private static String contentType(MultivaluedHashMap<String, Object> headers) {
    List<Object> contentType = headers.remove("Content-Type");
    return Optional.ofNullable(contentType)
        .filter(values -> !values.isEmpty())
        .map(values -> values.get(0))
        .map(String::valueOf)
        .orElse(MediaType.APPLICATION_JSON);
  }

  /**
   * Replaces the URI that is configured in rest-clients.yaml by the URI of the request.
   * The base URL of the runtime comes from the variable <code>RuleEngine.Url</code>.
   */
  static class RequestUriFilter implements ClientRequestFilter {

    @Override
    public void filter(ClientRequestContext context) throws IOException {
      Object uri = context.getProperty(REQUEST_URI);
      if (uri != null) {
        context.setUri(URI.create(uri.toString()));
      }
    }
  }
}
