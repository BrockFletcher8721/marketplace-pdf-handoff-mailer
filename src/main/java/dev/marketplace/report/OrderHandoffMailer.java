package dev.marketplace.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import dev.marketplace.report.InfraiEmailClient.EmailMessage;
import dev.marketplace.report.InfraiEmailClient.SendResult;
import dev.marketplace.report.OrderHandoffReport.OrderHandoff;

public final class OrderHandoffMailer {
    private final InfraiEmailClient emailClient;
    private final OrderHandoffReport report;

    public OrderHandoffMailer(InfraiEmailClient emailClient, OrderHandoffReport report) {
        this.emailClient = emailClient;
        this.report = report;
    }

    public HandoffResult notifyBuyer(OrderHandoff handoff) throws IOException, InterruptedException {
        if (!handoff.paymentCleared()) return new HandoffResult(Decision.WAITING_FOR_PAYMENT, "");
        if (handoff.complianceHold()) return new HandoffResult(Decision.HELD_FOR_REVIEW, "");

        byte[] pdf = report.render(handoff);
        String pdfData = Base64.getEncoder().encodeToString(pdf);
        String html = "<h1>Order " + escape(handoff.orderId()) + " is ready</h1>"
                + "<p>Your seller asset <strong>" + escape(handoff.assetName()) + "</strong> is ready for handoff.</p>"
                + "<p><a download=\"order-" + escape(handoff.orderId())
                + ".pdf\" href=\"data:application/pdf;base64," + pdfData + "\">Download PDF report</a></p>";
        SendResult sent = emailClient.send(new EmailMessage(handoff.buyerEmail(),
                "Marketplace order " + handoff.orderId() + " handoff", html),
                "order-handoff-" + handoff.orderId());
        return new HandoffResult(Decision.SENT, sent.messageId());
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public enum Decision { WAITING_FOR_PAYMENT, HELD_FOR_REVIEW, SENT }
    public record HandoffResult(Decision decision, String messageId) {}
}
