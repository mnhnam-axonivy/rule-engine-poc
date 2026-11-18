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
    String apiKey,
    Duration timeout,
    Duration connectTimeout,
    boolean autoDeploy,
    String modelPrefix,
    boolean logRequests) {

  public interface Var {
    String URL = "RuleEngine.Url";
    String API_KEY = "RuleEngine.ApiKey";
    String TIMEOUT_SECONDS = "RuleEngine.TimeoutSeconds";
    String CONNECT_TIMEOUT_SECONDS = "RuleEngine.ConnectTimeoutSeconds";
    String AUTO_DEPLOY = "RuleEngine.AutoDeploy";
    String MODEL_PREFIX = "RuleEngine.ModelPrefix";
    String LOG_REQUESTS = "RuleEngine.LogRequests";
  }

  private static final double DEFAULT_TIMEOUT_SECONDS = 10;
  private static final double DEFAULT_CONNECT_TIMEOUT_SECONDS = 5;

  public static RuleEngineConfig fromVariables() {
    return new RuleEngineConfig(
        StringUtils.trimToEmpty(Ivy.var().get(Var.URL)),
        StringUtils.trimToNull(Ivy.var().get(Var.API_KEY)),
        seconds(Ivy.var().get(Var.TIMEOUT_SECONDS), DEFAULT_TIMEOUT_SECONDS),
        seconds(Ivy.var().get(Var.CONNECT_TIMEOUT_SECONDS), DEFAULT_CONNECT_TIMEOUT_SECONDS),
        !"false".equalsIgnoreCase(StringUtils.trimToEmpty(Ivy.var().get(Var.AUTO_DEPLOY))),
        StringUtils.trimToEmpty(Ivy.var().get(Var.MODEL_PREFIX)),
        "true".equalsIgnoreCase(StringUtils.trimToEmpty(Ivy.var().get(Var.LOG_REQUESTS))));
  }

  public RuleEngineConfig withTimeout(Duration newTimeout) {
    return new RuleEngineConfig(url, apiKey, newTimeout, connectTimeout, autoDeploy, modelPrefix, logRequests);
  }

  /** @return the name of a model on the runtime: the name of the .dmn file with the configured prefix */
  public String runtimeModel(String model) {
    return modelPrefix + model;
  }

  /**
   * @param runtimeUrl the value of {@link Var#URL}
   * @return the DMN editor page that the decision runtime serves, e.g. http://localhost:8090/editor/
   */
  public static String editorUrl(String runtimeUrl) {
    String base = StringUtils.trimToEmpty(runtimeUrl);
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return (base.isEmpty() ? "<" + Var.URL + ">" : base) + "/editor/";
  }

  public static Duration seconds(Number seconds) {
    return Duration.ofMillis(Math.max(1, Math.round(seconds.doubleValue() * 1000)));
  }

  private static Duration seconds(String value, double defaultSeconds) {
    double seconds = NumberUtils.toDouble(StringUtils.trimToEmpty(value), defaultSeconds);
    return seconds(seconds <= 0 ? defaultSeconds : seconds);
  }
}
