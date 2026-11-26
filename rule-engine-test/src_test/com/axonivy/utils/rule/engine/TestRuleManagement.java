package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.RuleEngineConfig.Var;
import com.axonivy.utils.rule.engine.portal.DmnEditorScript;
import com.axonivy.utils.rule.engine.test.mock.MockRuntime;

import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.environment.AppFixture;
import ch.ivyteam.ivy.environment.Ivy;
import ch.ivyteam.ivy.process.call.SubProcessCallStartEvent;
import ch.ivyteam.ivy.process.call.SubProcessCallStartParamCaller;
import ch.ivyteam.ivy.process.call.SubProcessSearchFilter;
import ch.ivyteam.ivy.process.call.SubProcessSearchFilter.SearchScope;
import ch.ivyteam.ivy.security.exec.Sudo;
import jakarta.ws.rs.core.Response;

/**
 * The Portal knows nothing of the rule engine but the signatures of Portal/RuleManagement, so they and the JDK types
 * they answer with are a contract.
 */
@IvyProcessTest(enableWebServer = true)
@SuppressWarnings("unchecked")
class TestRuleManagement {

  private static final List<String> SIGNATURES = List.of(
      "ruleManagementInfo()",
      "ruleManagementModels()",
      "ruleManagementVersions(String)",
      "ruleManagementContent(String,Integer)",
      "ruleManagementValidate(String)",
      "ruleManagementSave(String,String,String,String,Boolean)",
      "ruleManagementActivate(String,Integer,String)",
      "ruleManagementRollback(String,String)");

  private static final String DMN = "<definitions xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\" name=\"discount\"/>";

  @BeforeEach
  void setup(AppFixture fixture) {
    MockRuntime.reset();
    DmnEditorScript.forget();
    fixture.var(Var.URL, MockRuntime.url());
    fixture.var(Var.API_KEY, "test-key");
    fixture.var(Var.MODEL_PREFIX, "");
  }

  @Test
  void portalFindsEveryCallInTheSecurityContext() {
    for (String signature : SIGNATURES) {
      assertThat(find(signature)).as(signature).hasSize(1);
    }
  }

  @Test
  void info() {
    Map<String, Object> info = (Map<String, Object>) result(call("ruleManagementInfo()", Map.of()));

    assertThat(info).containsEntry("runtimeUrl", MockRuntime.url()).containsEntry("reachable", true);
  }

  @Test
  void modelsOfTheRuntimeAndFilesOfTheProjects() {
    MockRuntime.model("discount", "discount", "purchaseAmount");

    List<Map<String, Object>> models = (List<Map<String, Object>>) result(call("ruleManagementModels()", Map.of()));

    assertThat(models).extracting(model -> model.get("name"))
        .as("sorted by name: the runtime's models and the .dmn files of rule-engine-test and rule-engine-demo")
        .containsSubsequence("discount", "invoice-approval", "invoice-discount");
    Map<String, Object> discount = byName(models, "discount");
    assertThat(discount).containsEntry("onRuntime", true).containsEntry("activeVersion", 1)
        .containsEntry("versionCount", 1).containsEntry("decisions", List.of("discount"))
        .containsEntry("file", "discount.dmn");
    assertThat((String) discount.get("project")).startsWith("rule-engine-test");
    assertThat(byName(models, "invoice-approval")).containsEntry("onRuntime", false).containsEntry("versionCount", 0);
    Map<String, Object> demo = byName(models, "invoice-discount");
    assertThat(demo).containsEntry("onRuntime", false).containsEntry("file", "invoice-discount.dmn");
    assertThat((String) demo.get("project")).as("a required project").startsWith("rule-engine-demo");
    assertThat(MockRuntime.calls("models").get(0).apiKey()).isEqualTo("test-key");
  }

  @Test
  void modelsOfOtherApplicationsAreLeftOut(AppFixture fixture) {
    fixture.var(Var.MODEL_PREFIX, "crm-");
    MockRuntime.model("crm-pricing", "price", "amount");
    MockRuntime.model("hr-bonus", "bonus", "salary");

    List<Map<String, Object>> models = (List<Map<String, Object>>) result(call("ruleManagementModels()", Map.of()));

    assertThat(byName(models, "pricing")).containsEntry("onRuntime", true);
    assertThat(models).extracting(model -> model.get("name")).doesNotContain("hr-bonus", "bonus", "crm-pricing");
  }

