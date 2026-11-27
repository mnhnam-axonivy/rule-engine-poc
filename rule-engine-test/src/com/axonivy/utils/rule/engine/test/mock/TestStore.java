package com.axonivy.utils.rule.engine.test.mock;

import java.util.ArrayList;
import java.util.List;

import com.axonivy.utils.rule.engine.api.ModelVersion;
import com.axonivy.utils.rule.engine.api.RegisterVersionRequest;
import com.axonivy.utils.rule.engine.api.VersionSource;
import com.axonivy.utils.rule.engine.deploy.ModelDeployer;
import com.axonivy.utils.rule.engine.deploy.RuntimeLoader;
import com.axonivy.utils.rule.engine.version.VersionStore;

public final class TestStore {

  private TestStore() {}

  public static void clear() {
    var store = VersionStore.current();
    store.models().forEach(store::delete);
    ModelDeployer.reset();
    RuntimeLoader.reset();
  }

  public static ModelVersion store(String model, String decision, String... inputs) {
    return store(model, dmn(model, decision, inputs), VersionSource.API, null);
  }

  public static ModelVersion store(String model, String dmn, VersionSource source, Boolean activate) {
    return VersionStore.current().register(model, RegisterVersionRequest.builder()
        .content(dmn)
        .author("test")
        .source(source)
        .activate(activate)
        .build());
  }

  public static String dmn(String model, String decision, String... inputs) {
    var inputData = new ArrayList<String>();
    var requirements = new ArrayList<String>();
    for (String input : List.of(inputs)) {
      inputData.add("""
            <inputData id="_input_%1$s" name="%1$s">
              <variable id="_input_var_%1$s" name="%1$s" typeRef="Any"/>
            </inputData>
          """.formatted(input));
      requirements.add("""
              <informationRequirement id="_requirement_%1$s">
                <requiredInput href="#_input_%1$s"/>
              </informationRequirement>
          """.formatted(input));
    }
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="_%1$s" name="%1$s"
            namespace="https://axonivy.com/dmn/%1$s">
        %3$s  <decision id="_decision_%2$s" name="%2$s">
            <variable id="_decision_var_%2$s" name="%2$s" typeRef="Any"/>
        %4$s  </decision>
        </definitions>
        """.formatted(model, decision, String.join("", inputData), String.join("", requirements));
  }
}
