package com.axonivy.utils.rule.engine.client.exception;

/**
 * Base of all failures when talking to the decision runtime.
 */
public class RuleEngineException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RuleEngineException(String message) {
    super(message);
  }

  public RuleEngineException(String message, Throwable cause) {
    super(message, cause);
  }
}
