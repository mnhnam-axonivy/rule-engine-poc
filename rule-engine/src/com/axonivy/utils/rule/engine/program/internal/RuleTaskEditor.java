package com.axonivy.utils.rule.engine.program.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.axonivy.utils.rule.engine.dmn.DmnModelInfo;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.process.program.ui.ProgramGroupBuilder;
import ch.ivyteam.ivy.process.program.ui.ProgramUiBuilder;
import ch.ivyteam.ivy.process.program.ui.ProgramWidget;
import ch.ivyteam.ivy.process.program.ui.select.SelectItem;

/**
 * The inscription mask of the Business Rule Task: the decision, the process data its inputs read and the fields its
 * results fill.
 * <p>The inscription API builds the mask without the configuration of the element, so the groups Input and Output
 * hold the fields of every choice of the dropdown. Their keys name the choice they belong to (see {@link Conf}): the
 * inscription view of the Designer shows the fields of the first selected decision only, each as a row with the name,
 * the type below it and the script field beside them. The field requires the Ivy type of the DMN type: the Designer
 * marks process data of another type.</p>
 */
public class RuleTaskEditor {

  /** The inscription view of the Designer shows the icon of each group: a rule, data going in, results going out. */
  public static final String DECISION_GROUP = "Decision";
  public static final String INPUT_GROUP = "Input";
  public static final String OUTPUT_GROUP = "Output";
  public static final String DECISION_HELP = String.join("\n",
      "A decision of a .dmn file of this project.",
      "New file: Axon Ivy > New > DMN Rule in the menu of the project.");
  public static final String INPUT_HELP = String.join("\n",
      "The process data each input reads, e.g. in.order.amount.",
      "Every input needs one.");
  public static final String OUTPUT_HELP = String.join("\n",
      "The field each result fills, e.g. in.order.discount.",
      "Empty: the result is not written.");

  private record Model(ProjectDmnFiles.DmnFile file, String location, DmnModelInfo info, String parseError) {

    String name() {
      return file.model();
    }
  }

  /** The Ivy type that takes the value of a DMN type, as FEEL names it in DMN 1.2 and later or in DMN 1.1. */
  private static final Map<String, Class<?>> IVY_TYPES = Map.ofEntries(
      Map.entry("number", Number.class),
      Map.entry("string", String.class),
      Map.entry("boolean", Boolean.class),
      Map.entry("date", ch.ivyteam.ivy.scripting.objects.Date.class),
      Map.entry("date and time", ch.ivyteam.ivy.scripting.objects.DateTime.class),
      Map.entry("dateTime", ch.ivyteam.ivy.scripting.objects.DateTime.class),
      Map.entry("time", ch.ivyteam.ivy.scripting.objects.Time.class),
      Map.entry("days and time duration", ch.ivyteam.ivy.scripting.objects.Duration.class),
      Map.entry("dayTimeDuration", ch.ivyteam.ivy.scripting.objects.Duration.class),
      Map.entry("years and months duration", ch.ivyteam.ivy.scripting.objects.Duration.class),
      Map.entry("yearMonthDuration", ch.ivyteam.ivy.scripting.objects.Duration.class));

  /**
   * A mapping field of the mask: the name of an input or result, its type shown below the name, and the Ivy type the
   * field requires, if the DMN type has one.
   */
  private record Row(String key, String name, String help, Class<?> ivyType) {

    static Row of(String key, String name, String type) {
      return new Row(key, name, type, RuleTaskEditor.ivyType(type));
    }
  }

  /** What a choice of the decision dropdown maps: the inputs it reads and the results it gives. */
  private record Choice(List<Row> inputs, List<Row> outputs) {}

  public void editor(ProgramUiBuilder ui) {
    Project project = currentProject();
    List<Model> models = models(project);

    ui.group(DECISION_GROUP)
        .add(ui.multiSelect(Conf.DECISION)
            .label("DMN decision:")
            .items(decisions(models))
            .create())
        .add(ui.label(DECISION_HELP).multiline().create())
        .create();
    List<Choice> choices = models.stream().filter(model -> model.info() != null)
        .flatMap(model -> choices(model.name(), model.info()).stream()).toList();
    if (!choices.isEmpty()) {
      group(ui, INPUT_GROUP, INPUT_HELP, choices.stream().flatMap(choice -> choice.inputs().stream()).toList());
      group(ui, OUTPUT_GROUP, OUTPUT_HELP, choices.stream().flatMap(choice -> choice.outputs().stream()).toList());
    }
  }

