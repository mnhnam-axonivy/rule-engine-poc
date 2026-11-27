package com.axonivy.utils.rule.engine.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.axonivy.utils.rule.engine.api.Message;
import com.axonivy.utils.rule.engine.api.ValidationResult;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineException;
import com.axonivy.utils.rule.engine.client.internal.ExceptionMapper;
import com.axonivy.utils.rule.engine.client.internal.JitDmn;
import com.axonivy.utils.rule.engine.client.internal.RequestLogFilter;
import com.axonivy.utils.rule.engine.client.internal.RuntimeUrlFeature;

import ch.ivyteam.ivy.environment.Ivy;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;

/**
 * Client of the DMN engine container, the Apache KIE JIT runner, through the Axon Ivy REST client
 * <code>ruleEngine</code> of rest-clients.yaml. The engine keeps nothing: every call carries the DMN file.
 */
public class RuleEngineClient {

  private static final String REST_CLIENT = "ruleEngine";
  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

  private interface Paths {
    String VALIDATE = "jitdmn/validate";
    String EVALUATE = "jitdmn/dmnresult";
    String READY = "q/health/ready";
  }

  private interface Headers {
    String REQUESTED_BY = "X-Requested-By";
    String REQUESTED_BY_IVY = "ivy";
  }

  private interface JerseyProperties {
    String CONNECT_TIMEOUT = "jersey.config.client.connectTimeout";
    String READ_TIMEOUT = "jersey.config.client.readTimeout";
  }

  private final String baseUrl;
  private final int connectTimeoutMillis;
  private final int readTimeoutMillis;
  private final boolean logRequests;

  private RuleEngineClient(Builder builder) {
    if (builder.baseUrl == null || builder.baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl cannot be null or blank");
    }
    this.baseUrl = builder.baseUrl.trim();
    this.connectTimeoutMillis = millis(builder.connectTimeout, DEFAULT_CONNECT_TIMEOUT);
    this.readTimeoutMillis = millis(builder.readTimeout, DEFAULT_READ_TIMEOUT);
    this.logRequests = builder.logRequests;
  }

  public static Builder builder() {
    return new Builder();
  }

  public ValidationResult validate(String dmn) {
    var body = Entity.entity(dmn == null ? "" : dmn, MediaType.APPLICATION_XML_TYPE);
    var findings = call(() -> request(target(Paths.VALIDATE)).post(body, new GenericType<List<JitDmn.Finding>>() {}));
    var messages = JitDmn.messages(findings);
    return new ValidationResult(messages.stream().noneMatch(Message::isError), messages);
  }

  /**
   * @throws com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException INVALID_MODEL if the engine cannot
   *         read the DMN file
   */
  public DmnResult evaluate(String dmn, Map<String, Object> input) {
    var body = Entity.json(new JitDmn.Request(dmn, input == null ? Map.of() : input));
    return JitDmn.toResult(call(() -> request(target(Paths.EVALUATE)).post(body, JitDmn.Result.class)));
  }

  public boolean isReady() {
    try {
      call(() -> request(target(Paths.READY)).get(String.class));
      return true;
    } catch (RuleEngineException ex) {
      return false;
    }
  }

  public String baseUrl() {
    return baseUrl;
  }

  private WebTarget target(String path) {
    var target = Ivy.rest().client(REST_CLIENT).path(path);
    return logRequests ? target.register(new RequestLogFilter()) : target;
  }

  private Invocation.Builder request(WebTarget target) {
    return target.request(MediaType.APPLICATION_JSON)
        // the URL of the REST client in rest-clients.yaml is a placeholder: RuntimeUrlFeature replaces it by baseUrl
        .property(RuntimeUrlFeature.BASE_URI, baseUrl)
        .property(JerseyProperties.CONNECT_TIMEOUT, connectTimeoutMillis)
        .property(JerseyProperties.READ_TIMEOUT, readTimeoutMillis)
        // Axon Ivy REST services, like the stand-in of the tests, refuse modifying calls without it (CSRF protection)
        .header(Headers.REQUESTED_BY, Headers.REQUESTED_BY_IVY);
  }

  private <T> T call(Supplier<T> call) {
    try {
      return call.get();
    } catch (WebApplicationException ex) {
      throw ExceptionMapper.answer(ex.getResponse());
    } catch (ProcessingException ex) {
      throw ExceptionMapper.map(baseUrl, ex);
    }
  }

  private static int millis(Duration duration, Duration fallback) {
    Duration value = duration == null ? fallback : duration;
    return (int) Math.min(Integer.MAX_VALUE, value.toMillis());
  }

  public static final class Builder {

    private String baseUrl;
    private Duration connectTimeout;
    private Duration readTimeout;
    private boolean logRequests;

    private Builder() {}

    /** @param baseUrl e.g. "http://localhost:8090" */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    public Builder connectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
      return this;
    }

    public Builder readTimeout(Duration readTimeout) {
      this.readTimeout = readTimeout;
      return this;
    }

    public Builder logRequests(boolean logRequests) {
      this.logRequests = logRequests;
      return this;
    }

    public RuleEngineClient build() {
      return new RuleEngineClient(this);
    }
  }
}
