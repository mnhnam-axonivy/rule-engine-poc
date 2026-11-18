package com.axonivy.utils.rule.engine.client.http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class HttpRequest {

  private final HttpMethod method;
  private final String url;
  private final Map<String, List<String>> headers;
  private final String body;

  public HttpRequest(Builder builder) {
    if (builder.method == null) {
      throw new IllegalArgumentException("method cannot be null");
    }
    if (builder.url == null || builder.url.isBlank()) {
      throw new IllegalArgumentException("url cannot be null or blank");
    }
    this.method = builder.method;
    this.url = buildUrl(builder);
    this.headers = builder.headers == null ? Map.of() : Map.copyOf(builder.headers);
    this.body = builder.body;
  }

  private static String buildUrl(Builder builder) {
    if (builder.queryParams == null || builder.queryParams.isEmpty()) {
      return builder.url;
    }
    String query = builder.queryParams.entrySet().stream()
        .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
        .collect(Collectors.joining("&"));
    return builder.url + (builder.url.contains("?") ? "&" : "?") + query;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  public HttpMethod method() {
    return method;
  }

  public String url() {
    return url;
  }

  public Map<String, List<String>> headers() {
    return headers;
  }

  public String body() {
    return body;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private HttpMethod method;
    private String url;
    private Map<String, List<String>> headers;
    private Map<String, String> queryParams;
    private String body;

    private Builder() {}

    public Builder method(HttpMethod method) {
      this.method = method;
      return this;
    }

    public Builder url(String url) {
      this.url = url;
      return this;
    }

    public Builder url(String baseUrl, String path) {
      if (baseUrl == null || baseUrl.isBlank()) {
        throw new IllegalArgumentException("baseUrl cannot be null or blank");
      }
      if (path == null || path.isBlank()) {
        throw new IllegalArgumentException("path cannot be null or blank");
      }
      String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
      String relative = path.startsWith("/") ? path.substring(1) : path;
      this.url = base + "/" + relative;
      return this;
    }

    public Builder addHeader(String name, String... values) {
      if (headers == null) {
        headers = new HashMap<>();
      }
      headers.put(name, List.of(values));
      return this;
    }

    public Builder addHeaders(Map<String, String> headers) {
      if (headers == null || headers.isEmpty()) {
        return this;
      }
      if (this.headers == null) {
        this.headers = new HashMap<>();
      }
      headers.forEach((name, value) -> this.headers.put(name, List.of(value)));
      return this;
    }

    public Builder addQueryParam(String name, String value) {
      if (value == null) {
        return this;
      }
      if (queryParams == null) {
        queryParams = new LinkedHashMap<>();
      }
      queryParams.put(name, value);
      return this;
    }

    public Builder body(String body) {
      this.body = body;
      return this;
    }

    public HttpRequest build() {
      return new HttpRequest(this);
    }
  }
}
