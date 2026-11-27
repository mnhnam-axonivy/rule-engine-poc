package com.axonivy.utils.rule.engine.runtime.service;

import java.util.Map;

import com.axonivy.utils.rule.engine.runtime.api.model.LoadedVersion;
import com.axonivy.utils.rule.engine.runtime.dmn.CompiledModel;
import com.axonivy.utils.rule.engine.runtime.dmn.DmnEvaluator;
import com.axonivy.utils.rule.engine.runtime.dmn.DmnEvaluator.Evaluation;
import com.axonivy.utils.rule.engine.runtime.service.RuleEngineException.Code;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DecisionService {

  private static final String DECISION_NOT_FOUND =
      "Model '%s' version %d has no decision '%s'. Available decisions: %s";
  private static final String EVALUATION_FAILED = "Evaluation of model '%s' version %d failed: %s";
  private static final String UNKNOWN_ERROR = "unknown error";

  private final ModelRegistry registry;
  private final DmnEvaluator evaluator;

  public DecisionService(ModelRegistry registry, DmnEvaluator evaluator) {
    this.registry = registry;
    this.evaluator = evaluator;
  }

  public record EvaluationOutcome(CompiledModel model, String decision, Evaluation evaluation) {}

  public EvaluationOutcome evaluate(String model, int version, String decision, Map<String, Object> input) {
    var compiled = registry.get(model, version);
    var name = decisionName(decision);
    requireDecision(compiled, name);
    var evaluation = evaluator.evaluate(compiled, name, input);
    requireNoErrors(compiled, evaluation);
    return new EvaluationOutcome(compiled, name, evaluation);
  }

  private static String decisionName(String decision) {
    return decision == null || decision.isBlank() ? null : decision.trim();
  }

  private static void requireDecision(CompiledModel compiled, String name) {
    if (name == null || compiled.hasDecision(name)) {
      return;
    }
    var known = compiled.decisions().stream().map(LoadedVersion.Decision::name).toList();
    throw new RuleEngineException(Code.DECISION_NOT_FOUND,
        DECISION_NOT_FOUND.formatted(compiled.model(), compiled.version(), name, known));
  }

  private static void requireNoErrors(CompiledModel compiled, Evaluation evaluation) {
    if (!evaluation.hasErrors()) {
      return;
    }
    var message = EVALUATION_FAILED.formatted(compiled.model(), compiled.version(), firstError(evaluation));
    throw new RuleEngineException(Code.EVALUATION_FAILED, message, evaluation.messages());
  }

  private static String firstError(Evaluation evaluation) {
    return evaluation.messages().stream()
        .filter(message -> message.isError())
        .map(message -> message.message())
        .findFirst()
        .orElse(UNKNOWN_ERROR);
  }
}
