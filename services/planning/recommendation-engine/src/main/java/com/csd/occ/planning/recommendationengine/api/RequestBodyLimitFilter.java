package com.csd.occ.planning.recommendationengine.api;

import com.csd.occ.planning.recommendationengine.api.config.RecommendationInputProperties;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class RequestBodyLimitFilter extends OncePerRequestFilter {
    private static final String RECOMMENDATION_PATH = "/internal/v1/bunching-recommendations";

    private final RecommendationInputProperties properties;
    private final JsonMapper json;

    public RequestBodyLimitFilter(RecommendationInputProperties properties, JsonMapper json) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.json = Objects.requireNonNull(json, "json is required");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !RECOMMENDATION_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            jakarta.servlet.FilterChain filterChain) throws IOException, jakarta.servlet.ServletException {
        long limit = properties.maxRequestBodyBytes();
        if (request.getContentLengthLong() > limit) {
            writeTooLarge(response, request);
            return;
        }
        try {
            filterChain.doFilter(new LimitedBodyRequest(request, limit), response);
        } catch (RequestBodyLimitExceededException exception) {
            if (!response.isCommitted()) writeTooLarge(response, request);
            else throw exception;
        }
    }

    private void writeTooLarge(HttpServletResponse response, HttpServletRequest request) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
                "The request body exceeds the configured size limit.");
        problem.setTitle("Request body too large");
        problem.setType(URI.create("urn:occ:problem:request-body-too-large"));
        problem.setInstance(URI.create(request.getRequestURI()));
        String correlationId = request.getHeader("X-Correlation-ID");
        if (correlationId != null && !correlationId.isBlank()) problem.setProperty("correlationId", correlationId);
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream inputStream;
        private BufferedReader reader;

        private LimitedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (reader != null) throw new IllegalStateException("getReader() has already been called");
            if (inputStream == null) inputStream = new LimitedServletInputStream(super.getInputStream(), limit);
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (inputStream != null) throw new IllegalStateException("getInputStream() has already been called");
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                inputStream = new LimitedServletInputStream(super.getInputStream(), limit);
                reader = new BufferedReader(new InputStreamReader(inputStream, charset));
            }
            return reader;
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private long bytesRead;

        private LimitedServletInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0 && ++bytesRead > limit) throw new RequestBodyLimitExceededException(limit);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            long remainingWithSentinel = Math.max(1, limit - bytesRead + 1);
            int boundedLength = (int) Math.min(length, remainingWithSentinel);
            int count = delegate.read(bytes, offset, boundedLength);
            if (count > 0) {
                bytesRead += count;
                if (bytesRead > limit) throw new RequestBodyLimitExceededException(limit);
            }
            return count;
        }

        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener readListener) { delegate.setReadListener(readListener); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
