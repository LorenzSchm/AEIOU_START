package ai.aeiou.forwarder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;

/**
 * Checks the generated OpenAPI document and, on demand, exports it for the frontend's orval client.
 * <p>
 * Export (no Gradle plugin, no running server; Docker is needed for the Testcontainers Postgres):
 * <pre>
 *   cd forwarder/backend
 *   ./gradlew test --tests '*OpenApiExportTest' -DexportOpenApi=true
 *   cd ../frontend &amp;&amp; npm run gen:api
 * </pre>
 * The {@code exportOpenApi} property is forwarded to the test JVM in build.gradle.
 */
class OpenApiExportTest extends ApiTestBase {

    private static final Path TARGET = Path.of("..", "frontend", "openapi.json");

    private static final List<String> OPERATION_IDS = List.of(
            "listSessions", "getSession", "listSessionEvents", "listSessionMails", "getSessionDraft", "sessionAction",
            "listEvents", "getEventPayload", "listMails", "getMail", "downloadAttachment", "listLlmCalls", "getLlmCall",
            "listBookings", "listActiveShipments", "getStats", "listBoardResults", "listTariffs", "getTariff",
            "activateTariff", "rejectTariff", "listCarriers", "listSettings", "updateSetting", "stream",
            "getAdminStatus", "registerOpenAiKey", "setLive");

    @Test
    void specHasContractOperationIdsEnumsAndRequiredFields() throws Exception {
        JsonNode spec = spec();
        List<String> ids = spec.get("paths").values().stream()
                .flatMap(path -> path.values().stream())
                .map(op -> op.path("operationId").asString())
                .toList();
        assertThat(ids).containsAll(OPERATION_IDS);

        JsonNode schemas = spec.at("/components/schemas");
        assertThat(schemas.at("/SessionStatus/enum").values()).extracting(JsonNode::asString)
                .contains("NEW", "NEEDS_HUMAN", "EXPIRED");
        JsonNode summary = schemas.get("SessionSummary");
        List<String> required = summary.get("required").values().stream().map(JsonNode::asString).toList();
        // responses: every field is always present, nullable ones may be null
        assertThat(required).contains("id", "publicRef", "status", "needsHuman", "lastEventAt",
                "customerAddr", "lane", "boardVerdict");
        // request bodies: nullable fields may be omitted
        List<String> liveRequired = schemas.at("/LiveUpdate/required").isMissingNode() ? List.of()
                : schemas.at("/LiveUpdate/required").values().stream().map(JsonNode::asString).toList();
        assertThat(liveRequired).doesNotContain("pollingEnabled", "autopilot");
        assertThat(isNullable(summary.at("/properties/customerAddr"))).isTrue();
        assertThat(isNullable(summary.at("/properties/publicRef"))).isFalse();
        assertThat(isNullable(schemas.at("/SessionDetail/properties/rfq"))).isTrue();
        assertThat(isNullable(schemas.at("/PageSessionSummary/properties/nextCursor"))).isTrue();
    }

    @Test
    @EnabledIfSystemProperty(named = "exportOpenApi", matches = "true")
    void exportForFrontend() throws Exception {
        String pretty = mapper.writer().with(SerializationFeature.INDENT_OUTPUT).writeValueAsString(spec());
        Files.writeString(TARGET, pretty + "\n", StandardCharsets.UTF_8);
        System.out.println("[openapi] wrote " + TARGET.toAbsolutePath().normalize());
    }

    private JsonNode spec() throws Exception {
        return mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** OpenAPI 3.1 style: {@code type: [T, "null"]} or {@code anyOf: [{$ref}, {type: "null"}]}. */
    private static boolean isNullable(JsonNode property) {
        boolean typeUnion = property.path("type").isArray()
                && property.path("type").values().stream().anyMatch(t -> "null".equals(t.asString()));
        boolean refUnion = property.path("anyOf").values().stream()
                .anyMatch(alt -> "null".equals(alt.path("type").asString(null)));
        return typeUnion || refUnion;
    }
}
