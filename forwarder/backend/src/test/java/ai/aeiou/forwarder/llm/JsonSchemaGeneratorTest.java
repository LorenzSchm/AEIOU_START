package ai.aeiou.forwarder.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.aeiou.forwarder.router.Extraction;
import ai.aeiou.forwarder.router.MailType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonSchemaGeneratorTest {

    @Test
    void rootRecordIsStrictObjectWithAllFieldsRequired() {
        Map<String, Object> schema = JsonSchemaGenerator.forRecord(Extraction.class);

        assertThat(schema).containsEntry("type", "object").containsEntry("additionalProperties", false);
        assertThat(props(schema).keySet()).containsExactly(
                "type", "confidence", "reasoning", "language", "senderName", "rfq", "bid", "tracking");
        assertThat(JsonSchemaGeneratorTest.<List<Object>>cast(schema.get("required"))).containsExactlyElementsOf(props(schema).keySet());
    }

    @Test
    void primitivesAreNotNullableButReferencesAre() {
        Map<String, Object> p = props(JsonSchemaGenerator.forRecord(Extraction.class));

        assertThat(p.get("confidence")).isEqualTo(Map.of("type", "number"));
        assertThat(p.get("reasoning")).isEqualTo(Map.of("type", List.of("string", "null")));
    }

    @Test
    void enumsListTheirConstantsAndAcceptNull() {
        Map<String, Object> type = cast(props(JsonSchemaGenerator.forRecord(Extraction.class)).get("type"));
        List<Map<String, Object>> anyOf = cast(type.get("anyOf"));

        assertThat(anyOf.get(0)).containsEntry("type", "string");
        assertThat(JsonSchemaGeneratorTest.<List<Object>>cast(anyOf.get(0).get("enum")))
                .containsExactlyElementsOf(Arrays.stream(MailType.values()).map(Enum::name).toList());
        assertThat(anyOf.get(1)).isEqualTo(Map.of("type", "null"));
    }

    @Test
    void nestedRecordsAreStrictNullableObjects() {
        Map<String, Object> rfq = cast(props(JsonSchemaGenerator.forRecord(Extraction.class)).get("rfq"));
        List<Map<String, Object>> anyOf = cast(rfq.get("anyOf"));
        Map<String, Object> object = anyOf.get(0);

        assertThat(object).containsEntry("type", "object").containsEntry("additionalProperties", false);
        assertThat(props(object).get("weightKg")).isEqualTo(Map.of("type", List.of("number", "null")));
        assertThat(props(object).get("pieces")).isEqualTo(Map.of("type", List.of("integer", "null")));
        assertThat(props(object).get("doorPickup")).isEqualTo(Map.of("type", List.of("boolean", "null")));
        assertThat((List<?>) object.get("required")).hasSize(props(object).size());
        assertThat(anyOf.get(1)).isEqualTo(Map.of("type", "null"));
    }

    record Leg(String from, String to) {}

    record Route(List<Leg> legs, List<String> tags, int hops) {}

    @Test
    void listsUseTheirElementSchema() {
        Map<String, Object> p = props(JsonSchemaGenerator.forRecord(Route.class));

        Map<String, Object> legs = cast(p.get("legs"));
        assertThat(legs.get("type")).isEqualTo(List.of("array", "null"));
        assertThat(JsonSchemaGeneratorTest.<Map<String, Object>>cast(legs.get("items"))).containsEntry("type", "object");
        assertThat(p.get("tags")).isEqualTo(Map.of("type", List.of("array", "null"), "items", Map.of("type", "string")));
        assertThat(p.get("hops")).isEqualTo(Map.of("type", "integer"));
    }

    record Node(String name, Node child) {}

    record WithMap(Map<String, String> values) {}

    @Test
    void rejectsWhatStrictModeCannotExpress() {
        assertThatThrownBy(() -> JsonSchemaGenerator.forRecord(Node.class)).hasMessageContaining("Recursive");
        assertThatThrownBy(() -> JsonSchemaGenerator.forRecord(WithMap.class)).hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> JsonSchemaGenerator.forRecord(String.class)).hasMessageContaining("not a record");
    }

    private static Map<String, Object> props(Map<String, Object> schema) {
        return cast(schema.get("properties"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) o;
    }
}
