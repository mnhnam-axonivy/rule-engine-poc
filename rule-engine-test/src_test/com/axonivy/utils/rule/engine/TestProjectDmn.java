package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.dmn.DmnModelInfo;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;
import com.axonivy.utils.rule.engine.program.internal.Conf;
import com.axonivy.utils.rule.engine.program.internal.RuleTaskEditor;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.environment.IvyTest;
import ch.ivyteam.ivy.process.program.ui.ProgramUiBuilder;
import ch.ivyteam.ivy.process.program.ui.ProgramWidget;
import ch.ivyteam.ivy.process.program.ui.select.SelectItem;

/**
 * The .dmn files of a project: what they offer and how the inscription mask lists and maps them.
 */
@IvyTest
class TestProjectDmn {

  /** Records what the element builds as inscription mask. */
  static class RecordingUi {

    final List<String> groups = new ArrayList<>();
    final List<String> fields = new ArrayList<>();
    final List<String> labels = new ArrayList<>();
    final Map<String, String> fieldLabels = new LinkedHashMap<>();
    final Map<String, String> fieldHelps = new LinkedHashMap<>();
    final Map<String, Class<?>> fieldTypes = new LinkedHashMap<>();
    final Map<String, List<SelectItem>> selects = new LinkedHashMap<>();

    ProgramUiBuilder ui() {
      return (ProgramUiBuilder) Proxy.newProxyInstance(getClass().getClassLoader(),
          new Class<?>[] {ProgramUiBuilder.class}, (proxy, method, args) -> {
            String key = String.valueOf(args[0]);
            switch (method.getName()) {
              case "group" -> groups.add(key);
              case "label" -> labels.add(key);
              default -> fields.add(key);
            }
            return builder(method.getReturnType(), key);
          });
    }

