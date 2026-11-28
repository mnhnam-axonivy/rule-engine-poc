package com.axonivy.utils.rule.engine.deploy;

import java.util.LinkedHashMap;
import java.util.List;

import com.axonivy.utils.rule.engine.api.EvaluateRequest;
import com.axonivy.utils.rule.engine.api.EvaluateResponse;
import com.axonivy.utils.rule.engine.api.Message;
import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.client.DmnResult;
import com.axonivy.utils.rule.engine.client.RuleEngineClient;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.version.VersionStore;

/**
 * Evaluates a stored version: the DMN engine gets the file of the version with each evaluation.
 */
public final class Evaluator {

  private static final String DECISION_NOT_FOUND =
      "Version %d of model '%s' has no decision '%s'. Its decisions are: %s";
  private static final String EVALUATION_FAILED = "Evaluation of version %d of model '%s' failed: %s";
  private static final String UNKNOWN_ERROR = "unknown error";
  private static final int NOT_FOUND = 404;
  private static final int UNPROCESSABLE = 422;

  private Evaluator() {}

  /**
   * @throws RuleEngineApiException DECISION_NOT_FOUND, or EVALUATION_FAILED with the findings of the DMN engine
   */
  public static EvaluateResponse evaluate(RuleEngineClient client, VersionStore store, ModelVersion version,
      EvaluateRequest request) {
    var result = client.evaluate(store.content(version.model(), version.version()), request.input());
    var decision = decisionName(request.decision());
    return decision == null ? all(version, result) : one(version, decision, result);
  }

  private static EvaluateResponse all(ModelVersion version, DmnResult result) {
    requireNoErrors(version, result.messages());
    return response(version, null, null, result);
  }

  /** Only the asked decision counts: the engine evaluates the others too, and they may lack inputs. */
  private static EvaluateResponse one(ModelVersion version, String name, DmnResult result) {
    var decision = result.decision(name).orElseThrow(() -> new RuleEngineApiException(NOT_FOUND,
        RuleEngineApiException.DECISION_NOT_FOUND,
        DECISION_NOT_FOUND.formatted(version.version(), version.model(), name, result.decisionNames()), null, null));
    if (!decision.succeeded()) {
      throw failed(version, decision.messages());
    }
    return response(version, name, decision.result(), new DmnResult(List.of(decision), decision.messages()));
  }

  private static EvaluateResponse response(ModelVersion version, String decision, Object result, DmnResult evaluated) {
    var results = new LinkedHashMap<String, Object>();
    evaluated.decisions().stream()
        .filter(DmnResult.Decision::succeeded)
        .forEach(each -> results.put(each.name(), each.result()));
    var messages = evaluated.messages();
    return new EvaluateResponse(version.model(), version.version(), decision, result, results,
        messages.isEmpty() ? null : messages);
  }

  private static void requireNoErrors(ModelVersion version, List<Message> messages) {
    if (messages.stream().anyMatch(Message::isError)) {
      throw failed(version, messages);
    }
  }

  private static RuleEngineApiException failed(ModelVersion version, List<Message> messages) {
    String first = messages.stream().filter(Message::isError).map(Message::message).findFirst().orElse(UNKNOWN_ERROR);
    return new RuleEngineApiException(UNPROCESSABLE, RuleEngineApiException.EVALUATION_FAILED,
        EVALUATION_FAILED.formatted(version.version(), version.model(), first), messages, null);
  }

  private static String decisionName(String decision) {
    return decision == null || decision.isBlank() ? null : decision.trim();
  }
}
