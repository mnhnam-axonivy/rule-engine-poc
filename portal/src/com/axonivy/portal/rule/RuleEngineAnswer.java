package com.axonivy.portal.rule;

import java.util.List;
import java.util.Map;

/**
 * What the rule engine answered: a result made of JDK types, or an error.
 */
public record RuleEngineAnswer(Object result, RuleEngineError error) {

  static RuleEngineAnswer failure(String code, String message) {
    return new RuleEngineAnswer(null, new RuleEngineError(code, message, List.of()));
  }

  public boolean isOk() {
    return error == null;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> map() {
    return result instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> list() {
    return result instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }
}
