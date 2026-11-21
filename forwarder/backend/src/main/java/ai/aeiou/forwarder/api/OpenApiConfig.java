package ai.aeiou.forwarder.api;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shapes {@code /v3/api-docs} so orval generates a clean TS client: enums become named schemas, and every
 * property that is not marked {@code @Schema(nullable = true)} is required (non-optional in TS).
 */
@Configuration
public class OpenApiConfig {

    static {
        // Named enum schemas (SessionStatus, BookingStatus, ...) instead of repeated inline string unions.
        ModelResolver.enumsAsRef = true;
    }

    @Bean
    OpenAPI forwarderOpenApi() {
        return new OpenAPI().info(new Info()
                .title("AEIOU forwarder control panel API")
                .version("1")
                .description("See forwarder/API_CONTRACT.md. Live updates: GET /api/stream (SSE)."));
    }

    @Bean
    OpenApiCustomizer requireNonNullableProperties() {
        return api -> {
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
            api.getComponents().getSchemas().values().forEach(OpenApiConfig::markRequired);
        };
    }

    @SuppressWarnings("rawtypes")
    private static void markRequired(Schema<?> schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null || properties.isEmpty()) return;
        List<String> required = properties.entrySet().stream()
                .filter(e -> !isNullable(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        schema.setRequired(required.isEmpty() ? null : required);
        properties.replaceAll((name, property) -> isNullable(property) ? asNullable31(property) : property);
    }

    /**
     * OpenAPI 3.1 has no {@code nullable} keyword (springdoc drops it when writing 3.1), so nullability is
     * expressed as {@code type: [T, "null"]}, or {@code anyOf: [{$ref}, {type: "null"}]} for references.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema asNullable31(Schema property) {
        Schema nullType = new Schema<>().types(new LinkedHashSet<>(List.of("null")));
        if (property.get$ref() != null) {
            Schema ref = new Schema<>().$ref(property.get$ref());
            return new Schema<>().anyOf(List.of(ref, nullType)).description(property.getDescription());
        }
        Set<String> types = new LinkedHashSet<>();
        if (property.getTypes() != null) types.addAll(property.getTypes());
        else if (property.getType() != null) types.add(property.getType());
        if (types.isEmpty()) return property;  // untyped (any JSON value) already admits null
        types.add("null");
        property.setTypes(types);
        property.setNullable(null);
        return property;
    }

    private static boolean isNullable(Schema<?> property) {
        return Boolean.TRUE.equals(property.getNullable())
                || (property.getTypes() != null && property.getTypes().contains("null"));
    }
}
