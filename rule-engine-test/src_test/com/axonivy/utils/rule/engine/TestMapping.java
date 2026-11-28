package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.mapping.BeanProperties;
import com.axonivy.utils.rule.engine.mapping.FieldLocator;
import com.axonivy.utils.rule.engine.mapping.ValueConverter;
import com.axonivy.utils.rule.engine.test.Customer;
import com.axonivy.utils.rule.engine.test.Recommendation;
import com.axonivy.utils.rule.engine.test.Segment;
import com.axonivy.utils.rule.engine.test.TestData;

import ch.ivyteam.ivy.environment.IvyTest;
import ch.ivyteam.ivy.scripting.objects.Date;
import ch.ivyteam.ivy.scripting.objects.DateTime;
import ch.ivyteam.ivy.scripting.objects.Duration;
import ch.ivyteam.ivy.scripting.objects.Time;

/**
 * Mapping between Ivy data classes and DMN: values, and the fields a mapping names.
 */
@IvyTest
class TestMapping {

  @Test
  void dataClassProperties_areFoundInDeclarationOrder() {
    assertThat(BeanProperties.of(Customer.class)).extracting(BeanProperties.Property::name)
        .containsExactly("name", "segment", "age", "vip", "birthday", "lastContact", "tags", "address");
    assertThat(BeanProperties.of(Customer.class)).allMatch(BeanProperties.Property::isWritable);
  }

  @Test
  void ivyValues_becomeJsonValues() {
    assertThat(ValueConverter.toJson(new Date(2026, 1, 5))).isEqualTo("2026-01-05");
    assertThat(ValueConverter.toJson(new DateTime(2026, 1, 5, 9, 0, 0))).isEqualTo("2026-01-05T09:00:00");
    assertThat(ValueConverter.toJson(new DateTime(2026, 1, 5, 9, 0, 7))).isEqualTo("2026-01-05T09:00:07");
    assertThat(ValueConverter.toJson(new Time(7, 5, 0))).isEqualTo("07:05:00");
    assertThat(ValueConverter.toJson(new Duration(90 * 60L))).isEqualTo("PT1H30M");
    assertThat(ValueConverter.toJson(Segment.SILVER)).isEqualTo("SILVER");
    assertThat(ValueConverter.toJson(null)).isNull();
    assertThat(ValueConverter.toJson(new BigDecimal("1.50"))).isEqualTo(new BigDecimal("1.50"));

    var customer = new Customer();
    customer.setName("Jane");
    customer.setSegment(Segment.GOLD);
    @SuppressWarnings("unchecked")
    var json = (Map<String, Object>) ValueConverter.toJson(customer);
    assertThat(json).containsEntry("name", "Jane").containsEntry("segment", "GOLD")
        .containsEntry("age", null).containsEntry("address", null)
        .containsKeys("vip", "birthday", "lastContact", "tags");
  }

  @Test
  void jsonValues_becomeIvyValues() throws Exception {
    assertThat(ValueConverter.fromJson("2026-12-15", Date.class)).isEqualTo(new Date(2026, 12, 15));
    assertThat(ValueConverter.fromJson("2026-12-15T08:30:00", DateTime.class))
        .isEqualTo(new DateTime(2026, 12, 15, 8, 30, 0));
    assertThat(ValueConverter.fromJson("08:30:00", Time.class)).isEqualTo(new Time(8, 30, 0));
    assertThat(ValueConverter.fromJson("PT2H", Duration.class)).isEqualTo(new Duration(7200L));
    assertThat(ValueConverter.fromJson("GOLD", Segment.class)).isEqualTo(Segment.GOLD);
    assertThat(ValueConverter.fromJson(new BigDecimal("3"), Integer.class)).isEqualTo(3);
    assertThat(ValueConverter.fromJson(new BigDecimal("3.5"), Double.class)).isEqualTo(3.5);
    assertThat(ValueConverter.fromJson(new BigDecimal("3.0"), Number.class)).as("Ivy Number").isEqualTo(3);
    assertThat(ValueConverter.fromJson(new BigDecimal("3.25"), Number.class)).isEqualTo(3.25);
    assertThat(ValueConverter.fromJson(7, String.class)).isEqualTo("7");
    assertThat(ValueConverter.fromJson(null, Date.class)).isNull();

    var labels = TestData.class.getMethod("getLabels").getGenericReturnType();
    assertThat((List<?>) ValueConverter.fromJson(List.of("a", "b"), labels))
        .isInstanceOf(ch.ivyteam.ivy.scripting.objects.List.class)
        .hasSize(2);

    var recommendation = (Recommendation) ValueConverter.fromJson(
        Map.of("product", "Sofa", "score", new BigDecimal("0.75"), "validUntil", "2027-01-31", "unknown", 1),
        Recommendation.class);
    assertThat(recommendation.getProduct()).isEqualTo("Sofa");
    assertThat(recommendation.getScore()).isEqualTo(0.75);
    assertThat(recommendation.getValidUntil()).isEqualTo(new Date(2027, 1, 31));
  }

  @Test
  void valueThatDoesNotFit_isRejectedWithReadableMessage() {
    assertThatThrownBy(() -> ValueConverter.fromJson("a lot", Integer.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("a lot").hasMessageContaining("Integer");
    assertThatThrownBy(() -> ValueConverter.fromJson("PLATINUM", Segment.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PLATINUM");
    assertThatThrownBy(() -> ValueConverter.fromJson("tomorrow", Date.class))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ValueConverter.fromJson(new BigDecimal("3.5"), Integer.class))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fieldsAreTypedByTheirPath() {
    assertThat(FieldLocator.typeOf(TestData.class, "in.customer.age")).contains(Integer.class);
    assertThat(FieldLocator.typeOf(TestData.class, "in.customer.address.city")).as("two levels down")
        .contains(String.class);
    assertThat(FieldLocator.typeOf(TestData.class, "in.customer.unknown")).isEmpty();
  }

  @Test
  void fieldsAreReadWithoutCreatingNestedObjects() {
    var data = new TestData();
    assertThat(FieldLocator.read(data, "in.customer.address.city")).contains(java.util.Optional.empty());
    assertThat(data.getCustomer()).as("reading must not change the process data").isNull();
    data.setAmount(5);
    assertThat(FieldLocator.read(data, "in.amount")).contains(java.util.Optional.of(5));
    assertThat(FieldLocator.read(data, "in.unknown")).as("not a field").isEmpty();
  }
}
