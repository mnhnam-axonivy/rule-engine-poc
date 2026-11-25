package com.axonivy.utils.rule.engine.runtime.dmn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.core.internal.utils.DynamicDMNContextBuilder;
import org.kie.dmn.core.internal.utils.MarshallingStubUtils;

import com.axonivy.utils.rule.engine.runtime.api.model.Message;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Evaluates decisions of a {@link CompiledModel} with JSON like input.
 */
@ApplicationScoped
public class DmnEvaluator {

  public record Evaluation(Map<String, Object> results, List<Message> messages) {

    public boolean hasErrors() {
      return messages.stream().anyMatch(Message::isError);
    }
  }

  /**
   * @param model the version to evaluate
   * @param decision name of the decision to evaluate, or null to evaluate all decisions of the model
   * @param input values by input name. Strings are converted to dates, times and durations where the model asks for them.
   * @return the result of every evaluated decision by name
   */
  public Evaluation evaluate(CompiledModel model, String decision, Map<String, Object> input) {
    var runtime = model.runtime();
    DMNContext context = new DynamicDMNContextBuilder(runtime.newContext(), model.dmnModel())
        .populateContextWith(input == null ? Map.of() : input);
    DMNResult result = decision == null
        ? runtime.evaluateAll(model.dmnModel(), context)
        : runtime.evaluateByName(model.dmnModel(), context, decision);

    var results = new LinkedHashMap<String, Object>();
    for (DMNDecisionResult decisionResult : result.getDecisionResults()) {
      results.put(decisionResult.getDecisionName(),
          MarshallingStubUtils.stubDMNResult(decisionResult.getResult(), String::valueOf));
    }
    var messages = new ArrayList<Message>();
    result.getMessages().stream()
        .map(Messages::of)
        .filter(message -> !messages.contains(message))
        .forEach(messages::add);
    return new Evaluation(results, messages);
  }
}
