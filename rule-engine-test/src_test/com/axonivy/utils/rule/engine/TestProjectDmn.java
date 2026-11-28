package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.dmn.DmnModelInfo;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;
import com.axonivy.utils.rule.engine.program.internal.Conf;
import com.axonivy.utils.rule.engine.program.internal.DesignTimeCreation;
import com.axonivy.utils.rule.engine.program.internal.DmnCreator;
import com.axonivy.utils.rule.engine.program.internal.FieldSelection;
import com.axonivy.utils.rule.engine.test.TestData;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.environment.IvyTest;
import ch.ivyteam.ivy.process.program.ui.ProgramUiBuilder;
import ch.ivyteam.ivy.process.program.ui.ProgramWidget;
import ch.ivyteam.ivy.process.program.ui.select.SelectItem;

/**
 * The .dmn files of a project: what the inscription mask offers and what "New DMN" generates.
 */
@IvyTest
class TestProjectDmn {

  /** Records what the element builds as inscription mask. */
  static class RecordingUi {

    final List<String> groups = new ArrayList<>();
    final List<String> fields = new ArrayList<>();
    final List<String> labels = new ArrayList<>();
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
        return method.getReturnType().isInstance(proxy) ? proxy : null;
      });
    }
  }

  private Path generated;

  @BeforeEach
  public void locate() {
    ProjectDmnFiles.refresh();
    generated = Project.current().directory().resolve("dmn").resolve("test-generated.dmn");
  }

  @AfterEach
  public void cleanup() throws IOException {
    Files.deleteIfExists(generated);
    if (Files.isDirectory(generated.getParent())) {
      try (var remaining = Files.list(generated.getParent())) {
        if (remaining.findAny().isEmpty()) {
          Files.delete(generated.getParent());
        }
      }
    }
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

    assertThat(ui.groups).containsExactly("⚖️ Decision", "✨ New DMN");
    assertThat(ui.fields).containsExactly(Conf.DECISION, Conf.NEW_MODEL, Conf.INPUTS, Conf.OUTPUTS);
    var decisions = ui.selects.get(Conf.DECISION);
    assertThat(decisions).extracting(SelectItem::id).contains(
        "discount#discount",
        "invoice-approval#riskLevel", "invoice-approval#approval", "invoice-approval#dueDate",
        "invoice-approval");
    assertThat(decisions.get(decisions.size() - 1).id()).isEqualTo(Conf.CREATE_NEW);
    assertThat(decisions.get(decisions.size() - 1).label()).isEqualTo("New DMN...");
    var discount = decisions.stream().filter(item -> item.id().equals("discount#discount")).findFirst().orElseThrow();
    assertThat(discount.label()).isEqualTo("discount › discount");
    assertThat(discount.description())
        .as("the inputs and outputs of the decision, with their types")
        .contains("in: purchaseAmount (number), type (string), country (string) · out: discount (number)");
  }

  @Test
  void inscriptionMask_explainsEachGroupOnce() {
    var ui = new RecordingUi();
    new BusinessRuleTask().editor(ui.ui());

    assertThat(ui.labels).hasSize(2);
    assertThat(ui.labels.get(0)).as("the group Decision")
        .startsWith("A decision of a .dmn file of this project.")
        .endsWith("Choose 'New DMN...' to create a new DMN file.");
    assertThat(ui.labels.get(1)).as("on top of the group New DMN, instead of a remark in every help text")
        .startsWith("Only used with 'New DMN...'");
  }

  @Test
  void createNewDmn_generatesFileFromSelectedFields_andSelectsIt() throws IOException {
    assertThat(generated).doesNotExist();

    // the process RuleTest has a Business Rule Task with 'New DMN...':
    // opening an inscription mask generates its file
    var ui = new RecordingUi();
    new BusinessRuleTask().editor(ui.ui());

    assertThat(generated).as("generated in the dmn folder of the project").exists();
    assertThat(ui.selects.get(Conf.DECISION)).extracting(SelectItem::id)
        .as("the new file is offered in the dropdown")
        .contains("test-generated#discount");

    String dmn = Files.readString(generated);
    DmnModelInfo info = DmnModelInfo.parse(dmn);
    assertThat(info.name()).isEqualTo("test-generated");
    assertThat(info.inputNames()).as("node names equal field names")
        .containsExactly("amount", "segment", "vip", "address");
    assertThat(info.decisions()).extracting(DmnModelInfo.Decision::name).containsExactly("discount");
    assertThat(dmn)
        .as("enum as allowed values").contains("&quot;GOLD&quot;, &quot;SILVER&quot;, &quot;STANDARD&quot;, null")
        .as("nested data class as structure").contains("name=\"tAddress\"")
        .contains("<text>address.city</text>")
        .as("diagram layout").contains("<dmndi:DMNDiagram");

    // the element switches from 'create' to the generated decision
    assertThat(dmn).as("the file says which fields it was generated from")
        .contains("<description>Generated by a Business Rule Task. Inputs: in.amount, in.customer.segment, "
            + "in.customer.vip, in.customer.address. Outputs: in.discount.</description>");
    var config = Map.of(Conf.DECISION, Conf.CREATE_NEW, Conf.NEW_MODEL, "test-generated",
        Conf.INPUTS, "in.amount, in.customer.segment, in.customer.vip, in.customer.address", Conf.OUTPUTS, "in.discount");
    assertThat(new BusinessRuleTask().migrateConfig(config))
        .containsEntry(Conf.DECISION, "test-generated#discount")
        .containsEntry(Conf.NEW_MODEL, "test-generated");
    var otherFields = Map.of(Conf.DECISION, Conf.CREATE_NEW, Conf.NEW_MODEL, "test-generated",
        Conf.INPUTS, "in.amount", Conf.OUTPUTS, "in.discount");
    assertThat(new BusinessRuleTask().migrateConfig(otherFields))
        .as("a file of that name from other fields is not this element's: it stays at 'New DMN...'")
        .isSameAs(otherFields);

    // an existing file is never overwritten
    Files.writeString(generated, dmn.replace("Placeholder", "My rule"));
    assertThat(DesignTimeCreation.createPending(Project.current())).isEmpty();
    assertThat(Files.readString(generated)).contains("My rule");
  }

  /** A new DMN needs a name, and one that no other file has. */
  @Test
  void newDmn_needsANameOfItsOwn() {
    assertThatThrownBy(() -> DmnCreator.create(Project.current(), TestData.class, " ",
        FieldSelection.parse("in.amount"), FieldSelection.parse("in.discount")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("'New DMN name' is empty. Name the new DMN, e.g. member-discount.");
    assertThatThrownBy(() -> DmnCreator.create(Project.current(), TestData.class, "discount",
        FieldSelection.parse("in.amount"), FieldSelection.parse("in.discount")))
        .as("dmn/discount.dmn of this project was not generated from these fields")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("A DMN named 'discount' exists already (discount.dmn)");
    var noName = Map.of(Conf.DECISION, Conf.CREATE_NEW, Conf.NEW_MODEL, "", Conf.INPUTS, "in.amount",
        Conf.OUTPUTS, "in.discount");
    assertThat(new BusinessRuleTask().migrateConfig(noName)).as("nothing to select without a name").isSameAs(noName);
  }

  /** The element reads the inputs and writes the results of a generated DMN by name, like those of any other. */
  @Test
  void newDmn_thatTheMappingByNameCouldNotRead_isRefused() {
    assertThatThrownBy(() -> DmnCreator.create(Project.current(), TestData.class, "test-generated",
        FieldSelection.parse("in.amount"), FieldSelection.parse("in.amount")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'amount' is selected as input and as output");
    assertThatThrownBy(() -> DmnCreator.create(Project.current(), TestData.class, "test-generated",
        FieldSelection.parse("amount = in.amount"), FieldSelection.parse("in.discount")))
        .as("an input is named like its field: there is nothing to rename")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("List fields with their path");
    assertThat(generated).as("no file for a refused selection").doesNotExist();
  }

  @Test
  void configOfOtherElements_isNotTouched() {
    var config = Map.of(Conf.DECISION, "discount#discount");
    assertThat(new BusinessRuleTask().migrateConfig(config)).isSameAs(config);
    var pending = Map.of(Conf.DECISION, Conf.CREATE_NEW, Conf.NEW_MODEL, "not-yet-there", Conf.OUTPUTS, "in.discount");
    assertThat(new BusinessRuleTask().migrateConfig(pending)).isSameAs(pending);
  }
}
