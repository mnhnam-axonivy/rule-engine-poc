package com.axonivy.utils.rule.engine.client.internal;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeoutException;

import com.axonivy.utils.rule.engine.client.exception.RuleEngineApiException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineTimeoutException;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineUnreachableException;

import jakarta.ws.rs.core.Response;

public final class ExceptionMapper {

  private static final String TIMEOUT = "The DMN engine at %s did not answer in time: %s";
  private static final String ABORTED = "The call to the DMN engine at %s was aborted.";
  private static final String FAILED = "The call to the DMN engine at %s failed: %s";
  private static final String UNREACHABLE =
      "The DMN engine is not reachable at %s. Check the variable RuleEngine.Url and that its container is running.";
  private static final String UNREADABLE_MODEL = "The DMN engine cannot read the model: %s";
  private static final String ANSWER = "The DMN engine answered with HTTP %d%s";
  private static final int BAD_REQUEST = 400;

  private ExceptionMapper() {}

  /**
   * @param ex what the REST client threw: its cause is the failure of the connection, e.g. a timeout
   */
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
        return new RuleEngineTimeoutException(TIMEOUT.formatted(baseUrl, cause.getMessage()), ex);
      }
      if (cause instanceof ConnectException || cause instanceof UnknownHostException
          || cause instanceof NoRouteToHostException || cause instanceof UnresolvedAddressException) {
        return unreachable(baseUrl, ex);
      }
      if (cause instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        return new RuleEngineTimeoutException(ABORTED.formatted(baseUrl), ex);
      }
    }
    return new RuleEngineException(FAILED.formatted(baseUrl, ex.getMessage()), ex);
  }

  /** @param response the error answer that JAX-RS hands over with its WebApplicationException */
  public static RuleEngineApiException answer(Response response) {
    String body = response.hasEntity() ? response.readEntity(String.class) : null;
    return answer(response.getStatus(), body);
  }

  /** The JIT runner answers 400 with the message of the DMN engine when it cannot read the model. */
  public static RuleEngineApiException answer(int status, String body) {
    String text = body == null ? "" : body.strip();
    if (status == BAD_REQUEST) {
      return new RuleEngineApiException(status, RuleEngineApiException.INVALID_MODEL,
          UNREADABLE_MODEL.formatted(text), null, null);
    }
    String code = status == 401 || status == 403
        ? RuleEngineApiException.UNAUTHORIZED
        : RuleEngineApiException.UNKNOWN;
    return new RuleEngineApiException(status, code, ANSWER.formatted(status, text.isEmpty() ? "" : ": " + text),
        null, null);
  }

  private static RuleEngineUnreachableException unreachable(String baseUrl, Exception ex) {
    return new RuleEngineUnreachableException(UNREACHABLE.formatted(baseUrl), ex);
  }

  private static Throwable next(Throwable cause) {
    return cause.getCause() == cause ? null : cause.getCause();
  }
}
