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
import com.axonivy.utils.rule.engine.client.process.RuntimeConnection;

import ch.ivyteam.ivy.process.call.SubProcessCall;
import ch.ivyteam.ivy.process.call.SubProcessCallResult;
import ch.ivyteam.ivy.process.call.SubProcessCallStartParamCaller;
import tools.jackson.core.type.TypeReference;

public class RuleEngineClient {

  public static final String CALLABLE = "RuleEngine/RuntimeCall";

  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);
  private static final Logger LOG = LoggerFactory.getLogger(RuleEngineClient.class);

  private final String baseUrl;
  private final RuntimeConnection connection;
  private final boolean logRequests;

  private RuleEngineClient(Builder builder) {
    if (builder.baseUrl == null || builder.baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl cannot be null or blank");
    }
    this.baseUrl = builder.baseUrl.trim();
    this.connection = new RuntimeConnection();
    connection.setBaseUrl(baseUrl);
    connection.setApiKey(builder.apiKey == null || builder.apiKey.isBlank() ? null : builder.apiKey.trim());
    connection.setConnectTimeoutMillis(millis(builder.connectTimeout, DEFAULT_CONNECT_TIMEOUT));
    connection.setReadTimeoutMillis(millis(builder.readTimeout, DEFAULT_READ_TIMEOUT));
    this.logRequests = builder.logRequests;
  }

  public static Builder builder() {
    return new Builder();
  }

  public EvaluateResponse evaluate(String model, EvaluateRequest request) {
    return Json.fromJson(call("evaluate", "model", name(model), "body", Json.toJson(request)), EvaluateResponse.class);
  }

  public EvaluateResponse evaluate(String model, int version, EvaluateRequest request) {
    String answer = call("evaluateVersion", "model", name(model), "version", version, "body", Json.toJson(request));
    return Json.fromJson(answer, EvaluateResponse.class);
  }

  public ModelVersion registerVersion(String model, RegisterVersionRequest request) {
    return Json.fromJson(call("registerVersion", "model", name(model), "body", Json.toJson(request)),
        ModelVersion.class);
  }

  public ValidationResult validate(String dmn) {
    String body = Json.toJson(Map.of("content", dmn == null ? "" : dmn));
    return Json.fromJson(call("validate", "body", body), ValidationResult.class);
  }

  public List<ModelInfo> models() {
    return Json.fromJson(call("models"), new TypeReference<List<ModelInfo>>() {});
  }

  public ModelInfo model(String model) {
    return Json.fromJson(call("model", "model", name(model)), ModelInfo.class);
  }

  public List<ModelVersion> versions(String model) {
    return Json.fromJson(call("versions", "model", name(model)), new TypeReference<List<ModelVersion>>() {});
  }

  public ModelVersion version(String model, int version) {
    return Json.fromJson(call("version", "model", name(model), "version", version), ModelVersion.class);
  }

  public String content(String model, int version) {
    return call("content", "model", name(model), "version", version);
  }

  public ModelVersion approve(String model, int version) {
    return Json.fromJson(call("approve", "model", name(model), "version", version), ModelVersion.class);
  }

  public ModelVersion activate(String model, int version, ActivateRequest request) {
    var body = Json.toJson(request == null ? ActivateRequest.now(null) : request);
    return Json.fromJson(call("activate", "model", name(model), "version", version, "body", body), ModelVersion.class);
  }

  public ModelVersion retire(String model, int version) {
    return Json.fromJson(call("retire", "model", name(model), "version", version), ModelVersion.class);
  }

  public ModelVersion rollback(String model, String user) {
    return Json.fromJson(call("rollback", "model", name(model), "user", user), ModelVersion.class);
  }

  public void deleteModel(String model) {
    call("deleteModel", "model", name(model));
  }

  public boolean isReady() {
    try {
      call("ready");
      return true;
    } catch (RuleEngineException ex) {
      return false;
    }
  }

  public String editorScript() {
    try {
      return call("editorScript");
    } catch (RuleEngineApiException ex) {
      if (ex.statusCode() == 404) {
        return null;
      }
      throw ex;
    }
  }

  public String baseUrl() {
    return baseUrl;
  }

  private String call(String start, Object... params) {
    SubProcessCallStartParamCaller caller = SubProcessCall.withPath(CALLABLE)
        .withStartName(start)
        .withParam("connection", connection);
    for (int i = 0; i < params.length; i += 2) {
      caller = caller.withParam((String) params[i], params[i + 1]);
    }
    if (logRequests) {
      LOG.info("Rule engine request: {} {}", start, describe(params));
    }
    SubProcessCallResult result;
    try {
      result = caller.call();
    } catch (RuntimeException ex) {
      throw ExceptionMapper.map(baseUrl, ex);
    }
    Integer status = result.get("status", Integer.class);
    String body = result.get("body", String.class);
    if (logRequests) {
      LOG.info("Rule engine response: {} {}", status, body);
    }
    if (status == null) {
      throw new RuleEngineException("The call '" + start + "' of the rule engine at " + baseUrl + " got no answer.");
    }
    if (status < 200 || status > 299) {
      throw ExceptionMapper.answer(status, body);
    }
    return body;
  }

  private static String describe(Object... params) {
    var text = new StringBuilder();
    for (int i = 0; i < params.length; i += 2) {
      text.append(i == 0 ? "" : ", ").append(params[i]).append('=').append(params[i + 1]);
    }
    return text.toString();
  }

  private static String name(String model) {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model cannot be null or blank");
    }
    return model.trim();
  }

  private static Integer millis(Duration duration, Duration fallback) {
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

    public Builder logRequests(boolean logRequests) {
      this.logRequests = logRequests;
      return this;
    }

    public RuleEngineClient build() {
      return new RuleEngineClient(this);
    }
  }
}
