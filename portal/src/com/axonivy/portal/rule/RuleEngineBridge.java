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
 * The connection of the Portal to the Axon Ivy Rules Engine, without a dependency on it.
 *
 * <p>The rule engine project offers the callable sub process <code>Portal/RuleManagement</code>. Like Smart Workflow,
 * it is found by its signatures in the security context: without it the Portal shows no Rule Management and works as
 * before. Only JDK types cross, so the Portal needs no class of the rule engine.</p>
 *
 * <p>Every call answers with a result and an error, see {@link RuleEngineAnswer}.</p>
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
  static final String RETIRE = "ruleManagementRetire(String,Integer)";

  private RuleEngineBridge() {}

  /** @return true if a rule engine project is in the security context */
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

  /** runtimeUrl, reachable, editorScript (link of the DMN editor script) and modelPrefix */
  public static RuleEngineAnswer info() {
    return call(INFO, null);
  }

  /** The models of the decision runtime and the .dmn files of the projects, see {@link RuleModel} */
  public static RuleEngineAnswer models() {
    return call(MODELS, null);
  }

  /** The versions of a model, newest first, see {@link RuleVersion} */
  public static RuleEngineAnswer versions(String model) {
    return call(VERSIONS, params("model", model));
  }

  /** model, version and dmn of a version; version null opens the active one */
  public static RuleEngineAnswer content(String model, Integer version) {
    return call(CONTENT, params("model", model, "version", version));
  }

  /** valid and messages: the findings of the DMN engine */
  public static RuleEngineAnswer validate(String dmn) {
    return call(VALIDATE, params("dmn", dmn));
  }

  /** Stores a new version with the source Portal: live at once, or as draft */
  public static RuleEngineAnswer save(String model, String dmn, String author, String description, boolean activate) {
    return call(SAVE, params("model", model, "dmn", dmn, "author", author, "description", description,
        "activate", activate));
  }

  public static RuleEngineAnswer activate(String model, int version, String author) {
    return call(ACTIVATE, params("model", model, "version", version, "author", author));
  }

  /** Activates the version that was active before the current one */
  public static RuleEngineAnswer rollback(String model, String author) {
    return call(ROLLBACK, params("model", model, "author", author));
  }

  public static RuleEngineAnswer retire(String model, int version) {
    return call(RETIRE, params("model", model, "version", version));
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
