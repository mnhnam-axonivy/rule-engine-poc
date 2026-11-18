package com.axonivy.utils.rule.engine.program.internal;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.axonivy.utils.rule.engine.RuleEngine;
import com.axonivy.utils.rule.engine.RuleEngineConfig;
import com.axonivy.utils.rule.engine.api.EvaluateRequest;
import com.axonivy.utils.rule.engine.api.EvaluateResponse;
import com.axonivy.utils.rule.engine.api.ModelInfo;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.client.RuleEngineClient;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineException;
import com.axonivy.utils.rule.engine.deploy.ActiveVersionInputs;
import com.axonivy.utils.rule.engine.deploy.ModelDeployer;
import com.axonivy.utils.rule.engine.dmn.DmnModelInfo;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles.LoadedDmn;
import com.axonivy.utils.rule.engine.mapping.FieldLocator;
import com.axonivy.utils.rule.engine.mapping.ValueConverter;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.bpm.error.BpmError;
import ch.ivyteam.ivy.environment.Ivy;
import ch.ivyteam.ivy.process.program.exec.ProgramContext;

/**
 * Runs a Business Rule Task: maps process data to the inputs of the decision, lets the runtime evaluate the
 * active version of the model and writes the results back to the process data.
 */
public class RuleTaskExecutor {

