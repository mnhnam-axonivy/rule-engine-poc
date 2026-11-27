package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.api.EvaluateRequest;
import com.axonivy.utils.rule.engine.api.LoadedVersion;
import com.axonivy.utils.rule.engine.client.RuleEngineClient;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineTimeoutException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineUnreachableException;
import com.axonivy.utils.rule.engine.test.mock.MockRuntime;
import com.axonivy.utils.rule.engine.test.mock.TestStore;

import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import jakarta.ws.rs.core.Response;

/**
 * The client of the decision runtime: requests, answers and failures. Each call goes through the REST client
 * ruleEngine of Axon Ivy; the runtime is replaced by {@link MockRuntime}.
 */
@IvyProcessTest(enableWebServer = true)
class TestRuleEngineClient {

  private static final EvaluateRequest REQUEST = EvaluateRequest.builder().decision("discount").build();
  private static final String DISCOUNT = TestStore.dmn("discount", "discount", "purchaseAmount", "type");

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
  void load_sendsTheDmnFile_andAnswersWhatTheVersionNeeds() {
    LoadedVersion loaded = client().load("discount", 4, DISCOUNT);

    assertThat(loaded.model()).isEqualTo("discount");
    assertThat(loaded.version()).isEqualTo(4);
    assertThat(loaded.contentHash()).isNotBlank();
    assertThat(loaded.decisions()).extracting(LoadedVersion.Decision::name).containsExactly("discount");
    assertThat(loaded.decisions().get(0).requiredInputs()).containsExactly("purchaseAmount", "type");
    var call = MockRuntime.calls("load").get(0);
    assertThat(call.model()).isEqualTo("discount");
    assertThat(call.body().get("version").asInt()).isEqualTo(4);
    assertThat(call.body().get("content").asString()).isEqualTo(DISCOUNT);
    assertThat(call.apiKey()).isEqualTo("secret");
  }