    @SuppressWarnings("unchecked")
    private Object builder(Class<?> type, String key) {
      return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
        if (method.getName().equals("create")) {
          return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ProgramWidget.class},
              (widget, call, params) -> null);
        }
        if (method.getName().equals("items") && args != null) {
          selects.put(key, (List<SelectItem>) args[0]);
        }
        if (method.getName().equals("label") && args != null) {
          fieldLabels.put(key, (String) args[0]);
        }
        if (method.getName().equals("help") && args != null) {
          fieldHelps.put(key, (String) args[0]);
        }
        if (method.getName().equals("requireType") && args != null) {
          fieldTypes.put(key, (Class<?>) args[0]);
        }
        return method.getReturnType().isInstance(proxy) ? proxy : null;
      });
    }
  }

  @BeforeEach
  public void refresh() {
    ProjectDmnFiles.refresh();
  }

  @Test
  void dmnFilesOfTheProjectAndItsRequiredProjects_areFound() {
    var files = ProjectDmnFiles.list(Project.current());
    assertThat(files).extracting(ProjectDmnFiles.DmnFile::model).contains("discount", "invoice-approval", "invoice-discount");
    assertThat(ProjectDmnFiles.find(Project.current(), "invoice-discount").orElseThrow().project())
        .as("a file of the required project rule-engine-demo").startsWith("rule-engine-demo");

    var discount = ProjectDmnFiles.load(ProjectDmnFiles.find(Project.current(), "discount").orElseThrow());
    assertThat(discount.parseError()).isNull();
    assertThat(discount.info().name()).isEqualTo("discount");
    assertThat(discount.info().inputNames()).containsExactly("purchaseAmount", "type", "country");
    assertThat(discount.hash()).hasSize(64).isEqualTo(ProjectDmnFiles.hash(discount.content().replace("\n", "\r\n")));
  }

  /** An engine keeps a deployed project packed: its directory is the .iar, a zip with the files under META-INF/ivy. */
  @Test
  void dmnFilesOfAPackedProject_areFound() throws IOException {
    Path iar = Files.createTempFile("packed-project", ".iar");
    try {
      String dmn = Files.readString(ProjectDmnFiles.find(Project.current(), "discount").orElseThrow().path());
      try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(iar))) {
        zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/ivy/dmn/discount.dmn"));
        zip.write(dmn.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/ivy/target/classes/dmn/copy.dmn"));
        zip.write(dmn.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }

      Path root = ProjectDmnFiles.root(iar);

      assertThat(root).isNotNull();
      assertThat(root.toString()).isEqualTo("/META-INF/ivy");
      try (var paths = Files.walk(root)) {
        assertThat(paths.map(Path::toString).filter(path -> path.endsWith(".dmn")))
            .contains("/META-INF/ivy/dmn/discount.dmn");
      }
      var file = new ProjectDmnFiles.DmnFile("discount", root.resolve("dmn/discount.dmn"), "packed", "1", "app v1");
      assertThat(ProjectDmnFiles.load(file).info().inputNames()).containsExactly("purchaseAmount", "type", "country");
      assertThat(ProjectDmnFiles.root(Files.createTempDirectory("unpacked"))).as("a project folder is its own root").isNotNull();
    } finally {
      try {
        Files.deleteIfExists(iar);
      } catch (IOException ex) {
        iar.toFile().deleteOnExit(); // still open: ProjectDmnFiles keeps packed projects open
      }
    }
  }

  @Test
  void decisionKnowsItsInputs_alsoThroughOtherDecisions() {
    var file = ProjectDmnFiles.find(Project.current(), "invoice-approval").orElseThrow();
    DmnModelInfo info = ProjectDmnFiles.load(file).info();

    assertThat(info.decisions()).extracting(DmnModelInfo.Decision::name)
        .containsExactly("riskLevel", "approval", "dueDate");
    assertThat(info.decision("riskLevel").orElseThrow().requiredInputs())
        .containsExactlyInAnyOrder("amount", "supplierRating", "hasPurchaseOrder");
    assertThat(info.decision("approval").orElseThrow().requiredInputs())
        .as("approval needs riskLevel, which needs its own inputs")
        .containsExactlyInAnyOrder("amount", "supplierRating", "hasPurchaseOrder");
    assertThat(info.decision("dueDate").orElseThrow().requiredInputs()).containsExactly("invoiceDate");
    assertThat(info.decision("dueDate").orElseThrow().typeRef()).isEqualTo("date");
  }

  /** The model of the demo: one decision table with two outputs. */
  @Test
  void modelOfTheDemo() {
    var file = ProjectDmnFiles.find(Project.current(), "invoice-discount").orElseThrow();
    DmnModelInfo info = ProjectDmnFiles.load(file).info();

    assertThat(info.decisions()).extracting(DmnModelInfo.Decision::name).containsExactly("discount");
    assertThat(info.inputTypes()).containsEntry("customerTier", "string").containsEntry("orderAmount", "number");
    assertThat(info.decision("discount").orElseThrow().requiredInputs())
        .containsExactlyInAnyOrder("customerTier", "orderAmount");
    assertThat(DmnModelInfo.describeOutputs(info.decision("discount").orElseThrow()))
        .isEqualTo("discountPercent (number), discountReason (string)");
  }

  @Test
  void decisionKnowsItsResults_andTheTypesForAReader() {
    var file = ProjectDmnFiles.find(Project.current(), "invoice-approval").orElseThrow();
    DmnModelInfo info = ProjectDmnFiles.load(file).info();

    assertThat(info.inputTypes()).as("an item definition is resolved to what it stands for")
        .containsEntry("amount", "number")
        .containsEntry("supplierRating", "string")
        .containsEntry("hasPurchaseOrder", "boolean")
        .containsEntry("invoiceDate", "date");
    var riskLevel = info.decision("riskLevel").orElseThrow();
    assertThat(riskLevel.outputs()).containsExactly(new DmnModelInfo.Output("riskLevel", "string"));
    assertThat(info.describeInputs(riskLevel)).isEqualTo("amount (number), supplierRating (string), hasPurchaseOrder (boolean)");
    assertThat(DmnModelInfo.describeOutputs(info.decision("approval").orElseThrow()))
        .as("a table with several output columns gives the entries of a structure")
        .isEqualTo("nextStep (string), fourEyes (boolean)");
    assertThat(DmnModelInfo.describeOutputs(info.decision("dueDate").orElseThrow())).isEqualTo("dueDate (date)");
  }

  @Test
  void inscriptionMask_listsDmnFilesAndTheirDecisions() {
    var ui = new RecordingUi();
    new BusinessRuleTask().editor(ui.ui());

    assertThat(ui.groups).as("the decision, the process data of its inputs and the fields of its results")
        .containsExactly(RuleTaskEditor.DECISION_GROUP, RuleTaskEditor.INPUT_GROUP, RuleTaskEditor.OUTPUT_GROUP);
    assertThat(ui.fields.get(0)).isEqualTo(Conf.DECISION);
    var decisions = ui.selects.get(Conf.DECISION);
    assertThat(decisions).extracting(SelectItem::id).contains(
        "discount#discount",
        "invoice-approval#riskLevel", "invoice-approval#approval", "invoice-approval#dueDate",
        "invoice-approval");
    assertThat(decisions).extracting(SelectItem::label).as("files are created in the menu of the project")
        .doesNotContain("New DMN...");
    var discount = decisions.stream().filter(item -> item.id().equals("discount#discount")).findFirst().orElseThrow();
    assertThat(discount.label()).isEqualTo("discount › discount");
    assertThat(discount.description())
        .as("the inputs and outputs of the decision, with their types")
        .contains("in: purchaseAmount (number), type (string), country (string) · out: discount (number)");
  }

  @Test
  void inscriptionMask_mapsTheInputsAndResultsOfEachChoice() {
    var ui = new RecordingUi();
    new BusinessRuleTask().editor(ui.ui());

    String dueDate = Conf.choice("invoice-approval", "dueDate");
    String approval = Conf.choice("invoice-approval", "approval");
    String allOfApproval = Conf.choice("invoice-approval", null);
    assertThat(ui.fieldLabels).as("a script field per input and result of a choice, named like it")
        .containsEntry(Conf.inputKey("discount#discount", "purchaseAmount"), "purchaseAmount")
        .containsEntry(Conf.inputKey(dueDate, "invoiceDate"), "invoiceDate")
        .containsEntry(Conf.outputKey(approval, "approval", "fourEyes"), "fourEyes")
        .containsEntry(Conf.inputKey(allOfApproval, "amount"), "amount")
        .containsEntry(Conf.outputKey(allOfApproval, "approval", "fourEyes"), "fourEyes")
        .containsEntry(Conf.outputKey("invoice-discount#discount", "discount", "discountPercent"), "discountPercent");
    assertThat(ui.fieldLabels).as("only the inputs the decision needs")
        .doesNotContainKey(Conf.inputKey(dueDate, "amount"));
    assertThat(ui.fields).as("each field once").doesNotHaveDuplicates();
    assertThat(ui.fieldHelps).as("its type, shown below the name")
        .containsEntry(Conf.inputKey("discount#discount", "purchaseAmount"), "number")
        .containsEntry(Conf.inputKey(dueDate, "invoiceDate"), "date")
        .containsEntry(Conf.outputKey(approval, "approval", "fourEyes"), "boolean")
        .containsEntry(Conf.outputKey(allOfApproval, "approval", "fourEyes"), "boolean · approval")
        .containsEntry(Conf.outputKey("invoice-discount#discount", "discount", "discountPercent"), "number");
    assertThat(ui.fieldTypes).as("the Ivy type of the DMN type, which the Designer checks the process data against")
        .containsEntry(Conf.inputKey("discount#discount", "purchaseAmount"), Number.class)
        .containsEntry(Conf.inputKey("discount#discount", "type"), String.class)
        .containsEntry(Conf.inputKey(dueDate, "invoiceDate"), ch.ivyteam.ivy.scripting.objects.Date.class)
        .containsEntry(Conf.outputKey(approval, "approval", "fourEyes"), Boolean.class)
        .containsEntry(Conf.outputKey(allOfApproval, "approval", "fourEyes"), Boolean.class);
  }

  @Test
  void mappingKey_namesTheChoiceOfTheDropdown() {
    assertThat(Conf.inputKey(Conf.choice("discount", "discount"), "type")).isEqualTo("decision[discount#discount].input.type");
    assertThat(Conf.outputKey(Conf.choice("invoice-approval", "approval"), "approval", "nextStep"))
        .isEqualTo("decision[invoice-approval#approval].output.nextStep");
    assertThat(Conf.outputKey(Conf.choice("invoice-approval", null), "approval", "nextStep"))
        .as("with all decisions selected, the decision of the result too")
        .isEqualTo("decision[invoice-approval].output.approval.nextStep");
  }

  @Test
  void inscriptionMask_explainsEachGroupOnce() {
    var ui = new RecordingUi();
    new BusinessRuleTask().editor(ui.ui());

    assertThat(ui.labels).as("on top of each group, instead of a remark in every row")
        .containsExactly(RuleTaskEditor.DECISION_HELP, RuleTaskEditor.INPUT_HELP, RuleTaskEditor.OUTPUT_HELP);
  }
}
