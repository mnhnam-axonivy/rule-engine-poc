package com.axonivy.utils.rule.engine.program.internal;

import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineTimeoutException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineUnreachableException;

import ch.ivyteam.ivy.bpm.error.BpmError;

/**
 * The BPMN errors of the Business Rule Task. Catch them with an Error Boundary Event or an Error Start Event.
 */
public final class RuleErrors {

  /** The decision runtime cannot be reached. */
  public static final String UNREACHABLE = "rule:engine:unreachable";
  /** The decision runtime did not answer within the timeout. */
  public static final String TIMEOUT = "rule:engine:timeout";
  /** The runtime rejected the call: unknown model or decision, invalid DMN file or a failed evaluation. */
  public static final String DMN = "rule:engine:dmn";
  /** The element is not configured completely or process data cannot be mapped to the decision. */
  public static final String MAPPING = "rule:engine:mapping";

  /** Attribute of the BPMN error: error code of the runtime, e.g. MODEL_NOT_FOUND or EVALUATION_FAILED. */
  public static final String ATTRIBUTE_CODE = "ruleEngineCode";
  /** Attribute of the BPMN error: findings of the DMN engine as text. */
  public static final String ATTRIBUTE_DETAILS = "ruleEngineDetails";
  /** Attribute of the BPMN error: name of the model. */
  public static final String ATTRIBUTE_MODEL = "ruleEngineModel";

  private RuleErrors() {}

  public static BpmError mapping(String message) {
    return BpmError.create(MAPPING).withMessage(message).build();
  }

  public static BpmError mapping(String message, Throwable cause) {
    return BpmError.create(MAPPING).withMessage(message).withCause(cause).build();
  }

  public static BpmError of(RuleEngineException ex, String model) {
    var error = BpmError.create(code(ex))
        .withMessage(ex.getMessage())
        .withCause(ex)
        .withAttribute(ATTRIBUTE_MODEL, model);
    if (ex instanceof RuleEngineApiException api) {
      error.withAttribute(ATTRIBUTE_CODE, api.code());
      if (!api.details().isEmpty()) {
        var details = new StringBuilder();
        api.details().forEach(detail -> details.append(detail.severity()).append(": ")
            .append(detail.message()).append('\n'));
        error.withAttribute(ATTRIBUTE_DETAILS, details.toString().strip());
      }
    }
    return error.build();
  }

  private static String code(RuleEngineException ex) {
    if (ex instanceof RuleEngineTimeoutException) {
      return TIMEOUT;
    }
    if (ex instanceof RuleEngineApiException) {
      return DMN;
    }
    if (ex instanceof RuleEngineUnreachableException) {
      return UNREACHABLE;
    }
    return UNREACHABLE;
  }
}
