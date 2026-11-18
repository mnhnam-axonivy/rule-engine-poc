package com.axonivy.utils.rule.engine.runtime.api;

import java.util.List;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import com.axonivy.utils.rule.engine.api.ErrorResponse;
import com.axonivy.utils.rule.engine.runtime.dmn.InvalidModelException;
import com.axonivy.utils.rule.engine.runtime.service.RuleEngineException;
import com.axonivy.utils.rule.engine.runtime.service.RuleEngineException.Code;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Every failure answers with the same JSON shape: code, message and the findings of the DMN engine.
 */
public class ErrorMappers {

  private static final Logger LOG = Logger.getLogger(ErrorMappers.class);

  @ServerExceptionMapper
  public Response ruleEngine(RuleEngineException ex) {
    return error(ex.code().status(), new ErrorResponse(ex.code().name(), ex.getMessage(), ex.details()));
  }

  @ServerExceptionMapper
  public Response invalidModel(InvalidModelException ex) {
    return error(Code.INVALID_MODEL.status(),
        new ErrorResponse(Code.INVALID_MODEL.name(), ex.getMessage(), ex.messages()));
  }

  @ServerExceptionMapper
  public Response web(WebApplicationException ex) {
    int status = ex.getResponse().getStatus();
    var code = status >= 500 ? Code.INTERNAL_ERROR : Code.INVALID_REQUEST;
    if (status >= 500) {
      LOG.error("Request failed", ex);
    }
    return error(status, new ErrorResponse(code.name(), ex.getMessage(), List.of()));
  }

  @ServerExceptionMapper
  public Response unexpected(Throwable ex) {
    LOG.error("Request failed unexpectedly", ex);
    return error(Code.INTERNAL_ERROR.status(), new ErrorResponse(Code.INTERNAL_ERROR.name(),
        "Unexpected error: " + ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage()),
        List.of()));
  }

  private static Response error(int status, ErrorResponse body) {
    return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body).build();
  }
}
