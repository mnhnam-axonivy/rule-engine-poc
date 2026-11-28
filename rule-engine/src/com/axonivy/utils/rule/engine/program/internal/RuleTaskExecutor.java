package com.axonivy.utils.rule.engine.program.internal;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.axonivy.utils.rule.engine.RuleEngine;
import com.axonivy.utils.rule.engine.RuleEngineConfig;
import com.axonivy.utils.rule.engine.api.EvaluateRequest;
import com.axonivy.utils.rule.engine.api.EvaluateResponse;
import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.client.RuleEngineClient;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineException;
import com.axonivy.utils.rule.engine.deploy.Evaluator;
import com.axonivy.utils.rule.engine.deploy.ModelDeployer;
import com.axonivy.utils.rule.engine.dmn.DmnModelInfo;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;
import com.axonivy.utils.rule.engine.mapping.FieldLocator;
import com.axonivy.utils.rule.engine.mapping.ValueConverter;
import com.axonivy.utils.rule.engine.version.VersionStore;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.bpm.error.BpmError;
import ch.ivyteam.ivy.environment.Ivy;
import ch.ivyteam.ivy.process.program.exec.ProgramContext;

/**
 * Runs a Business Rule Task: maps process data to the inputs of the decision, lets the DMN engine evaluate the
 * active version of the model and writes the results back to the process data. Inputs and results go where the
 * mapping of the element says: every input needs process data, a result without a field is not written. The versions
 * are stored in the file area of the application; a changed .dmn file of the project is stored as a new version
 * first.
 */
public class RuleTaskExecutor {

