package com.csd.occ.planning.diagnosis.infrastructure.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.HttpStatusCode;

public final class BoundedResponseBodyInterceptor implements ClientHttpRequestInterceptor {
    private final long maximumBytes;

    public BoundedResponseBodyInterceptor(long maximumBytes) {
        if (maximumBytes < 1) throw new IllegalArgumentException("maximumBytes must be positive");
        this.maximumBytes = maximumBytes;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        long contentLength = response.getHeaders().getContentLength();
        if (contentLength > maximumBytes) {
            response.close();
            throw new PeerResponseLimitExceededException(maximumBytes);
        }
        return new BoundedClientHttpResponse(response, maximumBytes);
    }

    private static final class BoundedClientHttpResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final long maximumBytes;
        private InputStream boundedBody;

        private BoundedClientHttpResponse(ClientHttpResponse delegate, long maximumBytes) {
            this.delegate = Objects.requireNonNull(delegate);
            this.maximumBytes = maximumBytes;
        }

        @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
        @Override public void close() { delegate.close(); }

        @Override
        public InputStream getBody() throws IOException {
            if (boundedBody == null) boundedBody = new CountingInputStream(delegate.getBody(), maximumBytes);
            return boundedBody;
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final long maximumBytes;
        private long bytesRead;

        private CountingInputStream(InputStream input, long maximumBytes) {
            super(input);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && ++bytesRead > maximumBytes) throw new PeerResponseLimitExceededException(maximumBytes);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            long remainingWithSentinel = Math.max(1, maximumBytes - bytesRead + 1);
            int boundedLength = (int) Math.min(length, remainingWithSentinel);
            int count = in.read(bytes, offset, boundedLength);
            if (count > 0) {
                bytesRead += count;
                if (bytesRead > maximumBytes) throw new PeerResponseLimitExceededException(maximumBytes);
            }
            return count;
        }
    }
}
