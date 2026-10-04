package dev.marketplace.report;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

public final class InfraiEmailClient {
    private static final String EMAIL_SEND_PATH = "/v1/email/send";
    private static final int MAX_ATTEMPTS = 4;

    private final MarketplaceMailConfig config;
    private final Transport transport;
    private final Sleeper sleeper;

    public InfraiEmailClient(MarketplaceMailConfig config) {
        this(config, new JdkTransport(), Thread::sleep);
    }

    InfraiEmailClient(MarketplaceMailConfig config, Transport transport, Sleeper sleeper) {
        this.config = config;
        this.transport = transport;
        this.sleeper = sleeper;
    }

    // Canonical call shape: infrai.email.send
    public SendResult send(EmailMessage message, String idempotencyKey) throws IOException, InterruptedException {
        String body = "{\"to\":\"" + json(message.to()) + "\",\"subject\":\"" +
                json(message.subject()) + "\",\"html\":\"" + json(message.html()) + "\"}";

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(config.baseUri().resolve(EMAIL_SEND_PATH))
                    .timeout(config.requestTimeout())
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpReply reply = transport.send(request);

            Envelope envelope = Envelope.parse(reply.body());
            if (reply.statusCode() == 429 && attempt + 1 < MAX_ATTEMPTS) {
                sleeper.sleep(retryDelayMillis(reply.retryAfter(), attempt));
                continue;
            }
            if (reply.statusCode() >= 500) {
                throw new IOException("Email transport returned HTTP " + reply.statusCode());
            }
            if (!envelope.ok()) {
                throw new InfraiException(envelope.errorCode(), envelope.errorMessage(), reply.statusCode());
            }
            return new SendResult(envelope.messageId());
        }
        throw new IOException("Email request exhausted retry attempts");
    }

    private static long retryDelayMillis(Optional<String> retryAfter, int attempt) {
        if (retryAfter.isPresent()) {
            try {
                return Math.max(0L, Long.parseLong(retryAfter.get().trim()) * 1_000L);
            } catch (NumberFormatException ignored) {
                // Fall through to bounded exponential backoff.
            }
        }
        return Math.min(8_000L, 500L * (1L << attempt));
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private record Envelope(boolean ok, String messageId, String errorCode, String errorMessage) {
        static Envelope parse(String json) throws IOException {
            boolean ok = booleanValue(json, "ok");
            return new Envelope(ok, stringValue(json, "message_id"), stringValue(json, "code"),
                    firstNonBlank(stringValue(json, "message"), stringValue(json, "hint")));
        }

        private static boolean booleanValue(String json, String key) throws IOException {
            int start = valueStart(json, key);
            if (json.startsWith("true", start)) return true;
            if (json.startsWith("false", start)) return false;
            throw new IOException("Invalid boolean envelope field: " + key);
        }

        private static String stringValue(String json, String key) throws IOException {
            String marker = "\"" + key + "\"";
            int keyAt = json.indexOf(marker);
            if (keyAt < 0) return "";
            int start = valueStart(json, key);
            if (json.startsWith("null", start)) return "";
            if (start >= json.length() || json.charAt(start) != '"') return "";
            StringBuilder out = new StringBuilder();
            boolean escaped = false;
            for (int i = start + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (escaped) {
                    out.append(switch (c) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> c; });
                    escaped = false;
                } else if (c == '\\') escaped = true;
                else if (c == '"') return out.toString();
                else out.append(c);
            }
            throw new IOException("Unterminated envelope string: " + key);
        }

        private static int valueStart(String json, String key) throws IOException {
            int keyAt = json.indexOf("\"" + key + "\"");
            int colon = keyAt < 0 ? -1 : json.indexOf(':', keyAt);
            if (colon < 0) throw new IOException("Missing envelope field: " + key);
            int start = colon + 1;
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            return start;
        }

        private static String firstNonBlank(String first, String second) {
            return first == null || first.isBlank() ? second : first;
        }
    }

    public record EmailMessage(String to, String subject, String html) {}
    public record SendResult(String messageId) {}

    public static final class InfraiException extends IOException {
        private final String code;
        private final int statusCode;

        InfraiException(String code, String message, int statusCode) {
            super(message == null || message.isBlank() ? code : message);
            this.code = code;
            this.statusCode = statusCode;
        }
        public String code() { return code; }
        public int statusCode() { return statusCode; }
    }

    interface Transport { HttpReply send(HttpRequest request) throws IOException, InterruptedException; }
    interface Sleeper { void sleep(long millis) throws InterruptedException; }
    record HttpReply(int statusCode, String body, Optional<String> retryAfter) {}

    private static final class JdkTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        public HttpReply send(HttpRequest request) throws IOException, InterruptedException {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new HttpReply(response.statusCode(), response.body(), response.headers().firstValue("Retry-After"));
        }
    }
}