  @Test
  void contentOfTheActiveVersion() {
    MockRuntime.model("discount", "discount", "purchaseAmount");
    MockRuntime.content("discount", 1, DMN);

    Map<String, Object> content = (Map<String, Object>) result(call("ruleManagementContent(String,Integer)",
        params("model", "discount", "version", null)));

    assertThat(content).containsEntry("model", "discount").containsEntry("version", 1).containsEntry("dmn", DMN);
  }

  @Test
  void aModelThatIsNotOnTheRuntimeIsReadFromItsProject() {
    Map<String, Object> content = (Map<String, Object>) result(call("ruleManagementContent(String,Integer)",
        params("model", "invoice-discount", "version", null)));

    assertThat(content).containsEntry("version", null);
    assertThat((String) content.get("project")).startsWith("rule-engine-demo");
    assertThat((String) content.get("dmn")).contains("<definitions").contains("Silver customer");
  }

  @Test
  void versionsNewestFirst() {
    MockRuntime.versions("discount", """
        [ {"model":"discount","version":1,"status":"RETIRED","source":"PROJECT","author":"demo"},
          {"model":"discount","version":2,"status":"ACTIVE","source":"PORTAL","author":"admin","description":"Gold 20",
           "createdAt":"2026-11-17T10:00:00Z","activatedAt":"2026-11-17T10:01:00Z","activatedBy":"admin"} ]""");

    List<Map<String, Object>> versions = (List<Map<String, Object>>) result(call("ruleManagementVersions(String)",
        params("model", "discount")));

    assertThat(versions).extracting(version -> version.get("version")).containsExactly(2, 1);
    assertThat(versions.get(0)).containsEntry("status", "ACTIVE").containsEntry("source", "PORTAL")
        .containsEntry("description", "Gold 20").containsEntry("activatedBy", "admin")
        .containsEntry("activatedAt", "2026-11-17T10:01Z");
  }

  @Test
  void saveIsAVersionFromThePortal() {
    MockRuntime.onRegister(call -> MockRuntime.json(201, """
        {"model":"discount","version":3,"status":"ACTIVE","source":"PORTAL","author":"admin","created":true}"""));

    Map<String, Object> version = (Map<String, Object>) result(call("ruleManagementSave(String,String,String,String,Boolean)",
        params("model", "discount", "dmn", DMN, "author", "admin", "description", "Gold 20", "activate", true)));

    assertThat(version).containsEntry("version", 3).containsEntry("status", "ACTIVE").containsEntry("created", true);
    var sent = MockRuntime.calls("register").get(0).body();
    assertThat(sent.get("source").asString()).isEqualTo("PORTAL");
    assertThat(sent.get("author").asString()).isEqualTo("admin");
    assertThat(sent.get("description").asString()).isEqualTo("Gold 20");
    assertThat(sent.get("activate").asBoolean()).isTrue();
    assertThat(sent.get("content").asString()).isEqualTo(DMN);
  }

  @Test
  void aDraftStaysADraft() {
    call("ruleManagementSave(String,String,String,String,Boolean)",
        params("model", "discount", "dmn", DMN, "author", "admin", "description", "", "activate", false));

    var sent = MockRuntime.calls("register").get(0).body();
    assertThat(sent.get("activate").asBoolean()).isFalse();
    assertThat(sent.has("description")).as("an empty description is not sent").isFalse();
  }

  @Test
  void aModelWithErrorsIsRefusedWithTheFindings() {
    MockRuntime.onRegister(call -> MockRuntime.error(422, "INVALID_MODEL", "The model has errors.",
        "Rule 3: the input entry 'Goldd' is not an allowed value"));

    Map<String, Object> answer = call("ruleManagementSave(String,String,String,String,Boolean)",
        params("model", "discount", "dmn", DMN, "author", "admin", "description", null, "activate", true));

    assertThat(answer.get("result")).isNull();
    Map<String, Object> error = (Map<String, Object>) answer.get("error");
    assertThat(error).containsEntry("code", "INVALID_MODEL");
    assertThat((List<String>) error.get("details")).containsExactly("ERROR: Rule 3: the input entry 'Goldd' is not an allowed value");
  }

