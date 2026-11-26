package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
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
import com.axonivy.utils.rule.engine.test.mock.MockRuntime;

import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import jakarta.ws.rs.core.Response;

/**
 * The client of the decision runtime: requests, answers and failures. Each call runs a REST element of the callable
 * RuleEngine/RuntimeCall; the runtime is replaced by {@link MockRuntime}.
 */
@IvyProcessTest(enableWebServer = true)
class TestRuleEngineClient {

  private static final EvaluateRequest REQUEST = EvaluateRequest.builder().decision("discount").build();

  @BeforeEach
  void setup() {
    MockRuntime.reset();
  }

  private static RuleEngineClient client() {
    return RuleEngineClient.builder()
        .baseUrl(MockRuntime.url())
        .apiKey("secret")
        .readTimeout(Duration.ofSeconds(3))
        .build();
  }

  @Test
  void evaluate_sendsModelDecisionAndInput() {
    MockRuntime.onEvaluate(call -> MockRuntime.json(200, """
        {"model":"discount","version":4,"decision":"discount","result":15,"results":{"discount":15},
         "messages":[{"severity":"WARN","message":"careful"}],"somethingNew":true}
        """));

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

    var call = MockRuntime.calls("evaluate").get(0);
    assertThat(call.model()).isEqualTo("discount");
    assertThat(call.apiKey()).isEqualTo("secret");
    assertThat(call.body().get("decision").asString()).isEqualTo("discount");
    assertThat(call.body().get("input").get("purchaseAmount").asInt()).isEqualTo(1500);
    assertThat(call.body().get("input").get("type").isNull()).as("null inputs are sent").isTrue();
  }

  @Test
  void versions_areManaged() {
    MockRuntime.onRegister(call -> MockRuntime.json(201, """
        {"model":"discount","version":2,"status":"DRAFT","source":"PORTAL","author":"rule.editor",
         "createdAt":"2026-11-15T06:04:04.722313Z","previousVersion":1,"created":true}
        """));
    var client = client();

    var registered = client.registerVersion("discount", RegisterVersionRequest.builder()
        .content("<definitions/>").author("rule.editor").source(VersionSource.PORTAL).activate(false).build());
    assertThat(registered.version()).isEqualTo(2);
    assertThat(registered.status()).isEqualTo(VersionStatus.DRAFT);
    assertThat(registered.source()).isEqualTo(VersionSource.PORTAL);
    assertThat(registered.createdAt().getYear()).isEqualTo(2026);
    assertThat(registered.previousVersion()).isEqualTo(1);
    var register = MockRuntime.calls("register").get(0);
    assertThat(register.model()).isEqualTo("discount");
    assertThat(register.body().get("content").asString()).isEqualTo("<definitions/>");
    assertThat(register.body().get("activate").asBoolean()).isFalse();

    assertThat(client.approve("discount", 2).status()).isEqualTo(VersionStatus.APPROVED);
    assertThat(client.activate("discount", 2, ActivateRequest.now("rule.approver")).version()).isEqualTo(2);
    assertThatThrownBy(() -> client.rollback("discount", "rule approver"))
        .as("the mock has no version to roll back to")
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_STATE"));
    assertThat(client.retire("discount", 1).status()).isEqualTo(VersionStatus.RETIRED);

    assertThat(MockRuntime.calls()).extracting(MockRuntime.Call::operation)
        .containsExactly("register", "approve", "activate", "rollback", "retire");
    assertThat(MockRuntime.calls("activate").get(0).body().get("user").asString()).isEqualTo("rule.approver");
    assertThat(MockRuntime.calls("rollback").get(0).body().get("user").asString())
        .as("the query parameter is encoded").isEqualTo("rule approver");
  }

