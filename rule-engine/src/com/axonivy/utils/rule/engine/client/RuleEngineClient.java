package com.axonivy.utils.rule.engine.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import com.axonivy.utils.rule.engine.client.internal.Json;
import com.axonivy.utils.rule.engine.client.internal.RuntimeUrlFeature;

import ch.ivyteam.ivy.environment.Ivy;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tools.jackson.core.type.TypeReference;

/**
 * Client of the decision runtime of the Axon Ivy Rules Engine.
 *
 * <pre><code>
 * RuleEngineClient client = RuleEngineClient.builder()
 *     .baseUrl("http://localhost:8090")
 *     .apiKey("secret")
 *     .readTimeout(Duration.ofSeconds(10))
 *     .build();
 * EvaluateResponse response = client.evaluate("discount", EvaluateRequest.builder()
 *     .decision("discount")
 *     .input("purchaseAmount", 1500)
 *     .input("type", "Gold")
 *     .build());
 * </code></pre>
 *
 * Each operation calls the runtime through the REST client {@value #REST_CLIENT} of rest-clients.yaml, the REST
 * client of Axon Ivy: the calls appear in the tracing of the engine, and rest-clients.yaml is the place for TLS
 * settings. All methods throw a {@link RuleEngineException}: unreachable, timeout or an error answer of the runtime.
 */
public class RuleEngineClient {

  /** Name of the REST client in rest-clients.yaml. */
  public static final String REST_CLIENT = "ruleEngine";

