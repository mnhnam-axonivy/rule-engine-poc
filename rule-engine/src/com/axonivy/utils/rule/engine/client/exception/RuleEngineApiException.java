package com.axonivy.utils.rule.engine.client.exception;

import java.util.List;

import com.axonivy.utils.rule.engine.api.Message;

public class RuleEngineApiException extends RuleEngineException {

  private static final long serialVersionUID = 1L;

  public static final String MODEL_NOT_FOUND = "MODEL_NOT_FOUND";
  public static final String VERSION_NOT_FOUND = "VERSION_NOT_FOUND";
  public static final String DECISION_NOT_FOUND = "DECISION_NOT_FOUND";
  public static final String NO_ACTIVE_VERSION = "NO_ACTIVE_VERSION";
  public static final String INVALID_MODEL = "INVALID_MODEL";
  public static final String INVALID_STATE = "INVALID_STATE";
  public static final String INVALID_REQUEST = "INVALID_REQUEST";
  public static final String EVALUATION_FAILED = "EVALUATION_FAILED";
  public static final String UNAUTHORIZED = "UNAUTHORIZED";
  public static final String UNKNOWN = "UNKNOWN";

  private final int statusCode;
  private final String code;
  private final transient List<Message> details;

  public RuleEngineApiException(int statusCode, String code, String message, List<Message> details, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
    this.code = code == null ? UNKNOWN : code;
    this.details = details == null ? List.of() : List.copyOf(details);
  }

  public int statusCode() {
    return statusCode;
  }

  public String code() {
    return code;
  }

  public List<Message> details() {
    return details == null ? List.of() : details;
  }

  public boolean isModelMissing() {
    return MODEL_NOT_FOUND.equals(code) || NO_ACTIVE_VERSION.equals(code);
  }
}