  @Test
  void evaluate_sendsVersionDecisionAndInput() {
    MockRuntime.onEvaluate(call -> MockRuntime.json(200, """
        {"model":"discount","version":4,"decision":"discount","result":15,"results":{"discount":15},
         "messages":[{"severity":"WARN","message":"careful"}],"somethingNew":true}
        """));
    var client = client();
    client.load("discount", 4, DISCOUNT);

    var response = client.evaluate("discount", 4, EvaluateRequest.builder()
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
    assertThat(call.body().get("version").asInt()).isEqualTo(4);
    assertThat(call.apiKey()).isEqualTo("secret");
    assertThat(call.body().get("decision").asString()).isEqualTo("discount");
    assertThat(call.body().get("input").get("purchaseAmount").asInt()).isEqualTo(1500);
    assertThat(call.body().get("input").get("type").isNull()).as("null inputs are sent").isTrue();
  }

  @Test
  void aVersionThatIsNotLoaded_isSaidSo() {
    var client = client();
    assertThatThrownBy(() -> client.evaluate("discount", 1, REQUEST))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.MODEL_NOT_FOUND);
          assertThat(ex.isNotLoaded()).isTrue();
        });

    client.load("discount", 1, DISCOUNT);
    assertThatThrownBy(() -> client.evaluate("discount", 2, REQUEST))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.VERSION_NOT_FOUND);
          assertThat(ex.isNotLoaded()).isTrue();
        });
  }

  @Test
  void everyOperation_reachesItsEndpoint() {
    MockRuntime.editorScript("console.log('editor');");
    MockRuntime.onEvaluate(call -> MockRuntime.results(1, "{\"discount\":15}"));
    var client = client();

    client.load("discount", 1, DISCOUNT);
    assertThat(client.evaluate("discount", 1, REQUEST).results()).containsEntry("discount", 15);
    assertThat(client.loaded()).extracting(LoadedVersion::model).containsExactly("discount");
    assertThat(client.validate("<definitions/>").valid()).isTrue();
    assertThat(client.editorScript()).isEqualTo("console.log('editor');");
    assertThat(client.isReady()).isTrue();
    client.unload("discount");

    assertThat(MockRuntime.calls()).extracting(MockRuntime.Call::operation).containsExactly(
        "load", "evaluate", "loaded", "validate", "editorScript", "unload");
    assertThat(MockRuntime.loaded("discount", 1)).as("unloaded").isNull();
    assertThat(MockRuntime.calls("validate").get(0).body().get("content").asString()).isEqualTo("<definitions/>");
    assertThat(MockRuntime.calls()).filteredOn(call -> !call.operation().equals("editorScript"))
        .extracting(MockRuntime.Call::apiKey).containsOnly("secret");

    MockRuntime.editorScript(null);
    assertThat(client.editorScript()).as("the runtime serves no editor").isNull();
  }

  @Test
  void errorAnswer_becomesApiException() {
    var client = client();
    client.load("discount", 1, DISCOUNT);
    MockRuntime.onEvaluate(call -> MockRuntime.error(422, "EVALUATION_FAILED", "Evaluation failed: type mismatch",
        "type mismatch"));
    assertThatThrownBy(() -> client.evaluate("discount", 1, REQUEST))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.statusCode()).isEqualTo(422);
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.EVALUATION_FAILED);
          assertThat(ex.getMessage()).isEqualTo("Evaluation failed: type mismatch");
          assertThat(ex.details()).hasSize(1);
          assertThat(ex.isNotLoaded()).isFalse();
        });

    MockRuntime.onEvaluate(call -> Response.status(502).type("text/html").entity("<html>Bad Gateway</html>").build());
    assertThatThrownBy(() -> client.evaluate("discount", 1, REQUEST))
        .isInstanceOfSatisfying(RuleEngineApiException.class, ex -> {
          assertThat(ex.statusCode()).isEqualTo(502);
          assertThat(ex.code()).isEqualTo(RuleEngineApiException.UNKNOWN);
          assertThat(ex.getMessage()).contains("502").contains("Bad Gateway");
        });

    // the mock answers INVALID_MODEL for a file it cannot read
    assertThatThrownBy(() -> client.load("discount", 2, "not a dmn file"))
        .isInstanceOfSatisfying(RuleEngineApiException.class,
            ex -> assertThat(ex.code()).isEqualTo(RuleEngineApiException.INVALID_MODEL));
  }

  @Test
  void transportFailures_becomeTimeoutOrUnreachable() {
    MockRuntime.onEvaluate(call -> {
      sleep(3000);
      return MockRuntime.results(1, "{}");
    });
    var impatient = RuleEngineClient.builder().baseUrl(MockRuntime.url()).readTimeout(Duration.ofSeconds(1)).build();
    impatient.load("discount", 1, DISCOUNT);
    assertThatThrownBy(() -> impatient.evaluate("discount", 1, REQUEST))
        .isInstanceOf(RuleEngineTimeoutException.class)
        .hasMessageContaining(MockRuntime.url());

    var nobody = RuleEngineClient.builder().baseUrl("http://localhost:9").connectTimeout(Duration.ofSeconds(2)).build();
    assertThatThrownBy(() -> nobody.load("discount", 1, DISCOUNT))
        .isInstanceOf(RuleEngineUnreachableException.class)
        .hasMessageContaining("http://localhost:9")
        .hasMessageContaining("RuleEngine.Url");
    assertThat(nobody.isReady()).isFalse();
  }

  @Test
  void withoutApiKey_theHeaderIsEmpty() {
    RuleEngineClient.builder().baseUrl(MockRuntime.url()).build().load("discount", 1, DISCOUNT);

    assertThat(MockRuntime.calls("load").get(0).apiKey()).isNullOrEmpty();
  }

  @Test
  void logRequests_keepsRequestAndAnswer() {
    MockRuntime.onEvaluate(call -> MockRuntime.results(3, "{\"discount\":15}"));
    var logging = RuleEngineClient.builder().baseUrl(MockRuntime.url()).logRequests(true).build();

    assertThat(logging.load("discount", 3, DISCOUNT).version()).isEqualTo(3);
    var response = logging.evaluate("discount", 3, REQUEST);

    assertThat(response.version()).isEqualTo(3);
    assertThat(response.results()).containsEntry("discount", 15);
    assertThat(MockRuntime.calls("evaluate").get(0).body().get("decision").asString()).isEqualTo("discount");
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