  private static final String CONNECT_TIMEOUT = "jersey.config.client.connectTimeout";
  private static final String READ_TIMEOUT = "jersey.config.client.readTimeout";
  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);
  private static final Logger LOG = LoggerFactory.getLogger(RuleEngineClient.class);

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

  /** Evaluates the active version of a model. */
  public EvaluateResponse evaluate(String model, EvaluateRequest request) {
    String answer = request("api/models/{model}/evaluate").model(model).post(Json.toJson(request));
    return Json.fromJson(answer, EvaluateResponse.class);
  }

  /** Evaluates a specific version, e.g. to test a draft before it goes live. */
  public EvaluateResponse evaluate(String model, int version, EvaluateRequest request) {
    String answer = request("api/models/{model}/versions/{version}/evaluate").model(model).version(version)
        .post(Json.toJson(request));
    return Json.fromJson(answer, EvaluateResponse.class);
  }

  /** Stores a DMN file as a new version. Content that is already stored does not create another version. */
  public ModelVersion registerVersion(String model, RegisterVersionRequest request) {
    String answer = request("api/models/{model}/versions").model(model).post(Json.toJson(request));
    return Json.fromJson(answer, ModelVersion.class);
  }

  /** Checks a DMN file with the DMN validator without storing it. */
  public ValidationResult validate(String dmn) {
    String body = Json.toJson(Map.of("content", dmn == null ? "" : dmn));
    return Json.fromJson(request("api/validate").post(body), ValidationResult.class);
  }

  public List<ModelInfo> models() {
    return Json.fromJson(request("api/models").get(), new TypeReference<List<ModelInfo>>() {});
  }

  public ModelInfo model(String model) {
    return Json.fromJson(request("api/models/{model}").model(model).get(), ModelInfo.class);
  }

  /** @return the versions of a model, newest first */
  public List<ModelVersion> versions(String model) {
    return Json.fromJson(request("api/models/{model}/versions").model(model).get(),
        new TypeReference<List<ModelVersion>>() {});
  }

  public ModelVersion version(String model, int version) {
    String answer = request("api/models/{model}/versions/{version}").model(model).version(version).get();
    return Json.fromJson(answer, ModelVersion.class);
  }

  /** @return the DMN file of a version, e.g. to export it back into the Ivy project */
  public String content(String model, int version) {
    return request("api/models/{model}/versions/{version}/content").model(model).version(version)
        .accept(MediaType.APPLICATION_XML).get();
  }

  /** Releases a draft for activation. */
  public ModelVersion approve(String model, int version) {
    String answer = request("api/models/{model}/versions/{version}/approve").model(model).version(version).post("");
    return Json.fromJson(answer, ModelVersion.class);
  }

  /** Makes a version the active one, now or at an effective date. Live on the runtime without restart. */
  public ModelVersion activate(String model, int version, ActivateRequest request) {
    String body = Json.toJson(request == null ? ActivateRequest.now(null) : request);
    String answer = request("api/models/{model}/versions/{version}/activate").model(model).version(version).post(body);
    return Json.fromJson(answer, ModelVersion.class);
  }

  /** Withdraws a version that is not active. */
  public ModelVersion retire(String model, int version) {
    String answer = request("api/models/{model}/versions/{version}/retire").model(model).version(version).post("");
    return Json.fromJson(answer, ModelVersion.class);
  }

  /** Activates the version that was active before the current one. */
  public ModelVersion rollback(String model, String user) {
    String answer = request("api/models/{model}/rollback").model(model).query("user", user).post("");
    return Json.fromJson(answer, ModelVersion.class);
  }

  /** Removes a model with all its versions. */
  public void deleteModel(String model) {
    request("api/models/{model}").model(model).delete();
  }

  /** @return true if the runtime is up and has loaded its active versions */
  public boolean isReady() {
    try {
      request("q/health/ready").get();
      return true;
    } catch (RuleEngineException ex) {
      return false;
    }
  }

  /** @return the script of the DMN editor that the runtime serves for its page /editor/, or null if it has none */
  public String editorScript() {
    try {
      return request("editor/lib/dmn-editor.js").accept(MediaType.WILDCARD).get();
    } catch (RuleEngineApiException ex) {
      if (ex.statusCode() == 404) {
        return null;
      }
      throw ex;
    }
  }

  /** @return the base URL of the runtime this client talks to */
  public String baseUrl() {
    return baseUrl;
  }

  private Request request(String path) {
    return new Request(path);
  }

  private static String name(String model) {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model cannot be null or blank");
    }
    return model.trim();
  }

  private static int millis(Duration duration, Duration fallback) {
    Duration value = duration == null ? fallback : duration;
    return (int) Math.min(Integer.MAX_VALUE, value.toMillis());
  }

  /**
   * One call of the runtime. The REST client of rest-clients.yaml has a placeholder URL: {@link RuntimeUrlFeature}
   * sends the call to the base URL of this client and keeps the path.
   */
  private final class Request {

    private final String path;
    private WebTarget target;
    private String accept = MediaType.APPLICATION_JSON;

    private Request(String path) {
      this.path = path;
      this.target = Ivy.rest().client(REST_CLIENT).path(path);
    }

    private Request model(String model) {
      target = target.resolveTemplate("model", name(model));
      return this;
    }

    private Request version(int version) {
      target = target.resolveTemplate("version", version);
      return this;
    }

    private Request query(String name, Object value) {
      if (value != null) {
        target = target.queryParam(name, value);
      }
      return this;
    }

    private Request accept(String mediaType) {
      accept = mediaType;
      return this;
    }

    private String get() {
      return send("GET", null);
    }

    private String delete() {
      return send("DELETE", null);
    }

    private String post(String json) {
      return send("POST", Entity.entity(json, MediaType.APPLICATION_JSON));
    }

    /** @return the body of a successful answer */
    private String send(String method, Entity<String> body) {
      if (logRequests) {
        LOG.info("Rule engine request: {} {} {}", method, path, body == null ? "" : body.getEntity());
      }
      int status;
      String answer;
      try {
        var invocation = target
            .property(RuntimeUrlFeature.BASE_URI, baseUrl)
            .property(CONNECT_TIMEOUT, connectTimeoutMillis)
            .property(READ_TIMEOUT, readTimeoutMillis)
            .request(accept)
            .header("X-Requested-By", "ivy");
        if (apiKey != null) {
          invocation = invocation.header("X-API-Key", apiKey);
        }
        try (Response response = body == null ? invocation.method(method) : invocation.method(method, body)) {
          status = response.getStatus();
          answer = response.hasEntity() ? response.readEntity(String.class) : null;
        }
      } catch (RuntimeException ex) {
        throw ExceptionMapper.map(baseUrl, ex);
      }
      if (logRequests) {
        LOG.info("Rule engine response: {} {}", status, answer);
      }
      if (status < 200 || status > 299) {
        throw ExceptionMapper.answer(status, answer);
      }
      return answer;
    }
  }

  public static final class Builder {

    private String baseUrl;
    private String apiKey;
    private Duration connectTimeout;
    private Duration readTimeout;
    private boolean logRequests;

    private Builder() {}

    /** @param baseUrl base URL of the runtime, e.g. "http://localhost:8090" */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /** @param apiKey shared secret of the runtime, sent as header "X-API-Key" */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /** @param connectTimeout how long to wait for the connection. Default: 5 seconds. */
    public Builder connectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
      return this;
    }

    /** @param readTimeout how long to wait for the answer of the runtime. Default: 10 seconds. */
    public Builder readTimeout(Duration readTimeout) {
      this.readTimeout = readTimeout;
      return this;
    }

    /** @param logRequests true logs every request and its response; the API key is never logged */
    public Builder logRequests(boolean logRequests) {
      this.logRequests = logRequests;
      return this;
    }

    public RuleEngineClient build() {
      return new RuleEngineClient(this);
    }
  }
}
