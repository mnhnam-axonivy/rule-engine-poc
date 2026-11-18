package com.axonivy.utils.rule.engine.runtime.service;

import java.util.List;

import com.axonivy.utils.rule.engine.api.Message;


/**
 * A failure with a stable error code that API clients can react to.
 */
public class RuleEngineException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public enum Code {
    INVALID_REQUEST(400),
    INVALID_MODEL(400),
    UNAUTHORIZED(401),
    MODEL_NOT_FOUND(404),
    VERSION_NOT_FOUND(404),
    DECISION_NOT_FOUND(404),
    NO_ACTIVE_VERSION(409),
    INVALID_STATE(409),
    EVALUATION_FAILED(422),
    INTERNAL_ERROR(500);

    private final int status;

    Code(int status) {
      this.status = status;
    }

    public int status() {
      return status;
    }
  }

  private final Code code;
  private final transient List<Message> details;

  public RuleEngineException(Code code, String message) {
    this(code, message, List.of());
  }

  public RuleEngineException(Code code, String message, List<Message> details) {
    super(message);
    this.code = code;
    this.details = List.copyOf(details);
  }

  public Code code() {
    return code;
  }

  public List<Message> details() {
    return details;
  }

  public static RuleEngineException modelNotFound(String model) {
    return new RuleEngineException(Code.MODEL_NOT_FOUND, "Model '" + model + "' is not loaded.");
  }

  public static RuleEngineException versionNotFound(String model, int version) {
    return new RuleEngineException(Code.VERSION_NOT_FOUND,
        "Model '" + model + "' has no version " + version + ".");
  }

  public static RuleEngineException noActiveVersion(String model) {
    return new RuleEngineException(Code.NO_ACTIVE_VERSION,
        "Model '" + model + "' has no active version. Activate one of its versions first.");
  }

  public static RuleEngineException invalidState(String message) {
    return new RuleEngineException(Code.INVALID_STATE, message);
  }

  public static RuleEngineException invalidRequest(String message) {
    return new RuleEngineException(Code.INVALID_REQUEST, message);
  }
}
