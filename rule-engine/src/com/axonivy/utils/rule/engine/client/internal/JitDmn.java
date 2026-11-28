package com.axonivy.utils.rule.engine.client.internal;

import java.util.List;
import java.util.Map;

import com.axonivy.utils.rule.engine.api.Message;
import com.axonivy.utils.rule.engine.client.DmnResult;

/**
 * The JSON of the Apache KIE JIT runner, see jitexecutor-dmn of incubator-kie-kogito-apps.
 */
public final class JitDmn {

  private static final String ENGINE_PREFIX = "DMN: ";
  private static final String NO_FILE = "of DMN file: null - ";
  private static final String READABLE_NO_FILE = "of the DMN file: ";

  private JitDmn() {}

  public record Request(String model, Map<String, Object> context) {}

  public record Result(List<DecisionResult> decisionResults, List<Finding> messages) {}

  public record DecisionResult(String decisionName, Object result, List<Finding> messages, String evaluationStatus) {}

  public record Finding(String severity, String message, String messageType, String sourceId) {}

  public static DmnResult toResult(Result result) {
    var decisions = listOf(result == null ? null : result.decisionResults()).stream()
        .map(decision -> new DmnResult.Decision(decision.decisionName(), decision.result(),
            decision.evaluationStatus(), messages(decision.messages())))
        .toList();
    return new DmnResult(decisions, messages(result == null ? null : result.messages()));
  }

  public static List<Message> messages(List<Finding> findings) {
    return listOf(findings).stream().map(JitDmn::message).distinct().toList();
  }

  private static Message message(Finding finding) {
    return new Message(severity(finding.severity()), readable(finding.message()), finding.messageType(),
        finding.sourceId());
  }

  /** The engine answers ERROR, WARN or INFO; a finding without severity is taken as an error. */
  private static String severity(String severity) {
    return severity == null || severity.isBlank() ? Message.ERROR : severity;
  }

  private static String readable(String text) {
    if (text == null) {
      return "";
    }
    String readable = text.strip();
    if (readable.startsWith(ENGINE_PREFIX)) {
      readable = readable.substring(ENGINE_PREFIX.length());
    }
    return readable.replace(NO_FILE, READABLE_NO_FILE);
  }

  private static <T> List<T> listOf(List<T> list) {
    return list == null ? List.of() : list;
  }
}
