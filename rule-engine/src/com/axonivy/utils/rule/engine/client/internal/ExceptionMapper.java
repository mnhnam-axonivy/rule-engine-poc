package com.axonivy.utils.rule.engine.client.internal;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

import com.axonivy.utils.rule.engine.api.ErrorResponse;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineTimeoutException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineUnreachableException;
import com.axonivy.utils.rule.engine.client.http.HttpException;

/**
 * Turns the failures of the HTTP transport into the exceptions of the rule engine client.
 */
public final class ExceptionMapper {

  private ExceptionMapper() {}

  public static <T> T withExceptionMapper(String baseUrl, Callable<T> action) {
    try {
      return action.call();
    } catch (Exception ex) {
      throw map(baseUrl, ex);
    }
  }

  static RuntimeException map(String baseUrl, Exception ex) {
    if (ex instanceof RuleEngineException known) {
      return known;
    }
    if (ex instanceof HttpException http) {
      return apiException(http);
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

  private static RuleEngineUnreachableException unreachable(String baseUrl, Exception ex) {
    return new RuleEngineUnreachableException("The rule engine is not reachable at " + baseUrl
        + ". Check the variable RuleEngine.Url and that the runtime container is running.", ex);
  }

  private static RuleEngineApiException apiException(HttpException http) {
    String body = http.getMessage();
    try {
      ErrorResponse error = Json.fromJson(body, ErrorResponse.class);
      if (error != null && (error.code() != null || error.message() != null)) {
        return new RuleEngineApiException(http.statusCode(), error.code(), error.message(), error.details(), http);
      }
    } catch (RuntimeException notJson) {
      // an answer that does not come from the runtime, e.g. from a proxy in between
    }
    String code = http.statusCode() == 401 || http.statusCode() == 403
        ? RuleEngineApiException.UNAUTHORIZED
        : RuleEngineApiException.UNKNOWN;
    return new RuleEngineApiException(http.statusCode(), code,
        "The rule engine answered with HTTP " + http.statusCode() + (body == null ? "" : ": " + body), null, http);
  }

  private static Throwable next(Throwable cause) {
    return cause.getCause() == cause ? null : cause.getCause();
  }
}
