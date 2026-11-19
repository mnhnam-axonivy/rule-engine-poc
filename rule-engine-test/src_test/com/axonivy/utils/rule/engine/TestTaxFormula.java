package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.demo.PricedLine;
import com.axonivy.utils.rule.engine.demo.PricingResult;
import com.axonivy.utils.rule.engine.demo.TaxFormula;
import com.axonivy.utils.rule.engine.dmn.ProjectDmnFiles;

import ch.ivyteam.ivy.application.project.Project;
import ch.ivyteam.ivy.environment.IvyTest;

/**
 * The tax formula of the demo: read from the model, shown with the values of a line, replaced for a try.
 */
@IvyTest
class TestTaxFormula {

  @Test
  void formulaOfTheProjectFile() {
    assertThat(TaxFormula.of(null)).isEqualTo(TaxFormula.DEFAULT).isEqualTo("round half up(net * rate / 100, 2)");
  }

  @Test
  void anotherFormula_replacesOnlyTheFormula() throws Exception {
    String dmn = Files.readString(ProjectDmnFiles.find(Project.current(), TaxFormula.MODEL).orElseThrow().path());

    String changed = TaxFormula.withFormula(dmn, "if net < 0 then 0 else decimal(net * rate / 100, 2)");

    assertThat(TaxFormula.formula(changed)).as("a < is escaped and read back")
        .isEqualTo("if net < 0 then 0 else decimal(net * rate / 100, 2)");
    assertThat(changed).contains("name=\"lineDiscountTable\"").contains("name=\"vatRateTable\"")
        .contains("for line in lines return");
    assertThat(TaxFormula.formula("<definitions xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\"/>"))
        .as("a model without taxFormula").isNull();
    assertThatThrownBy(() -> TaxFormula.withFormula("<definitions xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\"/>", "1"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void formulaWithTheValuesOfALine() {
    assertThat(TaxFormula.withValues(TaxFormula.DEFAULT, new BigDecimal("11280.00"), new BigDecimal("8.1")))
        .isEqualTo("round half up(11280.00 * 8.1 / 100, 2)");
    assertThat(TaxFormula.withValues("netto + net", BigDecimal.ONE, BigDecimal.TEN)).as("whole names only")
        .isEqualTo("netto + 1");
  }

  @Test
  void stepsOfThePricedLines() {
    var line = new PricedLine();
    line.setProduct("Laptop");
    line.setNet(new BigDecimal("11280.00"));
    line.setVatRate(new BigDecimal("8.1"));
    line.setVat(new BigDecimal("913.68"));
    var pricing = new PricingResult();
    pricing.setPricedLines(ch.ivyteam.ivy.scripting.objects.List.create(PricedLine.class, List.of(line)));

    var steps = TaxFormula.steps(pricing, TaxFormula.DEFAULT);

    assertThat(steps).hasSize(1);
    assertThat(steps.get(0).getExact()).as("net * rate / 100 before rounding").isEqualByComparingTo("913.68");
    assertThat(steps.get(0).getFormula()).isEqualTo("round half up(11280.00 * 8.1 / 100, 2)");
    assertThat(TaxFormula.steps(null, TaxFormula.DEFAULT)).isEmpty();
  }

  @Test
  void formulasThatAreNotTried() {
    assertThat(TaxFormula.refused(" ")).startsWith("Enter a FEEL expression");
    assertThat(TaxFormula.refused("function(x) external {java: {class: \"java.lang.System\", method signature: \"exit(int)\"}}(0)"))
        .contains("Function definitions are not allowed");
    assertThat(TaxFormula.refused("net".repeat(200))).contains("longer than");
    assertThat(TaxFormula.refused("decimal(net * rate / 100, 2)")).isNull();
  }
}
