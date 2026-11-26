package com.axonivy.utils.rule.engine.client.internal;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeoutException;

import com.axonivy.utils.rule.engine.api.ErrorResponse;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineTimeoutException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineUnreachableException;

public final class ExceptionMapper {

  private ExceptionMapper() {}

  public static RuntimeException map(String baseUrl, Exception ex) {
    if (ex instanceof RuleEngineException known) {
      return known;
    }
    for (Throwable cause = ex; cause != null; cause = next(cause)) {
      if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException
          || cause instanceof TimeoutException) {
        // a connect timeout means nobody answers at that address
        if (String.valueOf(cause.getMessage()).toLowerCase().contains("connect")) {
          return unreachable(baseUrl, ex);
        }
        return new RuleEngineTimeoutException("The rule engine at " + baseUrl
            + " did not answer in time: " + cause.getMessage(), ex);
      }
      if (cause instanceof ConnectException || cause instanceof UnknownHostException
          || cause instanceof NoRouteToHostException || cause instanceof UnresolvedAddressException) {
        return unreachable(baseUrl, ex);
      }
      if (cause instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        return new RuleEngineTimeoutException("The call to the rule engine at " + baseUrl + " was aborted.", ex);
      }
    }
    return new RuleEngineException("The call to the rule engine at " + baseUrl + " failed: " + ex.getMessage(), ex);
  }

  public static RuleEngineApiException answer(int status, String body) {
    try {
      ErrorResponse error = Json.fromJson(body, ErrorResponse.class);
      if (error != null && (error.code() != null || error.message() != null)) {
        return new RuleEngineApiException(status, error.code(), error.message(), error.details(), null);
      }
    } catch (RuntimeException notJson) {
      // an answer that does not come from the runtime, e.g. from a proxy in between
    }
    String code = status == 401 || status == 403
        ? RuleEngineApiException.UNAUTHORIZED
        : RuleEngineApiException.UNKNOWN;
    return new RuleEngineApiException(status, code,
        "The rule engine answered with HTTP " + status + (body == null || body.isEmpty() ? "" : ": " + body), null, null);
  }

  private static RuleEngineUnreachableException unreachable(String baseUrl, Exception ex) {
    return new RuleEngineUnreachableException("The rule engine is not reachable at " + baseUrl
        + ". Check the variable RuleEngine.Url and that the runtime container is running.", ex);
  }

  private static Throwable next(Throwable cause) {
    return cause.getCause() == cause ? null : cause.getCause();
  }
}
