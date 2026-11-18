package com.axonivy.utils.rule.engine.client.http;

/**
 * The server answered with a client (4XX) or server (5XX) error. The message is the response body.
 */
public class HttpException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int statusCode;

  public HttpException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
