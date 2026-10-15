import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only proxy that drops one response after Planning has acknowledged its durable commit. */
public final class PlanningCommitFaultProxy {
    private static final String SUBMISSION_PATH = "/internal/v1/planning/requests";
    private static final int REQUEST_TIMEOUT_SECONDS = 10;

    private PlanningCommitFaultProxy() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "healthcheck".equals(args[0])) {
            checkHealth(Integer.parseInt(args[1]));
            return;
        }
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: <proxy-port> <control-port> <planning-base-url>");
        }

        int proxyPort = Integer.parseInt(args[0]);
        int controlPort = Integer.parseInt(args[1]);
        URI planningBaseUrl = URI.create(args[2]);
        State state = new State();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        startControlServer(controlPort, state);
        try (ServerSocket server = new ServerSocket(proxyPort)) {
            System.out.println("Planning commit fault proxy ready");
            var requests = Executors.newVirtualThreadPerTaskExecutor();
            while (!server.isClosed()) {
                Socket socket = server.accept();
                requests.submit(() -> forward(socket, planningBaseUrl, client, state));
            }
        }
    }

    private static void startControlServer(int port, State state) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/__test/health", exchange -> respond(exchange, 200, "ok"));
        server.createContext("/__test/status", exchange -> respond(exchange, 200, state.json()));
        server.start();
    }

    private static void checkHealth(int port) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__test/health"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        HttpResponse<Void> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) throw new IllegalStateException("fault proxy is not ready");
    }

    private static void respond(HttpExchange exchange, int status, String value) throws IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void forward(Socket socket, URI planningBaseUrl, HttpClient client, State state) {
        try (socket) {
            socket.setSoTimeout(REQUEST_TIMEOUT_SECONDS * 1000);
            InputStream input = socket.getInputStream();
            String requestLine = readLine(input);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ", 3);
            if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
                writeResponse(socket, 400, new byte[0], null);
                return;
            }

            Map<String, String> headers = readHeaders(input);
            String expect = headers.get("expect");
            if ("100-continue".equalsIgnoreCase(expect)) {
                socket.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            }
            byte[] body = readBody(input, headers);
            String target = parts[1];
            if ("GET".equalsIgnoreCase(parts[0]) && target.equals("/__test/status")) {
                writeResponse(socket, 200, state.json().getBytes(StandardCharsets.UTF_8), "application/json");
                return;
            }

            URI targetUri = URI.create(planningBaseUrl.toString().replaceAll("/+$", "") + target);
            HttpRequest.Builder request = HttpRequest.newBuilder(targetUri)
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));
            headers.forEach((name, value) -> {
                if (!isHopByHop(name)) request.header(name, value);
            });
            var publisher = body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body);
            HttpResponse<byte[]> response = client.send(request.method(parts[0], publisher).build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            if (SUBMISSION_PATH.equals(target.split("\\?", 2)[0]) && "POST".equalsIgnoreCase(parts[0])) {
                state.record(headers.get("idempotency-key"), headers.get("x-occ-event-fingerprint"), body,
                        response.statusCode());
                if (response.statusCode() == 202 && state.dropFirstCommittedResponse()) {
                    System.out.println("Injected lost response after Planning returned HTTP 202");
                    return;
                }
            }

            writeResponse(socket, response.statusCode(), response.body(),
                    response.headers().firstValue("content-type").orElse(null));
        } catch (Exception failure) {
            System.err.println("Planning fault proxy request failed: " + failure.getClass().getSimpleName());
            try {
                if (!socket.isClosed()) writeResponse(socket, 502, new byte[0], null);
            } catch (IOException ignored) {
                // The caller may already have closed a failed request.
            }
        }
    }

    private static boolean isHopByHop(String headerName) {
        return switch (headerName.toLowerCase(Locale.ROOT)) {
            case "host", "connection", "content-length", "transfer-encoding", "expect", "upgrade" -> true;
            default -> false;
        };
    }

    private static Map<String, String> readHeaders(InputStream input) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(input)) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator <= 0) throw new IOException("malformed request header");
            headers.put(line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                    line.substring(separator + 1).trim());
        }
        return headers;
    }

    private static byte[] readBody(InputStream input, Map<String, String> headers) throws IOException {
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) return readChunkedBody(input);
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        if (length < 0 || length > 1_048_576) throw new IOException("request body exceeds test proxy limit");
        byte[] body = input.readNBytes(length);
        if (body.length != length) throw new IOException("incomplete request body");
        return body;
    }

    private static byte[] readChunkedBody(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(input);
            if (line == null) throw new IOException("incomplete chunked request body");
            int size = Integer.parseInt(line.split(";", 2)[0].trim(), 16);
            if (size == 0) {
                while ((line = readLine(input)) != null && !line.isEmpty()) { }
                return body.toByteArray();
            }
            if (body.size() + size > 1_048_576) throw new IOException("request body exceeds test proxy limit");
            byte[] chunk = input.readNBytes(size);
            if (chunk.length != size || !"".equals(readLine(input))) throw new IOException("invalid request chunk");
            body.write(chunk);
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.ISO_8859_1);
            }
            line.write(current);
            previous = current;
            if (line.size() > 16_384) throw new IOException("HTTP line exceeds proxy limit");
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    private static void writeResponse(Socket socket, int status, byte[] body, String contentType) throws IOException {
        String reason = switch (status) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Content";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "Upstream Response";
        };
        OutputStream output = socket.getOutputStream();
        StringBuilder response = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason)
                .append("\r\nContent-Length: ").append(body.length)
                .append("\r\nConnection: close\r\n");
        if (contentType != null) response.append("Content-Type: ").append(contentType).append("\r\n");
        response.append("\r\n");
        output.write(response.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private static String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final class State {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger firstReceiverStatus = new AtomicInteger();
        private final AtomicInteger lastResponseStatus = new AtomicInteger();
        private final AtomicInteger dropped = new AtomicInteger();
        private final AtomicReference<String> firstKey = new AtomicReference<>();
        private final AtomicReference<String> firstFingerprint = new AtomicReference<>();
        private final AtomicReference<String> firstBodyHash = new AtomicReference<>();
        private volatile boolean sameKeys = true;
        private volatile boolean sameFingerprints = true;
        private volatile boolean sameBodies = true;

        private synchronized void record(String key, String fingerprint, byte[] body, int status) throws Exception {
            lastResponseStatus.set(status);
            String digest = sha256(body);
            if (calls.getAndIncrement() == 0) {
                firstKey.set(key);
                firstFingerprint.set(fingerprint);
                firstBodyHash.set(digest);
                firstReceiverStatus.set(status);
            } else {
                sameKeys &= Objects.equals(firstKey.get(), key);
                sameFingerprints &= Objects.equals(firstFingerprint.get(), fingerprint);
                sameBodies &= Objects.equals(firstBodyHash.get(), digest);
            }
        }

        private boolean dropFirstCommittedResponse() {
            return dropped.compareAndSet(0, 1);
        }

        private synchronized String json() {
            return "{\"calls\":" + calls.get()
                    + ",\"firstReceiverStatus\":" + firstReceiverStatus.get()
                    + ",\"firstResponseDropped\":" + (dropped.get() == 1)
                    + ",\"lastResponseStatus\":" + lastResponseStatus.get()
                    + ",\"idempotencyKey\":" + jsonString(firstKey.get())
                    + ",\"sameIdempotencyKey\":" + sameKeys
                    + ",\"sameFingerprint\":" + sameFingerprints
                    + ",\"samePayload\":" + sameBodies + "}";
        }
    }
}
