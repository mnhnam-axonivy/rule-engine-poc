package com.axonivy.utils.rule.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.axonivy.utils.rule.engine.dmn.generator.BeanProperties;
import com.axonivy.utils.rule.engine.dmn.generator.DmnType;
import com.axonivy.utils.rule.engine.dmn.generator.JavaTypeMapper;
import com.axonivy.utils.rule.engine.mapping.FieldLocator;
import com.axonivy.utils.rule.engine.mapping.ValueConverter;
import com.axonivy.utils.rule.engine.program.internal.FieldSelection;
import com.axonivy.utils.rule.engine.test.Customer;
import com.axonivy.utils.rule.engine.test.Recommendation;
import com.axonivy.utils.rule.engine.test.Segment;
import com.axonivy.utils.rule.engine.test.TestData;

import ch.ivyteam.ivy.environment.IvyTest;
import ch.ivyteam.ivy.scripting.objects.Date;
import ch.ivyteam.ivy.scripting.objects.DateTime;
import ch.ivyteam.ivy.scripting.objects.Duration;
import ch.ivyteam.ivy.scripting.objects.Time;

@IvyTest
class TestMapping {

  @Test
  void ivyDataClass_isMappedToDmnTypes() {
    var type = (DmnType.Structure) new JavaTypeMapper().toDmnType(TestData.class);
    var fields = type.fields().stream().collect(java.util.stream.Collectors.toMap(f -> f.name(), f -> f.type()));

    assertThat(fields.get("amount")).as("Ivy Number").isEqualTo(DmnType.NUMBER);
    assertThat(fields.get("quantity")).as("Integer").isEqualTo(DmnType.NUMBER);
    assertThat(fields.get("nextStep")).as("String").isEqualTo(DmnType.STRING);
    assertThat(fields.get("approved")).as("Boolean").isEqualTo(DmnType.BOOLEAN);
    assertThat(fields.get("orderDate")).as("Ivy Date").isEqualTo(DmnType.DATE);
    assertThat(fields.get("orderedAt")).as("Ivy DateTime").isEqualTo(DmnType.DATE_TIME);
    assertThat(fields.get("openingTime")).as("Ivy Time").isEqualTo(DmnType.TIME);
    assertThat(fields.get("deliveryTime")).as("Ivy Duration").isEqualTo(DmnType.DAYS_TIME_DURATION);
    assertThat(fields.get("labels")).as("Ivy List<String>").isEqualTo(new DmnType.Collection(DmnType.STRING));
    assertThat(fields.get("upgrade")).as("enum").isInstanceOf(DmnType.Enumeration.class);
    assertThat(((DmnType.Enumeration) fields.get("upgrade")).values()).containsExactly("GOLD", "SILVER", "STANDARD");

    var customer = (DmnType.Structure) fields.get("customer");
    assertThat(customer.name()).as("nested data class").isEqualTo("tCustomer");
    assertThat(customer.fields()).extracting(f -> f.name())
        .containsExactly("name", "segment", "age", "vip", "birthday", "lastContact", "tags", "address");
    var positions = (DmnType.Collection) fields.get("positions");
    assertThat(positions.element()).as("list of data classes").isInstanceOf(DmnType.Structure.class);
  }

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
  void fieldsAreFoundByName() {
    assertThat(FieldLocator.findByName(TestData.class, "in", "amount"))
        .get().extracting(FieldLocator.Field::expression).isEqualTo("in.amount");
    assertThat(FieldLocator.findByName(TestData.class, "in", "segment"))
        .get().extracting(FieldLocator.Field::expression).isEqualTo("in.customer.segment");
    assertThat(FieldLocator.findByName(TestData.class, "in", "city"))
        .as("a field of a nested data class, two levels down")
        .get().extracting(FieldLocator.Field::expression).isEqualTo("in.customer.address.city");
    assertThat(FieldLocator.findByName(TestData.class, "in", "Next Step"))
        .as("names of hand-written DMN models may differ in case and blanks")
        .get().extracting(FieldLocator.Field::expression).isEqualTo("in.nextStep");
    assertThat(FieldLocator.findByName(TestData.class, "in", "product"))
        .get().extracting(FieldLocator.Field::expression).isEqualTo("in.recommendation.product");
    assertThat(FieldLocator.findByName(TestData.class, "in", "creditScore")).isEmpty();

    assertThat(FieldLocator.typeOf(TestData.class, "in.customer.age")).contains(Integer.class);
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

  @Test
  void selectedFields_getTheirDmnNames() {
    var selection = FieldSelection.parse("""
        in.member.purchaseAmount, in.member.type
        in.order.type; in.amount
        in.member.purchaseAmount
        """);
    assertThat(selection.expressionsByName()).containsExactly(
        Map.entry("purchaseAmount", "in.member.purchaseAmount"),
        Map.entry("memberType", "in.member.type"),
        Map.entry("orderType", "in.order.type"),
        Map.entry("amount", "in.amount"));
    assertThat(FieldSelection.parse(null).isEmpty()).isTrue();
    assertThat(FieldSelection.parse("  ").isEmpty()).isTrue();
  }
}
