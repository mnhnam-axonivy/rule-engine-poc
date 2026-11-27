package com.axonivy.utils.rule.engine.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.runtime.TestModels;
import com.axonivy.utils.rule.engine.runtime.dmn.DmnCompiler;
import com.axonivy.utils.rule.engine.runtime.service.RuleEngineException.Code;

class ModelRegistryTest {

  private final ModelRegistry registry = registry(2);

  @Test
  void sameContent_isNotCompiledAgain() {
    var first = registry.load("discount", 1, TestModels.discount(15));
    var again = registry.load("discount", 1, TestModels.discount(15));
    assertThat(again.model()).isSameAs(first.model());

    var changed = registry.load("discount", 1, TestModels.discount(20));
    assertThat(changed.model()).isNotSameAs(first.model());
    assertThat(registry.get("discount", 1)).isSameAs(changed.model());
  }

  @Test
  void leastRecentlyUsedVersion_makesRoom() {
    registry.load("discount", 1, TestModels.discount(10));
    registry.load("discount", 2, TestModels.discount(20));
    registry.get("discount", 1);

    registry.load("discount", 3, TestModels.discount(30));

    assertThat(registry.size()).isEqualTo(2);
    assertThat(registry.get("discount", 1).version()).isEqualTo(1);
    assertThat(registry.get("discount", 3).version()).isEqualTo(3);
    assertThatThrownBy(() -> registry.get("discount", 2))
        .isInstanceOfSatisfying(RuleEngineException.class, ex -> assertThat(ex.code()).isEqualTo(Code.VERSION_NOT_FOUND));
    assertThatThrownBy(() -> registry.get("other", 1))
        .isInstanceOfSatisfying(RuleEngineException.class, ex -> assertThat(ex.code()).isEqualTo(Code.MODEL_NOT_FOUND));
  }

  private static ModelRegistry registry(int cacheSize) {
    return new ModelRegistry(new DmnCompiler(), cacheSize);
  }
}
