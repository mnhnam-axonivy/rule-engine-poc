package com.axonivy.utils.rule.engine.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.axonivy.utils.rule.engine.api.ActivateRequest;
import com.axonivy.utils.rule.engine.api.EvaluateRequest;
import com.axonivy.utils.rule.engine.api.EvaluateResponse;
import com.axonivy.utils.rule.engine.api.ModelInfo;
import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.ValidationResult;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineException;
import com.axonivy.utils.rule.engine.client.internal.ExceptionMapper;
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
import jakarta.ws.rs.core.Response.Status;

/**
 * Client of the decision runtime, through the Axon Ivy REST client <code>ruleEngine</code> of rest-clients.yaml.
 * Failures are thrown as {@link RuleEngineException}.
 */
public class RuleEngineClient {

  private static final String REST_CLIENT = "ruleEngine";
  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);
  private static final Entity<String> NO_BODY = Entity.json("");

  private interface Paths {
    String MODELS = "api/models";
    String MODEL = MODELS + "/{" + Params.MODEL + "}";
    String EVALUATE = MODEL + "/evaluate";
    String ROLLBACK = MODEL + "/rollback";
    String VERSIONS = MODEL + "/versions";
    String VERSION = VERSIONS + "/{" + Params.VERSION + "}";
    String EVALUATE_VERSION = VERSION + "/evaluate";
    String CONTENT = VERSION + "/content";
    String APPROVE = VERSION + "/approve";
    String ACTIVATE = VERSION + "/activate";
    String RETIRE = VERSION + "/retire";
    String VALIDATE = "api/validate";
    String READY = "q/health/ready";
    String EDITOR_SCRIPT = "editor/lib/dmn-editor.js";
  }

  private interface Params {
    String MODEL = "model";
    String VERSION = "version";
    String USER = "user";
    String CONTENT = "content";
  }

  private interface Headers {
    String API_KEY = "X-API-Key";
    String REQUESTED_BY = "X-Requested-By";
    String REQUESTED_BY_IVY = "ivy";
  }

  private interface JerseyProperties {
    String CONNECT_TIMEOUT = "jersey.config.client.connectTimeout";
    String READ_TIMEOUT = "jersey.config.client.readTimeout";
  }

  private final String baseUrl;
  private final String apiKey;
  private final int connectTimeoutMillis;
  private final int readTimeoutMillis;
  private final boolean logRequests;

  private RuleEngineClient(Builder builder) {
    if (builder.baseUrl == null || builder.baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl cannot be null or blank");
    }
    this.baseUrl = builder.baseUrl.trim();
    this.apiKey = builder.apiKey == null || builder.apiKey.isBlank() ? null : builder.apiKey.trim();
    this.connectTimeoutMillis = millis(builder.connectTimeout, DEFAULT_CONNECT_TIMEOUT);
    this.readTimeoutMillis = millis(builder.readTimeout, DEFAULT_READ_TIMEOUT);
    this.logRequests = builder.logRequests;
  }

  public static Builder builder() {
    return new Builder();
  }

  public EvaluateResponse evaluate(String model, EvaluateRequest request) {
    return call(() -> json(modelTarget(Paths.EVALUATE, model)).post(Entity.json(request), EvaluateResponse.class));
  }

  public EvaluateResponse evaluate(String model, int version, EvaluateRequest request) {
    return call(() -> json(versionTarget(Paths.EVALUATE_VERSION, model, version))
        .post(Entity.json(request), EvaluateResponse.class));
  }

  public ModelVersion registerVersion(String model, RegisterVersionRequest request) {
    return call(() -> json(modelTarget(Paths.VERSIONS, model)).post(Entity.json(request), ModelVersion.class));
  }

  public ValidationResult validate(String dmn) {
    var body = Entity.json(Map.of(Params.CONTENT, dmn == null ? "" : dmn));
    return call(() -> json(target(Paths.VALIDATE)).post(body, ValidationResult.class));
  }

  public List<ModelInfo> models() {
    return call(() -> json(target(Paths.MODELS)).get(new GenericType<List<ModelInfo>>() {}));
  }

  public ModelInfo model(String model) {
    return call(() -> json(modelTarget(Paths.MODEL, model)).get(ModelInfo.class));
  }

  /** @return newest first */
  public List<ModelVersion> versions(String model) {
    return call(() -> json(modelTarget(Paths.VERSIONS, model)).get(new GenericType<List<ModelVersion>>() {}));
  }

  public ModelVersion version(String model, int version) {
    return call(() -> json(versionTarget(Paths.VERSION, model, version)).get(ModelVersion.class));
  }

  /** @return the DMN file of the version */
  public String content(String model, int version) {
    return call(() -> request(versionTarget(Paths.CONTENT, model, version), MediaType.APPLICATION_XML)
        .get(String.class));
  }

  public ModelVersion approve(String model, int version) {
    return call(() -> json(versionTarget(Paths.APPROVE, model, version)).post(NO_BODY, ModelVersion.class));
  }

  public ModelVersion activate(String model, int version, ActivateRequest request) {
    var body = Entity.json(request == null ? ActivateRequest.now(null) : request);
    return call(() -> json(versionTarget(Paths.ACTIVATE, model, version)).post(body, ModelVersion.class));
  }

  public ModelVersion retire(String model, int version) {
    return call(() -> json(versionTarget(Paths.RETIRE, model, version)).post(NO_BODY, ModelVersion.class));
  }

  public ModelVersion rollback(String model, String user) {
    var target = modelTarget(Paths.ROLLBACK, model);
    var withUser = user == null ? target : target.queryParam(Params.USER, user);
    return call(() -> json(withUser).post(NO_BODY, ModelVersion.class));
  }

  public void deleteModel(String model) {
    call(() -> json(modelTarget(Paths.MODEL, model)).delete(String.class));
  }

  public boolean isReady() {
    try {
      call(() -> json(target(Paths.READY)).get(String.class));
      return true;
    } catch (RuleEngineException ex) {
      return false;
    }
  }

  /** @return null if the runtime serves no DMN editor */
  public String editorScript() {
    try {
      return call(() -> request(target(Paths.EDITOR_SCRIPT), MediaType.WILDCARD).get(String.class));
    } catch (RuleEngineApiException ex) {
      if (ex.statusCode() == Status.NOT_FOUND.getStatusCode()) {
        return null;
      }
      throw ex;
    }
  }

  public String baseUrl() {
    return baseUrl;
  }

  private WebTarget target(String path) {
    var target = Ivy.rest().client(REST_CLIENT).path(path);
    return logRequests ? target.register(new RequestLogFilter()) : target;
  }

  private WebTarget modelTarget(String path, String model) {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model cannot be null or blank");
    }
    return target(path).resolveTemplate(Params.MODEL, model.trim());
  }

  private WebTarget versionTarget(String path, String model, int version) {
    return modelTarget(path, model).resolveTemplate(Params.VERSION, version);
  }

  private Invocation.Builder json(WebTarget target) {
    return request(target, MediaType.APPLICATION_JSON);
  }

  private Invocation.Builder request(WebTarget target, String mediaType) {
    return target.request(mediaType)
        // the URL of the REST client in rest-clients.yaml is a placeholder: RuntimeUrlFeature replaces it by baseUrl
        .property(RuntimeUrlFeature.BASE_URI, baseUrl)
        .property(JerseyProperties.CONNECT_TIMEOUT, connectTimeoutMillis)
        .property(JerseyProperties.READ_TIMEOUT, readTimeoutMillis)
        // Axon Ivy REST services refuse modifying calls without it (CSRF protection)
        .header(Headers.REQUESTED_BY, Headers.REQUESTED_BY_IVY)
        // a null value removes the header: without an API key, none is sent
        .header(Headers.API_KEY, apiKey);
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
    private String apiKey;
    private Duration connectTimeout;
    private Duration readTimeout;
    private boolean logRequests;

    private Builder() {}

    /** @param baseUrl e.g. "http://localhost:8090" */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
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

    /** The API key is never logged. */
    public Builder logRequests(boolean logRequests) {
      this.logRequests = logRequests;
      return this;
    }

    public RuleEngineClient build() {
      return new RuleEngineClient(this);
    }
  }
}
