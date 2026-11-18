package com.axonivy.utils.rule.engine.portal;

import java.lang.ref.SoftReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import com.axonivy.utils.rule.engine.RuleEngineConfig;
import com.axonivy.utils.rule.engine.client.http.ivy.IvyHttpClient;
import com.axonivy.utils.rule.engine.client.http.ivy.IvyHttpClientBuilder;

import ch.ivyteam.ivy.environment.Ivy;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Serves the DMN editor of Apache KIE to the browser, from this engine: GET
 * <code>{application}/api/rule-engine/dmn-editor/dmn-editor.js</code>.
 *
 * <p>The script is the one the decision runtime serves for its own page <code>/editor/</code>, so the editor
 * always fits the DMN engine that checks the models. This engine fetches it once through the REST client
 * <code>ruleEngine</code> and keeps it in memory. The browser never talks to the runtime: it may well be
 * unreachable from there, and its API key stays on the server.</p>
 */
@Path(RuleManagement.REST_PATH)
@PermitAll // the open source editor; it holds no data of the application
@Hidden // not part of the API of the application
public class DmnEditorScript {

  /** Where the runtime serves the standalone build of the KIE DMN editor. */
  static final String RUNTIME_PATH = "editor/lib/" + RuleManagement.EDITOR_SCRIPT;
  private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
  private static final String JAVASCRIPT = "text/javascript; charset=utf-8";

  private record Script(String url, byte[] content, String etag) {}

  private static volatile SoftReference<Script> cached = new SoftReference<>(null);

  @GET
  @Path(RuleManagement.EDITOR_SCRIPT)
  public Response script(@HeaderParam("If-None-Match") String ifNoneMatch) {
    String runtimeUrl = RuleEngineConfig.fromVariables().url();
    if (runtimeUrl.isBlank()) {
      return problem(Response.Status.SERVICE_UNAVAILABLE,
          "The decision runtime is not configured. Set the variable " + RuleEngineConfig.Var.URL + ".");
    }
    String url = scriptUrl(runtimeUrl);
    Script script;
    try {
      script = load(url);
    } catch (RuntimeException ex) {
      Ivy.log().warn("Rule management: cannot load the DMN editor from " + url, ex);
      return problem(Response.Status.BAD_GATEWAY, "The decision runtime at " + runtimeUrl
          + " does not answer, so the DMN editor cannot be loaded.");
    }
    if (script == null) {
      return problem(Response.Status.NOT_FOUND, "The decision runtime at " + runtimeUrl
          + " has no DMN editor (" + RUNTIME_PATH + "). Run an image of the runtime that serves the page /editor/.");
    }
    if (script.etag().equals(ifNoneMatch)) {
      return Response.notModified().header("ETag", script.etag()).build();
    }
    return Response.ok(script.content(), JAVASCRIPT)
        .header("ETag", script.etag())
        .header("Cache-Control", "private, max-age=86400")
        .build();
  }

  /** Forgets the script, so that it is fetched from the runtime again, e.g. after the runtime was updated. */
  public static void forget() {
    cached = new SoftReference<>(null);
  }

  static String scriptUrl(String runtimeUrl) {
    String base = runtimeUrl.strip();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + "/" + RUNTIME_PATH;
  }

  private static Script load(String url) {
    Script script = cached.get();
    if (script != null && script.url().equals(url)) {
      return script;
    }
    var http = (IvyHttpClient) new IvyHttpClientBuilder()
        .connectTimeout(RuleEngineConfig.fromVariables().connectTimeout())
        .readTimeout(DOWNLOAD_TIMEOUT)
        .build();
    byte[] content = http.download(url);
    if (content == null) {
      return null;
    }
    script = new Script(url, content, "\"" + sha256(content) + "\"");
    cached = new SoftReference<>(script);
    return script;
  }

  private static Response problem(Response.Status status, String message) {
    // shown in the console of the browser, and by the Portal when the script does not load
    return Response.status(status).type(MediaType.TEXT_PLAIN_TYPE.withCharset("utf-8")).entity(message)
        .header("Cache-Control", "no-store")
        .build();
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