  /** The variable of the process data in IvyScript. */
  private static final String DATA = "in";
  /** A field of the process data, e.g. <code>in.member.purchaseAmount</code>. */
  private static final java.util.regex.Pattern FIELD_PATH = java.util.regex.Pattern
      .compile(DATA + "(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
  /** Prefix of the case custom field that stores the model version that produced the result. */
  public static final String VERSION_FIELD_PREFIX = "ruleVersion_";

  interface Variable {
    String RESULT = "result";
    String VERSION = "modelVersion";
  }

  private record Selection(String model, String decision) {}

  private final ProgramContext context;

  public RuleTaskExecutor(ProgramContext context) {
    this.context = context;
  }

  public void execute() {
    try {
      Class<?> dataClass = dataClass();
      Selection selection = selection(dataClass);
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
    String runtimeModel = config.runtimeModel(selection.model());
    Optional<LoadedDmn> dmn = ProjectDmnFiles.find(Project.current(), selection.model()).map(ProjectDmnFiles::load);
    boolean deploy = config.autoDeploy() && dmn.isPresent();

    List<String> needed = inputNames(selection, dmn, client, runtimeModel);
    Optional<ActiveVersionInputs.Active> active = dmn.isPresent()
        ? ActiveVersionInputs.of(client, runtimeModel, selection.decision())
        : Optional.empty();
    var input = input(withInputsOfAnsweringVersion(needed, deploy, active), selection, dataClass);

    if (deploy) {
      ModelDeployer.ensureRegistered(client, runtimeModel, dmn.get());
    }
    EvaluateResponse response;
    try {
      response = client.evaluate(runtimeModel, request(selection, input));
    } catch (RuleEngineApiException ex) {
      if (!deploy || !ex.isModelMissing()) {
        throw ex;
      }
      // the runtime lost the model, e.g. it was started with an empty volume: load it again
      ModelDeployer.forget(client, runtimeModel);
      ModelDeployer.register(client, runtimeModel, dmn.get());
      response = client.evaluate(runtimeModel, request(selection, input));
    }
    if (dmn.isPresent() && (active.isEmpty() || response.version() != active.get().version())) {
      // Another version answered than the one the inputs were chosen for, e.g. a rule owner activated one a moment
      // ago, or the runtime knew no version of the model yet. Ask again; if the version that answered needs more
      // inputs, evaluate once more with them.
      ActiveVersionInputs.forget(client, runtimeModel);
      var now = ActiveVersionInputs.of(client, runtimeModel, selection.decision());
      List<String> names = withInputsOfAnsweringVersion(needed, deploy, now);
      if (!input.keySet().containsAll(names)) {
        input = input(names, selection, dataClass);
        response = client.evaluate(runtimeModel, request(selection, input));
      }
    }
    mapResults(response, selection, dataClass);
  }

  private static EvaluateRequest request(Selection selection, Map<String, Object> input) {
    return EvaluateRequest.builder().decision(selection.decision()).input(input).build();
  }

  /**
   * @return the value of every input, read from the field of the process data with the name of the input. A Script
   *         before the Business Rule Task prepares a value that is not in such a field yet.
   */
  private Map<String, Object> input(List<String> names, Selection selection, Class<?> dataClass) {
    var input = new LinkedHashMap<String, Object>();
    var unmapped = new ArrayList<String>();
    for (String name : names) {
      var field = dataClass == null ? Optional.<FieldLocator.Field>empty() : FieldLocator.findByName(dataClass, DATA, name);
      if (field.isEmpty()) {
        unmapped.add(name);
      } else {
        input.put(name, ValueConverter.toJson(value(field.get().expression())));
      }
    }
    if (!unmapped.isEmpty()) {
      throw RuleErrors.mapping("No process data field found for the input(s) " + unmapped + " of the decision '"
          + selection.decision() + "' in model '" + selection.model() + "'. The process data needs a field named "
          + "like each input, e.g. in." + unmapped.get(0) + ", set before the Business Rule Task.");
    }
    return input;
  }

  private Selection selection(Class<?> dataClass) {
    List<String> selected = context.config().getList(Conf.DECISION);
    String value = selected.isEmpty() ? "" : StringUtils.trimToEmpty(selected.get(0));
    if (value.isEmpty()) {
      throw RuleErrors.mapping("No decision selected. Choose a decision of a .dmn file in the Business Rule Task '"
          + context.element().name() + "'.");
    }
    if (selected.size() > 1) {
      Ivy.log().warn("Only one decision is evaluated. Using " + value + " and ignoring the others of " + selected);
    }
    if (Conf.CREATE_NEW.equals(value)) {
      if (dataClass == null) {
        throw RuleErrors.mapping("Cannot create a DMN: the process has no data.");
      }
      try {
        var created = DmnCreator.create(Project.current(), dataClass, context.config().get(Conf.NEW_MODEL),
            FieldSelection.parse(context.config().get(Conf.INPUTS)),
            FieldSelection.parse(context.config().get(Conf.OUTPUTS)));
        return new Selection(created.model(), created.decision());
      } catch (IllegalArgumentException | IllegalStateException ex) {
        Ivy.log().error("Rule engine: the Business Rule Task '" + StringUtils.normalizeSpace(context.element().name())
            + "' cannot create its new DMN. " + ex.getMessage());
        throw RuleErrors.mapping(ex.getMessage(), ex);
      }
    }
    int separator = value.indexOf(Conf.SEPARATOR);
    String model = separator < 0 ? value : value.substring(0, separator);
    String decision = separator < 0 ? null : StringUtils.trimToNull(value.substring(separator + 1));
    return new Selection(model.trim(), decision);
  }

  /**
   * The version that answers may not be the file of the project: a version that a rule owner activated in the Portal
   * or through the API stays active, the file is only stored as a draft beside it. Such a version may need inputs the
   * file does not have, e.g. a column the rule owner added. A version from a project is replaced by the file.
   *
   * @return the inputs of the file, and those of the active version if that version answers instead of the file
   */
  private static List<String> withInputsOfAnsweringVersion(List<String> fileInputs, boolean deploy,
      Optional<ActiveVersionInputs.Active> active) {
    if (active.isEmpty() || (deploy && active.get().source() == VersionSource.PROJECT)) {
      return fileInputs;
    }
    var names = new ArrayList<>(fileInputs);
    active.get().inputs().stream().filter(name -> !names.contains(name)).forEach(names::add);
    return names;
  }

  /**
   * @return the inputs the decision needs. Taken from the .dmn file of the project,
   *         or from the runtime if the project has no such file.
   */
  private static List<String> inputNames(Selection selection, Optional<LoadedDmn> dmn, RuleEngineClient client,
      String runtimeModel) {
    if (dmn.isPresent() && dmn.get().info() != null) {
      DmnModelInfo info = dmn.get().info();
      if (selection.decision() == null) {
        return info.inputNames();
      }
      return info.decision(selection.decision())
          .map(DmnModelInfo.Decision::requiredInputs)
          .orElseThrow(() -> new IllegalArgumentException("The file " + dmn.get().file().path().getFileName()
              + " has no decision '" + selection.decision() + "'. Its decisions are: "
              + info.decisions().stream().map(DmnModelInfo.Decision::name).toList()));
    }
    ModelInfo info = client.model(runtimeModel);
    if (selection.decision() == null) {
      return info.inputs().stream().map(ModelInfo.Input::name).toList();
    }
    return info.decisions().stream()
        .filter(decision -> decision.name().equals(selection.decision()))
        .findFirst()
        .map(ModelInfo.Decision::requiredInputs)
        .orElseThrow(() -> new IllegalArgumentException("The model '" + runtimeModel + "' has no decision '"
            + selection.decision() + "'."));
  }

  private void mapResults(EvaluateResponse response, Selection selection, Class<?> dataClass) {
    if (dataClass != null) {
      Map<String, Object> named = selection.decision() == null
          ? response.results()
          : singleResult(selection.decision(), mainResult(response, selection));
      named.forEach((name, value) -> mapByName(name, value, dataClass));
    }
    rememberVersion(selection.model(), response.version());
  }

  /** @return the result of the selected decision, or all results if the whole model was evaluated */
  private static Object mainResult(EvaluateResponse response, Selection selection) {
    if (selection.decision() != null) {
      return response.results().get(selection.decision());
    }
    return response.results().size() == 1 ? response.results().values().iterator().next() : response.results();
  }

  private static Map<String, Object> singleResult(String decision, Object value) {
    var result = new LinkedHashMap<String, Object>();
    result.put(decision, value);
    return result;
  }

  /**
   * A result goes to the field with its name. A structured result without such a field is unfolded:
   * each of its entries goes to the field with the name of the entry.
   */
  private void mapByName(String name, Object value, Class<?> dataClass) {
    var field = FieldLocator.findByName(dataClass, DATA, name);
    if (field.isPresent()) {
      assign(field.get().expression(), value, field.get().type());
    } else if (value instanceof Map<?, ?> structure) {
      structure.forEach((key, entry) -> FieldLocator.findByName(dataClass, DATA, String.valueOf(key))
          .ifPresent(target -> assign(target.expression(), entry, target.type())));
    }
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
