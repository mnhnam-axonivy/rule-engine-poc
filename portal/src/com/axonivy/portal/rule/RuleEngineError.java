package com.axonivy.portal.rule;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Why a call of the rule engine failed.
 *
 * @param code e.g. UNREACHABLE, TIMEOUT, NOT_CONFIGURED, INVALID_MODEL (the model has errors, see the details),
 *          MODEL_NOT_FOUND, INVALID_STATE, NOT_AVAILABLE or FAILED
 * @param details the findings of the DMN engine, as text
 */
public record RuleEngineError(String code, String message, List<String> details) implements Serializable {

  public static final String UNREACHABLE = "UNREACHABLE";
  public static final String TIMEOUT = "TIMEOUT";
  public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
  public static final String INVALID_MODEL = "INVALID_MODEL";
  public static final String NOT_AVAILABLE = "NOT_AVAILABLE";
  public static final String FAILED = "FAILED";

  /** @return the error of an answer of the rule engine, null if there is none */
  static RuleEngineError of(Object error) {
    if (!(error instanceof Map<?, ?> map)) {
      return null;
    }
    Object details = map.get("details");
    return new RuleEngineError(
        String.valueOf(map.get("code")),
        map.get("message") == null ? "" : String.valueOf(map.get("message")),
        details instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of());
  }

  /** @return true if the runtime cannot be asked at all */
  public boolean isConnectionProblem() {
    return UNREACHABLE.equals(code) || TIMEOUT.equals(code) || NOT_CONFIGURED.equals(code);
  }
}
