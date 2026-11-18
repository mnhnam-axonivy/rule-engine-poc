package com.axonivy.utils.rule.engine.client.exception;

/**
 * The decision runtime did not answer within the configured timeout.
 */
public class RuleEngineTimeoutException extends RuleEngineException {

  private static final long serialVersionUID = 1L;

  public RuleEngineTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}