  @Test
  void everyOperation_reachesItsEndpoint() {
    MockRuntime.model("discount", "discount", "amount");
    MockRuntime.versions("discount", "[]");
    MockRuntime.content("discount", 1, "<definitions/>");
    MockRuntime.editorScript("console.log('editor');");
    MockRuntime.onEvaluate(call -> MockRuntime.results(1, "{\"discount\":15}"));
    var client = client();

    assertThat(client.evaluate("discount", 1, REQUEST).results()).containsEntry("discount", 15);
    assertThat(client.validate("<definitions/>").valid()).isTrue();
    assertThat(client.models()).extracting(info -> info.name()).containsExactly("discount");
    assertThat(client.model("discount").activeVersion()).isEqualTo(1);
    assertThat(client.versions("discount")).isEmpty();
    assertThat(client.version("discount", 1).status()).isEqualTo(VersionStatus.ACTIVE);
    assertThat(client.content("discount", 1)).isEqualTo("<definitions/>");
    assertThat(client.editorScript()).isEqualTo("console.log('editor');");
    assertThat(client.isReady()).isTrue();
    client.deleteModel("discount");

    assertThat(MockRuntime.calls()).extracting(MockRuntime.Call::operation).containsExactly(
        "evaluateVersion", "validate", "models", "model", "versions", "version", "content", "editorScript", "delete");
    assertThat(MockRuntime.calls("evaluateVersion").get(0).body().get("version").asInt()).isEqualTo(1);
    assertThat(MockRuntime.calls("validate").get(0).body().get("content").asString()).isEqualTo("<definitions/>");
    assertThat(MockRuntime.calls()).filteredOn(call -> !call.operation().equals("editorScript"))
        .extracting(MockRuntime.Call::apiKey).containsOnly("secret");

    MockRuntime.editorScript(null);
    assertThat(client.editorScript()).as("the runtime serves no editor").isNull();
  }

  @Test
  void errorAnswer_becomesApiException() {
    MockRuntime.onEvaluate(call -> MockRuntime.error(422, "EVALUATION_FAILED", "Evaluation failed: type mismatch",
        "type mismatch"));
    assertThatThrownBy(() -> client().evaluate("discount", REQUEST))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.statusCode()).isEqualTo(422);
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.EVALUATION_FAILED);
          assertThat(ex.getMessage()).isEqualTo("Evaluation failed: type mismatch");
          assertThat(ex.details()).hasSize(1);
          assertThat(ex.isModelMissing()).isFalse();
        });

    MockRuntime.onEvaluate(call -> Response.status(502).type("text/html").entity("<html>Bad Gateway</html>").build());
    assertThatThrownBy(() -> client().evaluate("discount", REQUEST))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.statusCode()).isEqualTo(502);
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.UNKNOWN);
          assertThat(ex.getMessage()).contains("502").contains("Bad Gateway");
        });

    // the mock answers 404 MODEL_NOT_FOUND for a model it does not know
    assertThatThrownBy(() -> client().model("x"))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> assertThat(ex.isModelMissing()).isTrue());
  }

  @Test
  void transportFailures_becomeTimeoutOrUnreachable() {
    MockRuntime.onEvaluate(call -> {
      sleep(3000);
      return MockRuntime.results(1, "{}");
    });
    var impatient = RuleEngineClient.builder().baseUrl(MockRuntime.url()).readTimeout(Duration.ofSeconds(1)).build();
    assertThatThrownBy(() -> impatient.evaluate("discount", REQUEST))
        .isInstanceOf(RuleEngineTimeoutException.class)
        .hasMessageContaining(MockRuntime.url());

    var nobody = RuleEngineClient.builder().baseUrl("http://localhost:9").connectTimeout(Duration.ofSeconds(2)).build();
    assertThatThrownBy(() -> nobody.model("discount"))
        .isInstanceOf(RuleEngineUnreachableException.class)
        .hasMessageContaining("http://localhost:9")
        .hasMessageContaining("RuleEngine.Url");
    assertThat(nobody.isReady()).isFalse();
  }

  @Test
  void withoutApiKey_theHeaderIsEmpty() {
    MockRuntime.onEvaluate(call -> MockRuntime.results(1, "{}"));

    RuleEngineClient.builder().baseUrl(MockRuntime.url()).build().evaluate("discount", REQUEST);

    // a REST element sends a header whose value is null as empty header; the runtime treats both as no key
    assertThat(MockRuntime.calls("evaluate").get(0).apiKey()).isNullOrEmpty();
  }

  @Test
  void baseUrlIsRequired() {
    assertThatThrownBy(() -> RuleEngineClient.builder().build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseUrl");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
