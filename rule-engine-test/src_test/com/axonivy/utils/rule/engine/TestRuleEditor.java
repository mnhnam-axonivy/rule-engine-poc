package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.RuleEngineConfig.Var;
import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.editor.RuleEditorController;
import com.axonivy.utils.rule.engine.editor.RuleEditorController.Notice;
import com.axonivy.utils.rule.engine.test.mock.MockDmnEngine;
import com.axonivy.utils.rule.engine.test.mock.TestStore;
import com.axonivy.utils.rule.engine.version.VersionStore;

import ch.ivyteam.ivy.bpm.exec.client.IvyProcessTest;
import ch.ivyteam.ivy.environment.AppFixture;
import ch.ivyteam.ivy.environment.Ivy;

/**
 * The dialog RuleEditor without its page: what it opens, what a check says, what a save stores. The page runs the
 * DMN editor of Apache KIE in the browser and hands the DMN over; here the test does. The DMN engine is replaced by
 * {@link MockDmnEngine}.
 */
@IvyProcessTest(enableWebServer = true)
class TestRuleEditor {

  private static final String DMN = "<definitions xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\" name=\"discount\"/>";

  @BeforeEach
  void setup(AppFixture fixture) {
    MockDmnEngine.reset();
    TestStore.clear();
    fixture.var(Var.URL, MockDmnEngine.url());
  }

  @Test
  void opensTheActiveVersion() {
    TestStore.store("discount", DMN, VersionSource.API, null);

    var editor = RuleEditorController.open("discount", null);

    assertThat(editor.isOpened()).isTrue();
    assertThat(editor.getDmn()).isEqualTo(DMN);
    assertThat(editor.getVersion()).isEqualTo(1);
    assertThat(editor.getState()).isEqualTo("Version 1 · Active");
    assertThat(editor.getNotices()).isEmpty();
  }

  @Test
  void opensAnOlderVersion() {
    TestStore.store("discount", DMN, VersionSource.API, null);
    TestStore.store("discount", TestStore.dmn("discount", "discount", "segment"), VersionSource.PORTAL, true);

    var editor = RuleEditorController.open("discount", 1);

    assertThat(editor.getDmn()).isEqualTo(DMN);
    assertThat(editor.getState()).isEqualTo("Version 1 · Retired");
  }

  @Test
  void aModelThatIsNotStoredIsReadFromItsProject() {
    var editor = RuleEditorController.open("invoice-discount", null);

    assertThat(editor.getVersion()).isNull();
    assertThat(editor.getState()).startsWith("File of project rule-engine-demo").endsWith("not stored yet");
    assertThat(editor.getDmn()).contains("<definitions").contains("Silver customer");
  }

  @Test
  void anUnknownModel_isNotOpened() {
    var editor = RuleEditorController.open("no-such-rule", null);

    assertThat(editor.isOpened()).isFalse();
    assertThat(editor.getNotices()).extracting(Notice::severity).containsExactly("error");
    assertThat(editor.getNotices().get(0).text()).contains("no-such-rule");
  }

  @Test
  void withoutAModel_isNotOpened() {
    var editor = RuleEditorController.open(null, null);

    assertThat(editor.isOpened()).isFalse();
    assertThat(editor.getNotices()).containsExactly(new Notice("error", "Which rule? Start the editor with the name of a model."));
  }

  @Test
  void check_saysWhatTheDmnEngineFound() {
    TestStore.store("discount", DMN, VersionSource.API, null);
    var editor = RuleEditorController.open("discount", null);

    editor.check();
    assertThat(editor.getNotices()).containsExactly(new Notice("info", "The model discount is valid."));

    MockDmnEngine.onValidate(call -> MockDmnEngine.json(200, """
        [{"severity":"ERROR","message":"Missing output","sourceId":"_rule3"}]"""));
    editor.setDmn(DMN.replace("discount", "discount2"));
    editor.check();
    assertThat(editor.getNotices()).containsExactly(
        new Notice("error", "The model discount has errors:"),
        new Notice("warn", "ERROR: Missing output (_rule3)"));
    assertThat(MockDmnEngine.calls("validate").get(1).dmn()).as("what the editor holds").contains("discount2");
    assertThat(editor.isSaved()).isFalse();
    assertThat(VersionStore.current().versions("discount")).as("a check stores nothing").hasSize(1);
  }

  @Test
  void saveAndActivate_isAVersionOfTheRuleOwner() {
    var editor = RuleEditorController.open("invoice-discount", null);
    editor.setDescription("Gold 20");

    editor.save(true);

    assertThat(editor.isSaved()).isTrue();
    assertThat(editor.getState()).isEqualTo("Version 1 · Active");
    assertThat(editor.getNotices()).containsExactly(new Notice("info", "Version 1 of invoice-discount is saved and active."));
    assertThat(editor.getDescription()).as("ready for the next change").isNull();
    ModelVersion stored = VersionStore.current().versions("invoice-discount").get(0);
    assertThat(stored.source()).isEqualTo(VersionSource.PORTAL);
    assertThat(stored.description()).isEqualTo("Gold 20");
    assertThat(stored.author()).isEqualTo(Ivy.session().getSessionUserName());
    assertThat(MockDmnEngine.calls("validate")).as("the DMN engine checks it first").hasSize(1);

    editor.save(true);
    assertThat(editor.getNotices()).containsExactly(
        new Notice("info", "The model equals version 1 of invoice-discount, no new version was created."));
  }

  @Test
  void saveAsDraft_staysADraft() {
    TestStore.store("discount", DMN, VersionSource.API, null);
    var editor = RuleEditorController.open("discount", null);
    editor.setDmn(TestStore.dmn("discount", "discount", "segment"));
    editor.setDescription(" ");

    editor.save(false);

    assertThat(editor.getState()).isEqualTo("Version 2 · Draft");
    assertThat(editor.getNotices()).containsExactly(new Notice("info", "Version 2 of discount is saved as draft."));
    assertThat(VersionStore.current().versions("discount").get(0).description()).isNull();
  }

  @Test
  void aModelWithErrors_isRefusedWithTheFindings() {
    MockDmnEngine.onValidate(call -> MockDmnEngine.findings("Rule 3: the input entry 'Goldd' is not an allowed value"));
    var editor = RuleEditorController.open("invoice-discount", null);

    editor.save(true);

    assertThat(editor.isSaved()).isFalse();
    assertThat(editor.getNotices()).extracting(Notice::severity).containsExactly("error", "warn");
    assertThat(editor.getNotices().get(1).text()).isEqualTo("ERROR: Rule 3: the input entry 'Goldd' is not an allowed value");
    assertThat(VersionStore.current().exists("invoice-discount")).as("nothing is stored").isFalse();
  }

  @Test
  void anUnreachableDmnEngine_stopsChecksAndSaves(AppFixture fixture) {
    var editor = RuleEditorController.open("invoice-discount", null);
    fixture.var(Var.URL, "http://127.0.0.1:9");

    editor.save(true);

    assertThat(editor.isSaved()).isFalse();
    assertThat(editor.getNotices()).extracting(Notice::severity).containsExactly("error");
    assertThat(editor.getNotices().get(0).text()).contains("not reachable");
  }

  @Test
  void theDialogHasAProcessStart_thatThePortalOpens() {
    var starts = Ivy.wf().findProcessStartsBySignature("editRule(String,Number)");

    assertThat(starts).hasSize(1);
    assertThat(starts.iterator().next().getLink().getRelative()).endsWith("/editRule.ivp");
  }
}