  /** The same choices as the dropdown: each decision, and all decisions of a model with several. */
  private static List<Choice> choices(String model, DmnModelInfo info) {
    var choices = new ArrayList<Choice>();
    Map<String, String> inputTypes = info.inputTypes();
    for (var decision : info.decisions()) {
      String choice = Conf.choice(model, decision.name());
      choices.add(new Choice(
          decision.requiredInputs().stream()
              .map(input -> Row.of(Conf.inputKey(choice, input), input, inputTypes.get(input))).toList(),
          decision.outputs().stream()
              .map(output -> Row.of(Conf.outputKey(choice, decision.name(), output.name()), output.name(), output.type()))
              .toList()));
    }
    if (info.decisions().size() > 1) {
      choices.add(new Choice(
          info.inputs().stream()
              .map(input -> Row.of(Conf.inputKey(model, input.name()), input.name(), input.type())).toList(),
          info.decisions().stream().flatMap(decision -> decision.outputs().stream()
              .map(output -> new Row(Conf.outputKey(model, decision.name(), output.name()), output.name(),
                  described(output.type()) + " · " + decision.name(), ivyType(output.type()))))
              .toList()));
    }
    return choices;
  }

  private static void group(ProgramUiBuilder ui, String name, String help, List<Row> rows) {
    ProgramGroupBuilder group = ui.group(name).add(ui.label(help).multiline().create());
    rows.forEach(row -> group.add(field(ui, row)));
    group.create();
  }

  /** A field named like the input or result; its type is the help, which the Designer shows below the name. */
  private static ProgramWidget field(ProgramUiBuilder ui, Row row) {
    var field = ui.scriptField(row.key()).label(row.name());
    if (row.help() != null) {
      field.help(row.help());
    }
    if (row.ivyType() != null) {
      field.requireType(row.ivyType());
    }
    return field.create();
  }

  private static String described(String type) {
    return type == null ? "any" : type;
  }

  /** @return null for a structure, a list or a type without a value of Ivy: the field takes any process data */
  private static Class<?> ivyType(String dmnType) {
    return dmnType == null ? null : IVY_TYPES.get(dmnType);
  }

  private static List<Model> models(Project project) {
    var models = new ArrayList<Model>();
    try {
      for (var file : ProjectDmnFiles.list(project)) {
        var dmn = ProjectDmnFiles.load(file);
        models.add(new Model(file, location(project, file), dmn.info(), dmn.parseError()));
      }
    } catch (RuntimeException ex) {
      // the editor must open, even if the files of the project cannot be listed
    }
    return models;
  }

  private static List<SelectItem> decisions(List<Model> models) {
    var items = new ArrayList<SelectItem>();
    for (var model : models) {
      String name = model.name();
      DmnModelInfo info = model.info();
      if (info == null) {
        items.add(SelectItem.of(name, name + " (cannot be read)", "⚠️", model.location() + " · " + model.parseError()));
        continue;
      }
      for (var decision : info.decisions()) {
        items.add(SelectItem.of(name + Conf.SEPARATOR + decision.name(), name + " › " + decision.name(), "📋",
            model.location() + " · in: " + info.describeInputs(decision) + " · out: "
                + DmnModelInfo.describeOutputs(decision)));
      }
      if (info.decisions().size() > 1) {
        items.add(SelectItem.of(name, name + " › all decisions", "🗂️",
            model.location() + " · evaluates every decision of the model · in: "
                + DmnModelInfo.describe(info.inputNames(), info.inputTypes()) + " · out: "
                + info.decisions().stream().map(DmnModelInfo::describeOutputs).collect(Collectors.joining(", "))));
      }
    }
    return items;
  }

  private static String location(Project project, ProjectDmnFiles.DmnFile file) {
    try {
      if (project != null && file.path().startsWith(project.directory())) {
        return file.relativePath(project.directory());
      }
    } catch (RuntimeException ex) {
      // fall through
    }
    return file.project() + "/" + file.path().getFileName();
  }

  private static Project currentProject() {
    try {
      return Project.current();
    } catch (RuntimeException ex) {
      return null;
    }
  }
}
