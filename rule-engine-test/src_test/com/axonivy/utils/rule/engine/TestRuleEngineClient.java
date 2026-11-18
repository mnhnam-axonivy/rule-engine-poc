package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.api.ActivateRequest;
import com.axonivy.utils.rule.engine.api.EvaluateRequest;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.api.VersionStatus;
import com.axonivy.utils.rule.engine.client.RuleEngineClient;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineTimeoutException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineUnreachableException;
import com.axonivy.utils.rule.engine.client.http.HttpClient;
import com.axonivy.utils.rule.engine.client.http.HttpClientBuilder;
import com.axonivy.utils.rule.engine.client.http.HttpClientBuilderLoader;
import com.axonivy.utils.rule.engine.client.http.HttpException;
import com.axonivy.utils.rule.engine.client.http.HttpMethod;
import com.axonivy.utils.rule.engine.client.http.HttpRequest;
import com.axonivy.utils.rule.engine.client.http.SuccessfulHttpResponse;
import com.axonivy.utils.rule.engine.client.http.ivy.IvyHttpClientBuilder;
import com.axonivy.utils.rule.engine.client.http.log.LoggingHttpClient;

import ch.ivyteam.ivy.environment.IvyTest;
import jakarta.ws.rs.ProcessingException;

/**
 * The client of the decision runtime: requests, answers and failures. The HTTP transport is replaced.
 */
@IvyTest
class TestRuleEngineClient {

  /** A transport that records the requests and answers what the test wants. */
  static class RecordingHttp implements HttpClientBuilder, HttpClient {

    final List<HttpRequest> requests = new ArrayList<>();
    Function<HttpRequest, String> answer = request -> "{}";
    Duration connectTimeout;
    Duration readTimeout;

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
      requests.add(request);
      return SuccessfulHttpResponse.builder().statusCode(200).body(answer.apply(request)).build();
    }

    @Override
    public Duration connectTimeout() {
      return connectTimeout;
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
      this.connectTimeout = timeout;
      return this;
    }

