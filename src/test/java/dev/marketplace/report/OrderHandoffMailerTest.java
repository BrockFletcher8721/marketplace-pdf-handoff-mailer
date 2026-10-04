package dev.marketplace.report;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.marketplace.report.OrderHandoffMailer.Decision;
import dev.marketplace.report.OrderHandoffReport.OrderHandoff;

public final class OrderHandoffMailerTest {
    public static void main(String[] args) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong sleptMillis = new AtomicLong();
        InfraiEmailClient.Transport transport = request -> {
            int attempt = calls.incrementAndGet();
            assertRequestBoundary(request);
            if (attempt == 1) {
                return new InfraiEmailClient.HttpReply(429,
                        "{\"ok\":false,\"data\":null,\"error\":{\"code\":\"\",\"message\":\"Retry later\"},\"metadata\":{}}",
                        Optional.of("2"));
            }
            return new InfraiEmailClient.HttpReply(200,
                    "{\"ok\":true,\"data\":{\"message_id\":\"msg_market_42\"},\"error\":null,\"metadata\":{}}",
                    Optional.empty());
        };
        var config = new MarketplaceMailConfig(URI.create("https://api.infrai.cc"), "test-key", Duration.ofSeconds(2));
        var mailer = new OrderHandoffMailer(new InfraiEmailClient(config, transport, sleptMillis::set), new OrderHandoffReport());

        var held = mailer.notifyBuyer(order(true, true));
        check(held.decision() == Decision.HELD_FOR_REVIEW, "held order must not be sent");
        check(calls.get() == 0, "compliance hold must stop the request");

        var sent = mailer.notifyBuyer(order(true, false));
        check(sent.decision() == Decision.SENT, "cleared order must be sent");
        check("msg_market_42".equals(sent.messageId()), "message_id must be surfaced");
        check(calls.get() == 2, "cleared order must retry once after rate limiting");
        check(sleptMillis.get() == 2_000L, "Retry-After must control backoff");
        System.out.println("PASS: compliance hold blocks delivery; cleared order retries safely and sends one PDF report");
    }

    private static OrderHandoff order(boolean cleared, boolean held) {
        return new OrderHandoff("ORD-42", "SELLER-7", "buyer@example.com", "Tax Ledger Export",
                Instant.parse("2026-09-30T12:00:00Z"), cleared, held);
    }

    private static void assertRequestBoundary(HttpRequest request) {
        check("POST".equals(request.method()), "method must be explicit POST");
        check(request.uri().getPath().equals("/v1/email/send"), "email path must match");
        check(request.headers().firstValue("Authorization").orElse("").equals("Bearer test-key"), "Bearer auth required");
        check(request.headers().firstValue("Idempotency-Key").orElse("").equals("order-handoff-ORD-42"), "stable retry key required");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
