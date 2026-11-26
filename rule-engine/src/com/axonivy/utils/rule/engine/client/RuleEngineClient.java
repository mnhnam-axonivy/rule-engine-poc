package com.axonivy.utils.rule.engine.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;

import com.axonivy.utils.rule.engine.api.ActivateRequest;
import com.axonivy.utils.rule.engine.api.EvaluateRequest;
import com.axonivy.utils.rule.engine.api.EvaluateResponse;
import com.axonivy.utils.rule.engine.api.ModelInfo;
import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.ValidationResult;
import com.axonivy.utils.rule.engine.client.http.HttpClientBuilder;

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
 * All methods throw a {@link com.axonivy.utils.rule.engine.client.exception.RuleEngineException}:
 * unreachable, timeout or an error answer of the runtime.
 */
public abstract class RuleEngineClient {

  /** Evaluates the active version of a model. */
  public abstract EvaluateResponse evaluate(String model, EvaluateRequest request);

  /** Evaluates a specific version, e.g. to test a draft before it goes live. */
  public abstract EvaluateResponse evaluate(String model, int version, EvaluateRequest request);

  /** Stores a DMN file as a new version. Content that is already stored does not create another version. */
  public abstract ModelVersion registerVersion(String model, RegisterVersionRequest request);

  /** Checks a DMN file with the DMN validator without storing it. */
  public abstract ValidationResult validate(String dmn);

  public abstract List<ModelInfo> models();

  public abstract ModelInfo model(String model);

  /** @return the versions of a model, newest first */
  public abstract List<ModelVersion> versions(String model);

  public abstract ModelVersion version(String model, int version);

  /** @return the DMN file of a version, e.g. to export it back into the Ivy project */
  public abstract String content(String model, int version);

  /** Releases a draft for activation. */
  public abstract ModelVersion approve(String model, int version);

  /** Makes a version the active one, now or at an effective date. Live on the runtime without restart. */
  public abstract ModelVersion activate(String model, int version, ActivateRequest request);

  /** Withdraws a version that is not active. */
  public abstract ModelVersion retire(String model, int version);

  /** Activates the version that was active before the current one. */
  public abstract ModelVersion rollback(String model, String user);

  /** Removes a model with all its versions. */
  public abstract void deleteModel(String model);

  /** @return true if the runtime is up and has loaded its active versions */
  public abstract boolean isReady();

  /** @return the base URL of the runtime this client talks to */
  public abstract String baseUrl();

  public static Builder<?, ?> builder() {
    return DefaultRuleEngineClient.builder();
  }

  @SuppressWarnings("unchecked")
  public abstract static class Builder<T extends RuleEngineClient, B extends Builder<T, B>> {

    public HttpClientBuilder httpClientBuilder;
    public String baseUrl;
    public String apiKey;
    public Duration connectTimeout;
    public Duration readTimeout;
    public boolean logRequests;
    public boolean logResponses;
    public Logger logger;
    public Supplier<Map<String, String>> customHeadersSupplier;

    public abstract T build();

    /**
     * @param httpClientBuilder the HTTP transport, e.g. a fake one in tests. Default: the REST client of Axon Ivy.
     */
    public B httpClientBuilder(HttpClientBuilder httpClientBuilder) {
      this.httpClientBuilder = httpClientBuilder;
      return (B) this;
    }

    /** @param baseUrl base URL of the runtime, e.g. "http://localhost:8090" */
    public B baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return (B) this;
    }

    /** @param apiKey shared secret of the runtime, sent as header "X-API-Key" */
    public B apiKey(String apiKey) {
      this.apiKey = apiKey;
      return (B) this;
    }

    public B connectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
      return (B) this;
    }

    /** @param readTimeout how long to wait for the answer of the runtime */
    public B readTimeout(Duration readTimeout) {
      this.readTimeout = readTimeout;
      return (B) this;
    }

    public B logRequests(Boolean logRequests) {
      this.logRequests = Boolean.TRUE.equals(logRequests);
      return (B) this;
    }

    public B logResponses(Boolean logResponses) {
      this.logResponses = Boolean.TRUE.equals(logResponses);
      return (B) this;
    }

    public B logger(Logger logger) {
      this.logger = logger;
      return (B) this;
    }

    /** @param customHeaders headers to be added to each request */
    public B customHeaders(Map<String, String> customHeaders) {
      this.customHeadersSupplier = () -> customHeaders;
      return (B) this;
    }

    /** @param customHeadersSupplier called before each request, e.g. for tokens that expire */
    public B customHeaders(Supplier<Map<String, String>> customHeadersSupplier) {
      this.customHeadersSupplier = customHeadersSupplier;
      return (B) this;
    }
  }
}
