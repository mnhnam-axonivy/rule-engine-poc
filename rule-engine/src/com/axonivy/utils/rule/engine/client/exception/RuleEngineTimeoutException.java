package com.axonivy.utils.rule.engine.client.exception;

public class RuleEngineTimeoutException extends RuleEngineException {

  private static final long serialVersionUID = 1L;

  public RuleEngineTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}
