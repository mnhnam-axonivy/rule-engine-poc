package com.axonivy.utils.rule.engine.client.http.log;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axonivy.utils.rule.engine.client.http.HttpClient;
import com.axonivy.utils.rule.engine.client.http.HttpException;
import com.axonivy.utils.rule.engine.client.http.HttpRequest;
import com.axonivy.utils.rule.engine.client.http.SuccessfulHttpResponse;

/**
 * Wraps a {@link HttpClient} and logs its requests and responses. Secrets in headers are masked.
 */
public class LoggingHttpClient implements HttpClient {

  private static final Logger DEFAULT_LOG = LoggerFactory.getLogger(LoggingHttpClient.class);
  private static final Set<String> SECRET_HEADERS = Set.of("authorization", "x-api-key");

  private final HttpClient delegate;
  private final boolean logRequests;
  private final boolean logResponses;
  private final Logger log;

  public LoggingHttpClient(HttpClient delegate, Boolean logRequests, Boolean logResponses) {
    this(delegate, logRequests, logResponses, null);
  }

  public LoggingHttpClient(HttpClient delegate, Boolean logRequests, Boolean logResponses, Logger logger) {
    this.delegate = delegate;
    this.logRequests = Boolean.TRUE.equals(logRequests);
    this.logResponses = Boolean.TRUE.equals(logResponses);
    this.log = logger == null ? DEFAULT_LOG : logger;
  }

  @Override
  public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException, RuntimeException {
    if (logRequests) {
      log.info("HTTP request: {} {} headers: {} body: {}",
          request.method(), request.url(), format(request.headers()), request.body());
    }
    try {
      SuccessfulHttpResponse response = delegate.execute(request);
      if (logResponses) {
        log.info("HTTP response: {} headers: {} body: {}",
            response.statusCode(), format(response.headers()), response.body());
      }
      return response;
    } catch (HttpException ex) {
      if (logResponses) {
        log.info("HTTP response: {} body: {}", ex.statusCode(), ex.getMessage());
      }
      throw ex;
    }
  }

  public static String format(Map<String, List<String>> headers) {
    return headers.entrySet().stream()
        .map(header -> "[" + header.getKey() + ": " + mask(header.getKey(), header.getValue()) + "]")
        .collect(Collectors.joining(", "));
  }

  private static String mask(String name, List<String> values) {
    if (SECRET_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
      return "***";
    }
    return String.join(", ", values);
  }
}
