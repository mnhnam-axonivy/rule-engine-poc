package com.axonivy.utils.rule.engine.client.http;

/**
 * A client for executing HTTP requests. The decision runtime is called through this abstraction,
 * so the transport can be replaced: the default uses the REST client of Axon Ivy.
 */
public interface HttpClient {

  /**
   * Executes a HTTP request and returns the response. Blocks until the entire response is received.
   *
   * @param request the HTTP request to be executed
   * @return the response of a successful request (2XX status codes)
   * @throws HttpException if the server returns a client (4XX) or server (5XX) error response
   * @throws RuntimeException if the request cannot be executed, e.g. network issues or timeouts
   */
  SuccessfulHttpResponse execute(HttpRequest request) throws HttpException, RuntimeException;
}
