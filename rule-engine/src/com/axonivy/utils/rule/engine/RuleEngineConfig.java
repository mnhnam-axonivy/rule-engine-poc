package com.axonivy.utils.rule.engine;

import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import ch.ivyteam.ivy.environment.Ivy;

/**
 * The settings of the rule engine connector, read from the variables <code>RuleEngine.*</code>.
 */
public record RuleEngineConfig(
    String url,
    Duration timeout,
    Duration connectTimeout,
    boolean autoDeploy,
    boolean logRequests) {

  public interface Var {
    String URL = "RuleEngine.Url";
    String TIMEOUT_SECONDS = "RuleEngine.TimeoutSeconds";
    String CONNECT_TIMEOUT_SECONDS = "RuleEngine.ConnectTimeoutSeconds";
    String AUTO_DEPLOY = "RuleEngine.AutoDeploy";
    String LOG_REQUESTS = "RuleEngine.LogRequests";
  }

  private static final double DEFAULT_TIMEOUT_SECONDS = 10;
  private static final double DEFAULT_CONNECT_TIMEOUT_SECONDS = 5;

  public static RuleEngineConfig fromVariables() {
    return new RuleEngineConfig(
        StringUtils.trimToEmpty(Ivy.var().get(Var.URL)),
        seconds(Ivy.var().get(Var.TIMEOUT_SECONDS), DEFAULT_TIMEOUT_SECONDS),
        seconds(Ivy.var().get(Var.CONNECT_TIMEOUT_SECONDS), DEFAULT_CONNECT_TIMEOUT_SECONDS),
        !"false".equalsIgnoreCase(StringUtils.trimToEmpty(Ivy.var().get(Var.AUTO_DEPLOY))),
        "true".equalsIgnoreCase(StringUtils.trimToEmpty(Ivy.var().get(Var.LOG_REQUESTS))));
  }

  public static Duration seconds(Number seconds) {
    return Duration.ofMillis(Math.max(1, Math.round(seconds.doubleValue() * 1000)));
  }

  private static Duration seconds(String value, double defaultSeconds) {
    double seconds = NumberUtils.toDouble(StringUtils.trimToEmpty(value), defaultSeconds);
    return seconds(seconds <= 0 ? defaultSeconds : seconds);
  }
}
