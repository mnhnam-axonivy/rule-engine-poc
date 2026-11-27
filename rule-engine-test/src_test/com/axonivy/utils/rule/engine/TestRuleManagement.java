package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.RuleEngineConfig.Var;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.test.mock.MockDmnEngine;
import com.axonivy.utils.rule.engine.test.mock.TestStore;
import com.axonivy.utils.rule.engine.version.VersionStore;

import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.environment.AppFixture;
import ch.ivyteam.ivy.environment.Ivy;
import ch.ivyteam.ivy.process.call.SubProcessCallStartEvent;
import ch.ivyteam.ivy.process.call.SubProcessCallStartParamCaller;
import ch.ivyteam.ivy.process.call.SubProcessSearchFilter;
import ch.ivyteam.ivy.process.call.SubProcessSearchFilter.SearchScope;
import ch.ivyteam.ivy.security.exec.Sudo;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The bridge to the tab "Rule Management" of the Axon Ivy Portal: the callable sub process Portal/RuleManagement,
 * called the way the Portal calls it. The Portal knows nothing of the rule engine but these signatures, so they
 * and the JDK types they answer with are a contract. The versions are stored in the file area of the test
 * application, the DMN engine is replaced by {@link MockDmnEngine}.
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
      "ruleManagementRollback(String,String)",
      "ruleManagementRetire(String,Integer)");

  private static final String DMN = "<definitions xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\" name=\"discount\"/>";

  @BeforeEach
  void setup(AppFixture fixture) {
    MockDmnEngine.reset();
    TestStore.clear();
    fixture.var(Var.URL, MockDmnEngine.url());
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

    assertThat(info).containsEntry("runtimeUrl", MockDmnEngine.url()).containsEntry("reachable", true);
  }

  @Test
  void storedModelsAndFilesOfTheProjects() {
    TestStore.store("discount", "discount", "purchaseAmount");

    List<Map<String, Object>> models = (List<Map<String, Object>>) result(call("ruleManagementModels()", Map.of()));

    assertThat(models).extracting(model -> model.get("name"))
        .as("sorted by name: the stored models and the .dmn files of rule-engine-test and rule-engine-demo")
        .containsSubsequence("discount", "invoice-approval", "invoice-discount");
    Map<String, Object> discount = byName(models, "discount");
    assertThat(discount).containsEntry("stored", true).containsEntry("activeVersion", 1)
        .containsEntry("versionCount", 1).containsEntry("decisions", List.of("discount"))
        .containsEntry("file", "discount.dmn");
    assertThat((String) discount.get("project")).startsWith("rule-engine-test");
    assertThat(byName(models, "invoice-approval")).containsEntry("stored", false).containsEntry("versionCount", 0);
    Map<String, Object> demo = byName(models, "invoice-discount");
    assertThat(demo).containsEntry("stored", false).containsEntry("file", "invoice-discount.dmn")
        .containsEntry("decisions", List.of("discount"));
    assertThat((String) demo.get("project")).as("a required project").startsWith("rule-engine-demo");
    assertThat(MockDmnEngine.calls()).as("listing needs no DMN engine").isEmpty();
  }

  @Test
  void contentOfTheActiveVersion() {
    TestStore.store("discount", DMN, VersionSource.API, null);

    Map<String, Object> content = (Map<String, Object>) result(call("ruleManagementContent(String,Integer)",
        params("model", "discount", "version", null)));

    assertThat(content).containsEntry("model", "discount").containsEntry("version", 1).containsEntry("dmn", DMN);
  }

  @Test
  void aModelThatIsNotStoredIsReadFromItsProject() {
    Map<String, Object> content = (Map<String, Object>) result(call("ruleManagementContent(String,Integer)",
        params("model", "invoice-discount", "version", null)));

    assertThat(content).containsEntry("version", null);
    assertThat((String) content.get("project")).startsWith("rule-engine-demo");
    assertThat((String) content.get("dmn")).contains("<definitions").contains("Silver customer");
  }

  @Test
  void versionsNewestFirst() {
    TestStore.store("discount", "discount", "purchaseAmount");
    VersionStore.current().register("discount", RegisterVersionRequest.builder()
        .content(TestStore.dmn("discount", "discount", "purchaseAmount", "segment"))
        .author("admin").description("Gold 20").source(VersionSource.PORTAL).activate(true).build());

    List<Map<String, Object>> versions = (List<Map<String, Object>>) result(call("ruleManagementVersions(String)",
        params("model", "discount")));

    assertThat(versions).extracting(version -> version.get("version")).containsExactly(2, 1);
    assertThat(versions.get(0)).containsEntry("status", "ACTIVE").containsEntry("source", "PORTAL")
        .containsEntry("description", "Gold 20").containsEntry("activatedBy", "admin").containsEntry("previousVersion", 1);
    assertThat(OffsetDateTime.parse((String) versions.get(0).get("effectiveDate"))).isNotNull();
    assertThat(versions.get(1)).containsEntry("status", "RETIRED");
  }

  @Test
  void saveIsAVersionFromThePortal() {
    Map<String, Object> version = (Map<String, Object>) result(call("ruleManagementSave(String,String,String,String,Boolean)",
        params("model", "discount", "dmn", DMN, "author", "admin", "description", "Gold 20", "activate", true)));

    assertThat(version).containsEntry("model", "discount").containsEntry("version", 1)
        .containsEntry("status", "ACTIVE").containsEntry("source", "PORTAL").containsEntry("author", "admin")
        .containsEntry("description", "Gold 20").containsEntry("created", true);
    assertThat(MockDmnEngine.calls("validate").get(0).dmn())
        .as("the runtime checks it first").isEqualTo(DMN);
    assertThat(VersionStore.current().content("discount", 1)).isEqualTo(DMN);

    Map<String, Object> again = (Map<String, Object>) result(call("ruleManagementSave(String,String,String,String,Boolean)",
        params("model", "discount", "dmn", DMN, "author", "admin", "description", null, "activate", true)));
    assertThat(again).containsEntry("version", 1).containsEntry("created", false);
  }

  @Test
  void aDraftStaysADraft() {
    Map<String, Object> version = (Map<String, Object>) result(call("ruleManagementSave(String,String,String,String,Boolean)",
        params("model", "discount", "dmn", DMN, "author", "admin", "description", "", "activate", false)));

    assertThat(version).containsEntry("status", "DRAFT").containsEntry("description", null);
  }

  @Test
  void aModelWithErrorsIsRefusedWithTheFindings() {
    MockDmnEngine.onValidate(call -> MockDmnEngine.findings("Rule 3: the input entry 'Goldd' is not an allowed value"));

    Map<String, Object> answer = call("ruleManagementSave(String,String,String,String,Boolean)",
        params("model", "discount", "dmn", DMN, "author", "admin", "description", null, "activate", true));

    assertThat(answer.get("result")).isNull();
    Map<String, Object> error = (Map<String, Object>) answer.get("error");
    assertThat(error).containsEntry("code", "INVALID_MODEL");
    assertThat((List<String>) error.get("details")).containsExactly("ERROR: Rule 3: the input entry 'Goldd' is not an allowed value");
    assertThat(VersionStore.current().exists("discount")).as("nothing is stored").isFalse();
  }

  @Test
  void activateAndRollBack() {
    TestStore.store("discount", "discount", "purchaseAmount");
    TestStore.store("discount", TestStore.dmn("discount", "discount", "segment"), VersionSource.PORTAL, false);

    Map<String, Object> activated = (Map<String, Object>) result(call("ruleManagementActivate(String,Integer,String)",
        params("model", "discount", "version", 2, "author", "admin")));
    assertThat(activated).containsEntry("version", 2).containsEntry("status", "ACTIVE").containsEntry("activatedBy", "admin");

    Map<String, Object> rolledBack = (Map<String, Object>) result(call("ruleManagementRollback(String,String)",
        params("model", "discount", "author", "admin")));
    assertThat(rolledBack).containsEntry("version", 1).containsEntry("status", "ACTIVE").containsEntry("activatedBy", "admin");

    Map<String, Object> answer = call("ruleManagementRollback(String,String)", params("model", "discount", "author", "admin"));
    assertThat((Map<String, Object>) answer.get("error")).as("version 1 was the first active one")
        .containsEntry("code", "INVALID_STATE");
  }

  @Test
  void aNewVersionOfTheApplication_isOfferedWhileTheVersionOfTheRuleOwnerIsActive() {
    TestStore.store("discount", TestStore.dmn("discount", "discount", "purchaseAmount"), VersionSource.PROJECT, null);
    TestStore.store("discount", TestStore.dmn("discount", "discount", "segment"), VersionSource.PORTAL, true);
    assertThat(byName(models(), "discount")).as("the version of the Portal is the newest").doesNotContainKey("pendingVersion");

    // the application is upgraded with a changed file: it is stored as a draft, version 2 of the Portal stays active
    TestStore.store("discount", TestStore.dmn("discount", "discount", "purchaseAmount", "segment"), VersionSource.PROJECT, null);
    assertThat(byName(models(), "discount")).containsEntry("activeVersion", 2)
        .containsEntry("pendingVersion", 3).containsEntry("pendingFrom", "test");

    Map<String, Object> kept = (Map<String, Object>) result(call("ruleManagementRetire(String,Integer)",
        params("model", "discount", "version", 3)));
    assertThat(kept).containsEntry("version", 3).containsEntry("status", "RETIRED");
    assertThat(byName(models(), "discount")).as("not offered again").containsEntry("activeVersion", 2)
        .doesNotContainKey("pendingVersion");

    TestStore.store("discount", TestStore.dmn("discount", "discount", "customer"), VersionSource.PROJECT, null);
    assertThat(byName(models(), "discount")).containsEntry("pendingVersion", 4);
    result(call("ruleManagementActivate(String,Integer,String)", params("model", "discount", "version", 4, "author", "admin")));
    assertThat(byName(models(), "discount")).containsEntry("activeVersion", 4).doesNotContainKey("pendingVersion");

    Map<String, Object> answer = call("ruleManagementRetire(String,Integer)", params("model", "discount", "version", 4));
    assertThat((Map<String, Object>) answer.get("error")).as("the active version cannot be retired")
        .containsEntry("code", "INVALID_STATE");
  }

  @Test
  void validate() {
    MockDmnEngine.onValidate(call -> MockDmnEngine.json(200, """
        [{"severity":"ERROR","message":"Missing output","sourceId":"_rule3"}]"""));

    Map<String, Object> validation = (Map<String, Object>) result(call("ruleManagementValidate(String)", params("dmn", DMN)));

    assertThat(validation).containsEntry("valid", false)
        .containsEntry("messages", List.of("ERROR: Missing output (_rule3)"));
  }

  @Test
  void anUnreachableDmnEngine_stopsChecksAndSaves_notTheList(AppFixture fixture) {
    TestStore.store("discount", "discount", "purchaseAmount");
    fixture.var(Var.URL, "http://127.0.0.1:9");

    assertThat((List<Map<String, Object>>) result(call("ruleManagementModels()", Map.of())))
        .extracting(model -> model.get("name")).contains("discount");
    Map<String, Object> answer = call("ruleManagementSave(String,String,String,String,Boolean)",
        params("model", "discount", "dmn", DMN, "author", "admin", "description", null, "activate", true));
    assertThat((Map<String, Object>) answer.get("error")).containsEntry("code", "UNREACHABLE");
    assertThat((Map<String, Object>) result(call("ruleManagementInfo()", Map.of()))).containsEntry("reachable", false);
  }

  @Test
  void theDmnEditorIsBundled() {
    try (Response first = editor("dmn-editor/dmn-editor.js", null)) {
      assertThat(first.getStatus()).isEqualTo(200);
      assertThat(first.getHeaderString("Content-Type")).startsWith("text/javascript");
      assertThat(first.readEntity(String.class)).contains("DmnEditor");
      String etag = first.getHeaderString("ETag");
      assertThat(etag).isNotBlank();
      try (Response again = editor("dmn-editor/dmn-editor.js", etag)) {
        assertThat(again.getStatus()).as("the browser has it already").isEqualTo(304);
      }
    }
    assertThat(MockDmnEngine.calls()).as("not fetched from the DMN engine").isEmpty();
  }

  @Test
  void theEditorPage() {
    try (Response page = editor("editor/index.html", null)) {
      assertThat(page.getStatus()).isEqualTo(200);
      assertThat(page.getHeaderString("Content-Type")).startsWith("text/html");
      assertThat(page.readEntity(String.class)).contains("lib/dmn-editor.js").contains("editor.js");
    }
    try (Response script = editor("editor/lib/dmn-editor.js", null)) {
      assertThat(script.getStatus()).isEqualTo(200);
    }
    try (Response other = editor("editor/pom.xml", null)) {
      assertThat(other.getStatus()).as("only the files of the page").isEqualTo(404);
    }
    try (Response anonymous = Ivy.rest().client("dmnEditor").path("editor/validate").request()
        .header("X-Requested-By", "ivy").post(Entity.entity(DMN, MediaType.APPLICATION_XML))) {
      assertThat(anonymous.getStatus()).as("the check needs a logged in user").isEqualTo(401);
    }
    assertThat(MockDmnEngine.calls("validate")).isEmpty();
  }

  private static Response editor(String path, String ifNoneMatch) {
    var request = Ivy.rest().client("dmnEditor").path(path).request();
    if (ifNoneMatch != null) {
      request = request.header("If-None-Match", ifNoneMatch);
    }
    return request.get();
  }

  /** Calls a start of the bridge as the Portal does: found by its signature in the security context. */
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

  private static List<Map<String, Object>> models() {
    return (List<Map<String, Object>>) result(call("ruleManagementModels()", Map.of()));
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
