package com.axonivy.utils.rule.engine.client;

import static com.axonivy.utils.rule.engine.client.http.HttpMethod.DELETE;
import static com.axonivy.utils.rule.engine.client.http.HttpMethod.GET;
import static com.axonivy.utils.rule.engine.client.http.HttpMethod.POST;
import static com.axonivy.utils.rule.engine.client.internal.ExceptionMapper.withExceptionMapper;

import java.time.Duration;
import java.util.HashMap;
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
import com.axonivy.utils.rule.engine.client.exception.RuleEngineException;
import com.axonivy.utils.rule.engine.client.http.HttpClient;
import com.axonivy.utils.rule.engine.client.http.HttpClientBuilder;
import com.axonivy.utils.rule.engine.client.http.HttpMethod;
import com.axonivy.utils.rule.engine.client.http.HttpRequest;
import com.axonivy.utils.rule.engine.client.http.SuccessfulHttpResponse;
import com.axonivy.utils.rule.engine.client.http.ivy.IvyHttpClientBuilder;
import com.axonivy.utils.rule.engine.client.http.log.LoggingHttpClient;
import com.axonivy.utils.rule.engine.client.internal.Json;

import tools.jackson.core.type.TypeReference;

public class DefaultRuleEngineClient extends RuleEngineClient {

  public static final String API_KEY_HEADER = "X-API-Key";
  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient;
  private final String baseUrl;
  private final Map<String, String> defaultHeaders;
  private final Supplier<Map<String, String>> customHeadersSupplier;

  public DefaultRuleEngineClient(Builder builder) {
    if (builder.baseUrl == null || builder.baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl cannot be null or blank");
    }
    this.baseUrl = builder.baseUrl.trim();
    HttpClientBuilder httpClientBuilder = builder.httpClientBuilder != null
        ? builder.httpClientBuilder
        : new IvyHttpClientBuilder();
    HttpClient client = httpClientBuilder
        .connectTimeout(firstNonNull(builder.connectTimeout, httpClientBuilder.connectTimeout(),
            DEFAULT_CONNECT_TIMEOUT))
        .readTimeout(firstNonNull(builder.readTimeout, httpClientBuilder.readTimeout(), DEFAULT_READ_TIMEOUT))
        .build();
    this.httpClient = builder.logRequests || builder.logResponses
        ? new LoggingHttpClient(client, builder.logRequests, builder.logResponses, builder.logger)
        : client;

    var headers = new HashMap<String, String>();
    headers.put("Accept", "application/json");
    // marks the call as made by a program, as REST services with CSRF protection require it (e.g. Axon Ivy)
    headers.put("X-Requested-By", "ivy");
    if (builder.apiKey != null && !builder.apiKey.isBlank()) {
      headers.put(API_KEY_HEADER, builder.apiKey.trim());
    }
    this.defaultHeaders = headers;
    this.customHeadersSupplier = builder.customHeadersSupplier == null ? Map::of : builder.customHeadersSupplier;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends RuleEngineClient.Builder<DefaultRuleEngineClient, Builder> {

    @Override
    public DefaultRuleEngineClient build() {
      return new DefaultRuleEngineClient(this);
    }
  }

  @Override
  public EvaluateResponse evaluate(String model, EvaluateRequest request) {
    return post(models(model, "evaluate"), request, EvaluateResponse.class);
  }

  @Override
  public EvaluateResponse evaluate(String model, int version, EvaluateRequest request) {
    return post(models(model, "versions", version, "evaluate"), request, EvaluateResponse.class);
  }

  @Override
  public ModelVersion registerVersion(String model, RegisterVersionRequest request) {
    return post(models(model, "versions"), request, ModelVersion.class);
  }

  @Override
  public ValidationResult validate(String dmn) {
    return post("api/validate", Map.of("content", dmn == null ? "" : dmn), ValidationResult.class);
  }

  @Override
  public List<ModelInfo> models() {
    String json = execute(request(GET, "api/models").build()).body();
    return Json.fromJson(json, new TypeReference<List<ModelInfo>>() {});
  }

  @Override
  public ModelInfo model(String model) {
    return get(models(model), ModelInfo.class);
  }

  @Override
  public List<ModelVersion> versions(String model) {
    String json = execute(request(GET, models(model, "versions")).build()).body();
    return Json.fromJson(json, new TypeReference<List<ModelVersion>>() {});
  }

  @Override
  public ModelVersion version(String model, int version) {
    return get(models(model, "versions", version), ModelVersion.class);
  }

  @Override
  public String content(String model, int version) {
    HttpRequest httpRequest = request(GET, models(model, "versions", version, "content"))
        .addHeader("Accept", "application/xml")
        .build();
    return execute(httpRequest).body();
  }

  @Override
  public ModelVersion approve(String model, int version) {
    return post(models(model, "versions", version, "approve"), null, ModelVersion.class);
  }

  @Override
  public ModelVersion activate(String model, int version, ActivateRequest request) {
    var body = request == null ? ActivateRequest.now(null) : request;
    return post(models(model, "versions", version, "activate"), body, ModelVersion.class);
  }

  @Override
  public ModelVersion retire(String model, int version) {
    return post(models(model, "versions", version, "retire"), null, ModelVersion.class);
  }

  @Override
  public ModelVersion rollback(String model, String user) {
    HttpRequest httpRequest = request(POST, models(model, "rollback"))
        .addQueryParam("user", user)
        .build();
    return Json.fromJson(execute(httpRequest).body(), ModelVersion.class);
  }

  @Override
  public void deleteModel(String model) {
    execute(request(DELETE, models(model)).build());
  }

  @Override
  public boolean isReady() {
    try {
      execute(request(GET, "q/health/ready").build());
      return true;
    } catch (RuleEngineException ex) {
      return false;
    }
  }

  @Override
  public String baseUrl() {
    return baseUrl;
  }

  private <T> T get(String path, Class<T> responseType) {
    return Json.fromJson(execute(request(GET, path).build()).body(), responseType);
  }

  private <T> T post(String path, Object body, Class<T> responseType) {
    HttpRequest.Builder httpRequest = request(POST, path);
    if (body != null) {
      httpRequest.addHeader("Content-Type", "application/json").body(Json.toJson(body));
    }
    return Json.fromJson(execute(httpRequest.build()).body(), responseType);
  }

  private HttpRequest.Builder request(HttpMethod method, String path) {
    return HttpRequest.builder()
        .method(method)
        .url(baseUrl, path)
        .addHeaders(defaultHeaders)
        .addHeaders(customHeadersSupplier.get());
  }

  private SuccessfulHttpResponse execute(HttpRequest httpRequest) {
    return withExceptionMapper(baseUrl, () -> httpClient.execute(httpRequest));
  }

  private static String models(String model, Object... segments) {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model cannot be null or blank");
    }
    var path = new StringBuilder("api/models/").append(model.trim());
    for (Object segment : segments) {
      path.append('/').append(segment);
    }
    return path.toString();
  }

  @SafeVarargs
  private static <T> T firstNonNull(T... values) {
    for (T value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }
}
