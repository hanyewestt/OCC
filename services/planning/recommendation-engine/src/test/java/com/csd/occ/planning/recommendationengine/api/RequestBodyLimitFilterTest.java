package com.csd.occ.planning.recommendationengine.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.csd.occ.planning.recommendationengine.api.config.RecommendationInputProperties;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RequestBodyLimitFilterTest {
    private static final long BODY_LIMIT = 16_384;

    @Test
    void rejectsKnownOversizeContentLengthBeforeControllerExecution() throws Exception {
        RequestBodyLimitFilter filter = filter();
        MockHttpServletRequest request = request(new byte[(int) BODY_LIMIT + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (wrapped, output) -> continued.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("request-body-too-large", "Request body too large");
        assertThat(continued).isFalse();
    }

    @Test
    void rejectsChunkedOversizeBodyWhileReading() throws Exception {
        RequestBodyLimitFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/bunching-recommendations") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[(int) BODY_LIMIT + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (wrapped, output) -> {
            continued.set(true);
            wrapped.getInputStream().readAllBytes();
        });

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(continued).isTrue();
    }

    @Test
    void allowsBodyExactlyAtConfiguredLimit() throws Exception {
        RequestBodyLimitFilter filter = filter();
        MockHttpServletRequest request = request(new byte[(int) BODY_LIMIT]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (wrapped, output) -> {
            continued.set(wrapped.getInputStream().readAllBytes().length == BODY_LIMIT);
        });

        assertThat(continued).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void appliesOnlyToRecommendationPostEndpoint() throws Exception {
        RequestBodyLimitFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/recommendations");
        request.setContent("oversize is ignored on unrelated route".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (wrapped, output) -> continued.set(true));

        assertThat(continued).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private RequestBodyLimitFilter filter() {
        return new RequestBodyLimitFilter(new RecommendationInputProperties(BODY_LIMIT, 8_192),
                JsonMapper.builder().findAndAddModules().build());
    }

    private MockHttpServletRequest request(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/internal/v1/bunching-recommendations");
        request.setContent(body);
        return request;
    }
}