  @Test
  void activateAndRollBack() {
    Map<String, Object> activated = (Map<String, Object>) result(call("ruleManagementActivate(String,Integer,String)",
        params("model", "discount", "version", 2, "author", "admin")));
    assertThat(activated).containsEntry("version", 2).containsEntry("status", "ACTIVE").containsEntry("activatedBy", "admin");

    Map<String, Object> answer = call("ruleManagementRollback(String,String)", params("model", "discount", "author", "admin"));
    assertThat((Map<String, Object>) answer.get("error")).containsEntry("code", "INVALID_STATE");
    assertThat(MockRuntime.calls("rollback").get(0).body().get("user").asString()).isEqualTo("admin");
  }

  @Test
  void validate() {
    MockRuntime.onValidate(call -> MockRuntime.json(200, """
        {"valid":false,"messages":[{"severity":"ERROR","message":"Missing output","sourceId":"_rule3"}]}"""));

    Map<String, Object> validation = (Map<String, Object>) result(call("ruleManagementValidate(String)", params("dmn", DMN)));

    assertThat(validation).containsEntry("valid", false)
        .containsEntry("messages", List.of("ERROR: Missing output (_rule3)"));
  }

  @Test
  void anUnreachableRuntimeIsAnError(AppFixture fixture) {
    fixture.var(Var.URL, "http://127.0.0.1:9");

    Map<String, Object> answer = call("ruleManagementModels()", Map.of());

    assertThat((Map<String, Object>) answer.get("error")).containsEntry("code", "UNREACHABLE");
    assertThat((Map<String, Object>) result(call("ruleManagementInfo()", Map.of()))).containsEntry("reachable", false);
  }

  @Test
  void theEditorScriptComesFromTheRuntimeThroughThisEngine() {
    MockRuntime.editorScript("window.DmnEditor = { open: function () {} };");

    try (Response first = editorScript(null)) {
      assertThat(first.getStatus()).isEqualTo(200);
      assertThat(first.readEntity(String.class)).isEqualTo("window.DmnEditor = { open: function () {} };");
      assertThat(first.getHeaderString("Content-Type")).startsWith("text/javascript");
      String etag = first.getHeaderString("ETag");
      assertThat(etag).isNotBlank();
      try (Response again = editorScript(etag)) {
        assertThat(again.getStatus()).as("the browser has it already").isEqualTo(304);
      }
    }
    assertThat(MockRuntime.calls("editorScript")).as("fetched from the runtime once").hasSize(1);
  }

  @Test
  void aRuntimeWithoutEditorIsExplained() {
    try (Response response = editorScript(null)) {
      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(response.readEntity(String.class)).contains("has no DMN editor");
    }
  }

  private static Response editorScript(String ifNoneMatch) {
    var request = Ivy.rest().client("dmnEditorScript").path("dmn-editor.js").request();
    if (ifNoneMatch != null) {
      request = request.header("If-None-Match", ifNoneMatch);
    }
    return request.get();
  }

  private static Map<String, Object> call(String signature, Map<String, Object> params) {
    return Sudo.get(() -> {
      SubProcessCallStartEvent start = find(signature).get(0);
      if (params.isEmpty()) {
        return start.call().asMap();
      }
      SubProcessCallStartParamCaller caller = null;
      for (var param : params.entrySet()) {
        caller = (caller == null ? start : caller).withParam(param.getKey(), param.getValue());
      }
      return caller.call().asMap();
    });
  }

  private static List<SubProcessCallStartEvent> find(String signature) {
    return Sudo.get(() -> SubProcessCallStartEvent.find(SubProcessSearchFilter.create()
        .setSearchScope(SearchScope.SECURITY_CONTEXT)
        .setSignature(signature)
        .toFilter()));
  }

  private static Object result(Map<String, Object> answer) {
    assertThat(answer.get("error")).as("error of the call").isNull();
    return answer.get("result");
  }

  private static Map<String, Object> byName(List<Map<String, Object>> models, String name) {
    return models.stream().filter(model -> name.equals(model.get("name"))).findFirst()
        .orElseThrow(() -> new AssertionError("no model " + name + " in " + models));
  }

  private static Map<String, Object> params(Object... keysAndValues) {
    var params = new LinkedHashMap<String, Object>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      params.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return params;
  }
}
