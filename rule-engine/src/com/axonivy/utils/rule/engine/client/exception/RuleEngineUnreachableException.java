package com.axonivy.utils.rule.engine.client.exception;

/**
 * The decision runtime cannot be reached: wrong URL, container down or network problem.
 */
public class RuleEngineUnreachableException extends RuleEngineException {

  private static final long serialVersionUID = 1L;

  public RuleEngineUnreachableException(String message, Throwable cause) {
    super(message, cause);
  }
}
