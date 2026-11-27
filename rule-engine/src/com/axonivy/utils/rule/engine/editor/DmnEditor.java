package com.axonivy.utils.rule.engine.editor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.ref.SoftReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.axonivy.utils.rule.engine.RuleEngine;
import com.axonivy.utils.rule.engine.api.ValidationResult;
import com.axonivy.utils.rule.engine.client.exception.RuleEngineException;
import com.axonivy.utils.rule.engine.portal.RuleManagement;

import ch.ivyteam.ivy.environment.Ivy;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * The Apache KIE DMN editor, bundled by the Maven build of this project:
 * <ul>
 * <li><code>{application}/api/rule-engine/dmn-editor/dmn-editor.js</code>: the script, for the Portal</li>
 * <li><code>{application}/api/rule-engine/editor</code>: a page to edit .dmn files and let the DMN engine check
 * them</li>
 * </ul>
 */
@Path(DmnEditor.ROOT)
@Hidden // not part of the API of the application
public class DmnEditor {

  static final String ROOT = "rule-engine";

  private static final String PAGE = "editor";
  private static final String INDEX = "index.html";
  private static final String LIB = "lib/";
  private static final String JAVASCRIPT = "text/javascript; charset=utf-8";
  private static final String TEXT = "text/plain; charset=utf-8";
  private static final Map<String, String> PAGE_FILES = Map.of(
      INDEX, "text/html; charset=utf-8",
      "editor.js", JAVASCRIPT,
      "editor.css", "text/css; charset=utf-8");
  private static final Map<String, String> LIB_FILES = Map.of(
      RuleManagement.EDITOR_SCRIPT, JAVASCRIPT,
      "dmn-editor.js.LICENSE.txt", TEXT,
      "dmn-editor-envelope.js.LICENSE.txt", TEXT,
      "LICENSE.txt", TEXT,
      "VERSION.txt", TEXT);
  private static final String NOT_BUNDLED = "The DMN editor is not bundled. Build the project rule-engine with Maven: "
      + "it puts @kie-tools/dmn-editor-standalone on its class path.";
  private static final String NOT_CHECKED = "The DMN engine cannot check the model: %s";
  private static final String LOGIN = "Log in to the Axon Ivy engine to let the DMN engine check models.";

  private record File(byte[] content, String etag) {}

  /** The script is big: kept while the memory allows it. */
  private static final Map<String, SoftReference<File>> FILES = new ConcurrentHashMap<>();

  @GET
  @Path("dmn-editor/" + RuleManagement.EDITOR_SCRIPT)
  @PermitAll // the open source editor; it holds no data of the application
  public Response script(@HeaderParam("If-None-Match") String ifNoneMatch) {
    return serve(LIB + RuleManagement.EDITOR_SCRIPT, JAVASCRIPT, ifNoneMatch, true);
  }

  @GET
  @Path(PAGE)
  @PermitAll
  public Response page(@Context UriInfo uri) {
    return Response.seeOther(uri.getAbsolutePathBuilder().path(INDEX).build()).build();
  }

  @GET
  @Path(PAGE + "/{file}")
  @PermitAll
  public Response pageFile(@PathParam("file") String file, @HeaderParam("If-None-Match") String ifNoneMatch) {
    String type = PAGE_FILES.get(file);
    return type == null ? notFound() : serve(file, type, ifNoneMatch, false);
  }

  @GET
  @Path(PAGE + "/" + LIB + "{file}")
  @PermitAll
  public Response libFile(@PathParam("file") String file, @HeaderParam("If-None-Match") String ifNoneMatch) {
    String type = LIB_FILES.get(file);
    return type == null ? notFound() : serve(LIB + file, type, ifNoneMatch, true);
  }

  /** For logged in users only: the DMN engine has no authentication, this must not open it to everybody. */
  @POST
  @Path(PAGE + "/validate")
  @Consumes({MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.TEXT_PLAIN})
  @Produces(MediaType.APPLICATION_JSON)
  public Response validate(String dmn) {
    if (Ivy.session().isSessionUserUnknown()) {
      return Response.status(Response.Status.UNAUTHORIZED).type(MediaType.APPLICATION_JSON)
          .entity(Map.of("message", LOGIN)).build();
    }
    try {
      ValidationResult result = RuleEngine.client().validate(dmn);
      return Response.ok(result).build();
    } catch (RuleEngineException | IllegalStateException ex) {
      return Response.status(Response.Status.BAD_GATEWAY).type(MediaType.APPLICATION_JSON)
          .entity(Map.of("message", NOT_CHECKED.formatted(ex.getMessage()))).build();
    }
  }

  private static Response serve(String name, String type, String ifNoneMatch, boolean cacheLong) {
    Optional<File> file = load(name);
    if (file.isEmpty()) {
      return problem(Response.Status.NOT_FOUND, NOT_BUNDLED);
    }
    String etag = file.get().etag();
    if (etag.equals(ifNoneMatch)) {
      return Response.notModified().header("ETag", etag).build();
    }
    return Response.ok(file.get().content(), type)
        .header("ETag", etag)
        .header("Cache-Control", cacheLong ? "private, max-age=86400" : "no-cache")
        .build();
  }

  private static Optional<File> load(String name) {
    var cached = FILES.get(name);
    File file = cached == null ? null : cached.get();
    if (file != null) {
      return Optional.of(file);
    }
    try (InputStream in = DmnEditor.class.getResourceAsStream(name)) {
      if (in == null) {
        return Optional.empty();
      }
      byte[] content = in.readAllBytes();
      file = new File(content, "\"" + sha256(content) + "\"");
    } catch (IOException ex) {
      throw new UncheckedIOException("Cannot read " + name + " of the DMN editor", ex);
    }
    FILES.put(name, new SoftReference<>(file));
    return Optional.of(file);
  }

  private static Response notFound() {
    return Response.status(Response.Status.NOT_FOUND).build();
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
