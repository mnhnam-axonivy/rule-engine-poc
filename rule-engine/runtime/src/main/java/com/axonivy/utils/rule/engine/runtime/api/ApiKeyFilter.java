package com.axonivy.utils.rule.engine.runtime.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import com.axonivy.utils.rule.engine.runtime.api.model.ErrorResponse;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class ApiKeyFilter {

  public static final String API_KEY_HEADER = "X-API-Key";
  private static final Logger LOG = Logger.getLogger(ApiKeyFilter.class);
  private static final String BEARER = "Bearer ";

  @ConfigProperty(name = "rule.engine.api-key")
  Optional<String> apiKey;

  void onStart(@Observes StartupEvent event) {
    if (configuredKey().isEmpty()) {
      LOG.warn("No API key configured (RULE_ENGINE_API_KEY): the runtime API is open to everyone who can reach it.");
    }
  }

  @ServerRequestFilter
  public Optional<Response> authenticate(ContainerRequestContext request) {
    var expected = configuredKey();
    if (expected.isEmpty() || !request.getUriInfo().getPath().startsWith("/api/")) {
      return Optional.empty();
    }
    var provided = providedKey(request);
    if (provided.isPresent() && matches(expected.get(), provided.get())) {
      return Optional.empty();
    }
    var body = new ErrorResponse("UNAUTHORIZED", "A valid API key is required in the '" + API_KEY_HEADER
        + "' header.", List.of());
    return Optional.of(Response.status(Response.Status.UNAUTHORIZED)
        .type(MediaType.APPLICATION_JSON)
        .entity(body)
        .build());
  }

  private Optional<String> configuredKey() {
    return apiKey.map(String::trim).filter(key -> !key.isEmpty());
  }

  private static Optional<String> providedKey(ContainerRequestContext request) {
    var header = request.getHeaderString(API_KEY_HEADER);
    if (header != null && !header.isBlank()) {
      return Optional.of(header.trim());
    }
    var authorization = request.getHeaderString(HttpHeaders.AUTHORIZATION);
    if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return Optional.of(authorization.substring(BEARER.length()).trim());
    }
    return Optional.empty();
  }

  private static boolean matches(String expected, String provided) {
    return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
  }
}
