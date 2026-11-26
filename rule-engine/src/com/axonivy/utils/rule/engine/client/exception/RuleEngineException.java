package com.axonivy.utils.rule.engine.client.exception;

public class RuleEngineException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RuleEngineException(String message) {
    super(message);
  }

  public RuleEngineException(String message, Throwable cause) {
    super(message, cause);
  }
}
