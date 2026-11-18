package com.axonivy.utils.rule.engine.portal;

import java.util.Map;

/**
 * The answer of a call of {@link RuleManagement}: the result, or the error.
 *
 * @param result made of JDK types only: String, Integer, Boolean, List and Map
 * @param error null on success, else code, message and details
 */
public record RuleManagementAnswer(Object result, Map<String, Object> error) {}
