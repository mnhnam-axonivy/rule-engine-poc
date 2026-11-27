package com.axonivy.utils.rule.engine.runtime.service;

import java.util.Map;

import com.axonivy.utils.rule.engine.runtime.api.model.LoadedVersion;
import com.axonivy.utils.rule.engine.runtime.dmn.CompiledModel;
import com.axonivy.utils.rule.engine.runtime.dmn.DmnEvaluator;
import com.axonivy.utils.rule.engine.runtime.dmn.DmnEvaluator.Evaluation;
import com.axonivy.utils.rule.engine.runtime.service.RuleEngineException.Code;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DecisionService {

  @Inject
  ModelRegistry registry;

  @Inject
  DmnEvaluator evaluator;

  public record EvaluationOutcome(CompiledModel model, String decision, Evaluation evaluation) {}

  /** @param decision null evaluates all decisions of the model */
  public EvaluationOutcome evaluate(String model, int version, String decision, Map<String, Object> input) {
    var compiled = registry.get(model, version);
    var name = decision == null || decision.isBlank() ? null : decision.trim();
    if (name != null && !compiled.hasDecision(name)) {
      var known = compiled.decisions().stream().map(LoadedVersion.Decision::name).toList();
      throw new RuleEngineException(Code.DECISION_NOT_FOUND, "Model '" + compiled.model() + "' version "
          + compiled.version() + " has no decision '" + name + "'. Available decisions: " + known);
    }
    var evaluation = evaluator.evaluate(compiled, name, input);
    if (evaluation.hasErrors()) {
      var first = evaluation.messages().stream()
          .filter(message -> message.isError())
          .map(message -> message.message())
          .findFirst()
          .orElse("unknown error");
      throw new RuleEngineException(Code.EVALUATION_FAILED, "Evaluation of model '" + compiled.model()
          + "' version " + compiled.version() + " failed: " + first, evaluation.messages());
    }
    return new EvaluationOutcome(compiled, name, evaluation);
  }
}
