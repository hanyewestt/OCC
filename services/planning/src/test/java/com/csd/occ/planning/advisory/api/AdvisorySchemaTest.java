package com.csd.occ.planning.advisory.api;

import static org.assertj.core.api.Assertions.assertThat;
import com.csd.occ.planning.advisory.api.dto.*;
import com.csd.occ.planning.advisory.domain.InstructionParameters;
import io.swagger.v3.core.converter.ModelConverters;
import org.junit.jupiter.api.Test;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;

class AdvisorySchemaTest {
    @Test
    void pageEnvelopesHaveDistinctNamesAndCorrectItems() {
        assertPage(AdvisoryResponse.PageResponse.class, "AdvisoryPageResponse", "AdvisoryResponse");
        assertPage(InstructionResponse.PageResponse.class, "InstructionPageResponse", "InstructionResponse");
        assertPage(NoticeResponse.PageResponse.class, "ManagedNoticePageResponse", "NoticeResponse");
        assertPage(PublishedNoticeResponse.PageResponse.class, "PublishedNoticePageResponse", "PublishedNoticeResponse");
    }
    @Test
    void protectedOperationsDeclareBearerSecurityAndProblemDetailsForRuntimeErrors() throws Exception {
        assertThat(AdvisoryController.class.getAnnotation(SecurityRequirement.class).name()).isEqualTo("SupabaseJWT");
        assertThat(NoticeController.class.getAnnotation(SecurityRequirement.class).name()).isEqualTo("SupabaseJWT");
        assertThat(BunchingInstructionController.class.getAnnotation(SecurityRequirement.class).name()).isEqualTo("SupabaseJWT");
        assertThat(NoticeController.class.getMethod("published", String.class, java.util.UUID.class, int.class, int.class)
                .getAnnotation(SecurityRequirements.class)).isNotNull();
        assertProblem(AdvisoryController.class.getMethod("decide", java.util.UUID.class, DecideAdvisoryRequest.class,
                org.springframework.security.oauth2.jwt.Jwt.class).getAnnotation(ApiResponses.class), "400", "404", "409");
        assertProblem(AdvisoryController.class.getMethod("acknowledge", java.util.UUID.class,
                org.springframework.security.oauth2.jwt.Jwt.class).getAnnotation(ApiResponses.class), "404", "409");
        assertProblem(NoticeController.class.getMethod("publish", java.util.UUID.class,
                org.springframework.security.oauth2.jwt.Jwt.class).getAnnotation(ApiResponses.class), "404", "409");
        assertNullable(InstructionResponse.class, "acknowledgedAt");
        assertNullable(InstructionResponse.class, "applicableUntil");
        assertNullable(InstructionParameters.class, "speedReductionKmh");
        assertNullable(AdvisoryResponse.class, "finalCandidate");
        assertNullable(NoticeResponse.class, "publishedAt");
    }
    private void assertNullable(Class<?> type, String field) {
        try {
            assertThat(type.getDeclaredField(field).getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class).nullable()).isTrue();
        } catch (NoSuchFieldException e) { throw new AssertionError(e); }
    }
    private void assertProblem(ApiResponses responses, String... codes) {
        for (String code : codes) {
            ApiResponse response = java.util.Arrays.stream(responses.value()).filter(value -> value.responseCode().equals(code)).findFirst().orElseThrow();
            Content content = response.content()[0];
            assertThat(content.mediaType()).isEqualTo("application/problem+json");
            assertThat(content.schema().implementation()).isEqualTo(org.springframework.http.ProblemDetail.class);
        }
    }
    private void assertPage(Class<?> type, String name, String item) {
        var schemas = ModelConverters.getInstance().read(type);
        assertThat(schemas).containsKey(name);
        assertThat(((io.swagger.v3.oas.models.media.Schema<?>) schemas.get(name).getProperties().get("items")).getItems().get$ref())
                .isEqualTo("#/components/schemas/" + item);
    }
}
