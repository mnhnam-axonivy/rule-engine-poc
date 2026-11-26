package com.axonivy.portal.rule;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;

import com.axonivy.portal.components.service.IvyAdapterService;

import ch.ivyteam.ivy.environment.Ivy;
import ch.ivyteam.ivy.process.call.SubProcessCallStartEvent;
import ch.ivyteam.ivy.process.call.SubProcessSearchFilter;
import ch.ivyteam.ivy.process.call.SubProcessSearchFilter.SearchScope;
import ch.ivyteam.ivy.security.exec.Sudo;

/**
 * The Portal has no dependency on the rule engine: the callable sub process <code>Portal/RuleManagement</code> is found
 * by its signatures in the security context, like Smart Workflow. Only JDK types cross.
 */
public final class RuleEngineBridge {

  static final String INFO = "ruleManagementInfo()";
  static final String MODELS = "ruleManagementModels()";
  static final String VERSIONS = "ruleManagementVersions(String)";
  static final String CONTENT = "ruleManagementContent(String,Integer)";
  static final String VALIDATE = "ruleManagementValidate(String)";
  static final String SAVE = "ruleManagementSave(String,String,String,String,Boolean)";
  static final String ACTIVATE = "ruleManagementActivate(String,Integer,String)";
  static final String ROLLBACK = "ruleManagementRollback(String,String)";

  private RuleEngineBridge() {}

  public static boolean isAvailable() {
    try {
      return Sudo.get(() -> CollectionUtils.isNotEmpty(SubProcessCallStartEvent.find(SubProcessSearchFilter.create()
          .setSearchScope(SearchScope.SECURITY_CONTEXT)
          .setSignature(MODELS)
          .toFilter())));
    } catch (Exception ex) {
      Ivy.log().warn("Rule management: cannot look up the rule engine", ex);
      return false;
    }
  }

  public static RuleEngineAnswer info() {
    return call(INFO, null);
  }

  public static RuleEngineAnswer models() {
    return call(MODELS, null);
  }

  public static RuleEngineAnswer versions(String model) {
    return call(VERSIONS, params("model", model));
  }

  public static RuleEngineAnswer content(String model, Integer version) {
    return call(CONTENT, params("model", model, "version", version));
  }

  public static RuleEngineAnswer validate(String dmn) {
    return call(VALIDATE, params("dmn", dmn));
  }

  public static RuleEngineAnswer save(String model, String dmn, String author, String description, boolean activate) {
    return call(SAVE, params("model", model, "dmn", dmn, "author", author, "description", description,
        "activate", activate));
  }

  public static RuleEngineAnswer activate(String model, int version, String author) {
    return call(ACTIVATE, params("model", model, "version", version, "author", author));
  }

  public static RuleEngineAnswer rollback(String model, String author) {
    return call(ROLLBACK, params("model", model, "author", author));
  }

  private static RuleEngineAnswer call(String signature, Map<String, Object> params) {
    try {
      Map<String, Object> response = IvyAdapterService.startSubProcessInSecurityContext(signature, params);
      if (response == null) {
        return RuleEngineAnswer.failure(RuleEngineError.NOT_AVAILABLE,
            "There is no rule engine project in this security context.");
      }
      return new RuleEngineAnswer(response.get("result"), RuleEngineError.of(response.get("error")));
    } catch (Exception ex) {
      Ivy.log().error("Rule management: the call " + signature + " failed", ex);
      return RuleEngineAnswer.failure(RuleEngineError.FAILED, ex.getMessage());
    }
  }

  private static Map<String, Object> params(Object... namesAndValues) {
    var params = new LinkedHashMap<String, Object>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      params.put((String) namesAndValues[i], namesAndValues[i + 1]);
    }
    return params;
  }
}