  /** The variable of the process data in IvyScript. */
  private static final String DATA = "in";
  /** A field of the process data, e.g. <code>in.member.purchaseAmount</code>. */
  private static final java.util.regex.Pattern FIELD_PATH = java.util.regex.Pattern
      .compile(DATA + "(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
  /** Prefix of the case custom field that stores the model version that produced the result. */
  public static final String VERSION_FIELD_PREFIX = "ruleVersion_";

  private static final String UNREADABLE = "Version %d of model '%s' cannot be read as DMN.";
  private static final String NO_DECISION = "Version %d of model '%s' has no decision '%s'. Its decisions are: %s";
  private static final String NO_FIELD = "The result '%s' of decision '%s' goes to '%s', which is no field of the "
      + "process data. Choose a field in the section Output of the Business Rule Task '%s', e.g. in.order.discount.";
  private static final String UNMAPPED = "No process data for the input(s) %s %s. Choose the process data of each "
      + "input in the section Input of the Business Rule Task '%s', e.g. in.order.amount.";
  /** The value of the decision dropdown that asked for a generated file, before files were created in the menu. */
  private static final String NEW_DMN = "#create";
  private static final String NEW_DMN_GONE = "The Business Rule Task '%s' asks for 'New DMN...', which is no longer "
      + "offered. Create the .dmn file with Axon Ivy > New > DMN Rule in the menu of the project and select its decision.";

  interface Variable {
    String RESULT = "result";
    String VERSION = "modelVersion";
  }

  private record Selection(String model, String decision) {

    String choice() {
      return Conf.choice(model, decision);
    }
  }

  private final ProgramContext context;

  public RuleTaskExecutor(ProgramContext context) {
    this.context = context;
  }

  public void execute() {
    try {
      Class<?> dataClass = dataClass();
      Selection selection = selection();
      try {
        evaluate(selection, dataClass);
      } catch (RuleEngineException ex) {
        throw RuleErrors.of(ex, selection.model());
      }
    } catch (BpmError error) {
      throw error;
    } catch (RuleEngineException ex) {
      throw RuleErrors.of(ex, null);
    } catch (IllegalArgumentException | IllegalStateException ex) {
      throw RuleErrors.mapping(ex.getMessage(), ex);
    }
  }

  private void evaluate(Selection selection, Class<?> dataClass) {
    RuleEngineConfig config = RuleEngineConfig.fromVariables();
    RuleEngineClient client = RuleEngine.client(config);
    VersionStore store = VersionStore.current();
    if (config.autoDeploy()) {
      ProjectDmnFiles.find(Project.current(), selection.model()).map(ProjectDmnFiles::load)
          .ifPresent(dmn -> ModelDeployer.ensureRegistered(client, store, selection.model(), dmn));
    }
    ModelVersion active = store.active(selection.model());
    var input = input(inputNames(selection, active, store), selection);
    EvaluateResponse response = Evaluator.evaluate(client, store, active, request(selection, input));
    mapResults(response, selection, dataClass);
  }

  private static EvaluateRequest request(Selection selection, Map<String, Object> input) {
    return EvaluateRequest.builder().decision(selection.decision()).input(input).build();
  }

  /** @return the value of every input: the process data chosen in the mapping, a field or a calculation */
  private Map<String, Object> input(List<String> names, Selection selection) {
    var input = new LinkedHashMap<String, Object>();
    var unmapped = new ArrayList<String>();
    for (String name : names) {
      String chosen = mapping(Conf.inputKey(selection.choice(), name));
      if (chosen == null) {
        unmapped.add(name);
      } else {
        input.put(name, ValueConverter.toJson(value(chosen)));
      }
    }
    if (!unmapped.isEmpty()) {
      String of = selection.decision() == null
          ? "of the model '" + selection.model() + "'"
          : "of the decision '" + selection.decision() + "' in model '" + selection.model() + "'";
      throw RuleErrors.mapping(UNMAPPED.formatted(unmapped, of, elementName()));
    }
    return input;
  }

  private String elementName() {
    return StringUtils.normalizeSpace(context.element().name());
  }

  /** @return what the mapping of the element says for the key, or null if it is left empty */
  private String mapping(String key) {
    return StringUtils.trimToNull(context.config().get(key));
  }

  private Selection selection() {
    List<String> selected = context.config().getList(Conf.DECISION);
    String value = selected.isEmpty() ? "" : StringUtils.trimToEmpty(selected.get(0));
    if (value.isEmpty()) {
      throw RuleErrors.mapping("No decision selected. Choose a decision of a .dmn file in the Business Rule Task '"
          + elementName() + "'.");
    }
    if (selected.size() > 1) {
      Ivy.log().warn("Only one decision is evaluated. Using " + value + " and ignoring the others of " + selected);
    }
    if (NEW_DMN.equals(value)) {
      throw RuleErrors.mapping(NEW_DMN_GONE.formatted(elementName()));
    }
    int separator = value.indexOf(Conf.SEPARATOR);
    String model = separator < 0 ? value : value.substring(0, separator);
    String decision = separator < 0 ? null : StringUtils.trimToNull(value.substring(separator + 1));
    return new Selection(model.trim(), decision);
  }

  /**
   * @return the inputs the decision needs in the active version. That version may not be the file of the project: a
   *         version that a rule owner activated in the Portal stays active, and may need other inputs.
   */
  private static List<String> inputNames(Selection selection, ModelVersion version, VersionStore store) {
    DmnModelInfo info = store.describe(version);
    if (info == null) {
      throw new IllegalArgumentException(UNREADABLE.formatted(version.version(), selection.model()));
    }
    if (selection.decision() == null) {
      return info.inputNames();
    }
    return info.decision(selection.decision())
        .map(DmnModelInfo.Decision::requiredInputs)
        .orElseThrow(() -> new IllegalArgumentException(NO_DECISION.formatted(version.version(), selection.model(),
            selection.decision(), info.decisions().stream().map(DmnModelInfo.Decision::name).toList())));
  }

  private void mapResults(EvaluateResponse response, Selection selection, Class<?> dataClass) {
    if (dataClass != null) {
      if (selection.decision() == null) {
        response.results().forEach((decision, value) -> mapResult(selection, decision, value, dataClass));
      } else {
        mapResult(selection, selection.decision(), response.results().get(selection.decision()), dataClass);
      }
    }
    rememberVersion(selection.model(), response.version());
  }

  /**
   * The result of a decision goes to the field chosen for it. A structured result without a field of its own is
   * unfolded: each entry goes to the field chosen for it. A result or entry without a field is not written.
   */
  private void mapResult(Selection selection, String decision, Object value, Class<?> dataClass) {
    var whole = new Result(selection, decision, decision);
    String chosen = mapping(whole.key());
    if (chosen != null) {
      assignChosen(chosen, value, dataClass, whole);
    } else if (value instanceof Map<?, ?> structure) {
      structure.forEach((key, entry) -> mapEntry(new Result(selection, decision, String.valueOf(key)), entry, dataClass));
    }
  }

  private record Result(Selection selection, String decision, String name) {

    String key() {
      return Conf.outputKey(selection.choice(), decision, name);
    }
  }

  private void mapEntry(Result result, Object value, Class<?> dataClass) {
    String chosen = mapping(result.key());
    if (chosen != null) {
      assignChosen(chosen, value, dataClass, result);
    }
  }

  private void assignChosen(String target, Object value, Class<?> dataClass, Result result) {
    Type type = FIELD_PATH.matcher(target).matches() ? FieldLocator.typeOf(dataClass, target).orElse(null) : null;
    if (type == null) {
      throw RuleErrors.mapping(NO_FIELD.formatted(result.name(), result.decision(), target, elementName()));
    }
    assign(target, value, type);
  }

  private void assign(String expression, Object value, Type type) {
    Object converted;
    try {
      converted = ValueConverter.fromJson(value, type);
    } catch (IllegalArgumentException ex) {
      throw RuleErrors.mapping("Cannot write the result to " + expression + ": " + ex.getMessage(), ex);
    }
    try {
      if (converted == null) {
        context.script().executeScript(expression + " = null;");
      } else {
        context.script().variable(Variable.RESULT, converted).executeScript(expression + " = " + Variable.RESULT + ";");
      }
    } catch (RuntimeException ex) {
      throw RuleErrors.mapping("Cannot write the result to " + expression + ": " + ex.getMessage(), ex);
    }
  }

  /**
   * Traceability: the case remembers which version of the model made the decision.
   */
  private static void rememberVersion(String model, int version) {
    try {
      var wfCase = Ivy.wfCase();
      if (wfCase != null) {
        wfCase.customFields().stringField(VERSION_FIELD_PREFIX + model).set(String.valueOf(version));
      }
    } catch (RuntimeException ex) {
      Ivy.log().debug("Rule engine: version " + version + " of model '" + model + "' not stored on the case: "
          + ex.getMessage());
    }
  }

  private Object value(String expression) {
    if (FIELD_PATH.matcher(expression).matches()) {
      var value = FieldLocator.read(data(), expression);
      if (value.isPresent()) {
        return value.get().orElse(null);
      }
    }
    // not a plain field path, e.g. a calculation: let IvyScript evaluate it
    try {
      return context.script().executeExpression(expression, Object.class).orElse(null);
    } catch (RuntimeException ex) {
      throw RuleErrors.mapping("Cannot read the input field " + expression + ": " + ex.getMessage(), ex);
    }
  }

  private Object data() {
    return context.script().executeExpression(DATA, Object.class).orElse(null);
  }

  private Class<?> dataClass() {
    Object data = data();
    return data == null ? null : data.getClass();
  }

}