    @Override
    public Duration readTimeout() {
      return readTimeout;
    }

    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
      this.readTimeout = timeout;
      return this;
    }

    @Override
    public HttpClient build() {
      return this;
    }
  }

  private final RecordingHttp http = new RecordingHttp();

  private RuleEngineClient client() {
    return RuleEngineClient.builder()
        .httpClientBuilder(http)
        .baseUrl("http://rules:8080/")
        .apiKey("secret")
        .readTimeout(Duration.ofSeconds(3))
        .build();
  }

  @Test
  void ivyRestClient_isTheDefaultTransport() {
    assertThat(HttpClientBuilderLoader.loadHttpClientBuilder())
        .as("the Ivy REST client integrates into the tracing tools of the engine")
        .isInstanceOf(IvyHttpClientBuilder.class);
  }

  @Test
  void evaluate_sendsModelDecisionAndInput() {
    http.answer = request -> """
        {"model":"discount","version":4,"decision":"discount","result":15,"results":{"discount":15},
         "messages":[{"severity":"WARN","message":"careful"}],"somethingNew":true}
        """;
    var response = client().evaluate("discount", EvaluateRequest.builder()
        .decision("discount")
        .input("purchaseAmount", 1500)
        .input("type", null)
        .build());

    assertThat(response.model()).isEqualTo("discount");
    assertThat(response.version()).isEqualTo(4);
    assertThat(response.result()).isEqualTo(15);
    assertThat(response.results()).containsEntry("discount", 15);
    assertThat(response.messages()).hasSize(1);

    var request = http.requests.get(0);
    assertThat(request.method()).isEqualTo(HttpMethod.POST);
    assertThat(request.url()).isEqualTo("http://rules:8080/api/models/discount/evaluate");
    assertThat(request.headers()).containsEntry("X-API-Key", List.of("secret"))
        .containsEntry("Content-Type", List.of("application/json"));
    assertThat(request.body()).isEqualTo("{\"decision\":\"discount\",\"input\":{\"purchaseAmount\":1500,\"type\":null}}");
    assertThat(http.readTimeout).isEqualTo(Duration.ofSeconds(3));
    assertThat(http.connectTimeout).as("default").isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void versions_areManaged() {
    http.answer = request -> """
        {"model":"discount","version":2,"status":"ACTIVE","source":"PORTAL","author":"rule.editor",
         "createdAt":"2026-11-15T06:04:04.722313Z","activatedAt":"2026-11-15T07:00:00+01:00","previousVersion":1,"created":true}
        """;
    var client = client();

    var registered = client.registerVersion("discount", RegisterVersionRequest.builder()
        .content("<definitions/>").author("rule.editor").source(VersionSource.PORTAL).activate(false).build());
    assertThat(registered.version()).isEqualTo(2);
    assertThat(registered.status()).isEqualTo(VersionStatus.ACTIVE);
    assertThat(registered.source()).isEqualTo(VersionSource.PORTAL);
    assertThat(registered.createdAt().getYear()).isEqualTo(2026);
    assertThat(registered.previousVersion()).isEqualTo(1);
    assertThat(http.requests.get(0).url()).isEqualTo("http://rules:8080/api/models/discount/versions");
    assertThat(http.requests.get(0).body())
        .isEqualTo("{\"content\":\"<definitions/>\",\"author\":\"rule.editor\",\"source\":\"PORTAL\",\"activate\":false}");

    client.approve("discount", 2);
    client.activate("discount", 2, ActivateRequest.now("rule.approver"));
    client.rollback("discount", "rule approver");
    client.retire("discount", 1);
    assertThat(http.requests).extracting(HttpRequest::url).containsExactly(
        "http://rules:8080/api/models/discount/versions",
        "http://rules:8080/api/models/discount/versions/2/approve",
        "http://rules:8080/api/models/discount/versions/2/activate",
        "http://rules:8080/api/models/discount/rollback?user=rule+approver",
        "http://rules:8080/api/models/discount/versions/1/retire");
    assertThat(http.requests.get(2).body()).isEqualTo("{\"user\":\"rule.approver\"}");

    http.answer = request -> "[]";
    assertThat(client.versions("discount")).isEmpty();
    assertThat(client.models()).isEmpty();
  }

  @Test
  void errorAnswer_becomesApiException() {
    http.answer = request -> {
      throw new HttpException(422, """
          {"code":"EVALUATION_FAILED","message":"Evaluation failed: type mismatch",
           "details":[{"severity":"ERROR","message":"type mismatch","type":"ERROR_EVAL_NODE"}]}
          """);
    };
    assertThatThrownBy(() -> client().evaluate("discount", EvaluateRequest.builder().build()))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.statusCode()).isEqualTo(422);
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.EVALUATION_FAILED);
          assertThat(ex.getMessage()).isEqualTo("Evaluation failed: type mismatch");
          assertThat(ex.details()).hasSize(1);
          assertThat(ex.isModelMissing()).isFalse();
        });

    http.answer = request -> {
      throw new HttpException(502, "<html>Bad Gateway</html>");
    };
    assertThatThrownBy(() -> client().model("discount"))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.statusCode()).isEqualTo(502);
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.UNKNOWN);
          assertThat(ex.getMessage()).contains("502").contains("Bad Gateway");
        });

    http.answer = request -> {
      throw new HttpException(404, "{\"code\":\"MODEL_NOT_FOUND\",\"message\":\"Model 'x' is not loaded.\"}");
    };
    assertThatThrownBy(() -> client().model("x"))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> assertThat(ex.isModelMissing()).isTrue());
  }

  @Test
  void transportFailures_becomeTimeoutOrUnreachable() {
    http.answer = request -> {
      throw new ProcessingException(new SocketTimeoutException("Read timed out"));
    };
    assertThatThrownBy(() -> client().model("discount"))
        .isInstanceOf(RuleEngineTimeoutException.class)
        .hasMessageContaining("http://rules:8080/");

    http.answer = request -> {
      throw new ProcessingException(new ConnectException("Connection refused"));
    };
    assertThatThrownBy(() -> client().model("discount"))
        .isInstanceOf(RuleEngineUnreachableException.class)
        .hasMessageContaining("RuleEngine.Url");

    http.answer = request -> {
      throw new ProcessingException(new SocketTimeoutException("Connect timed out"));
    };
    assertThatThrownBy(() -> client().model("discount")).isInstanceOf(RuleEngineUnreachableException.class);
    assertThat(client().isReady()).isFalse();
  }

  @Test
  void secretsAreMaskedInLogs() {
    var request = HttpRequest.builder().method(HttpMethod.GET).url("http://rules:8080", "api/models")
        .addHeader("X-API-Key", "secret").addHeader("Accept", "application/json").build();
    assertThat(LoggingHttpClient.format(request.headers()))
        .contains("[X-API-Key: ***]")
        .contains("[Accept: application/json]")
        .doesNotContain("secret");
  }

  @Test
  void baseUrlIsRequired() {
    assertThatThrownBy(() -> RuleEngineClient.builder().httpClientBuilder(http).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUrl");
  }
}
