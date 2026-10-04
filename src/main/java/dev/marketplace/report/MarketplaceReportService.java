package dev.marketplace.report;

import java.time.Instant;
import dev.marketplace.report.OrderHandoffReport.OrderHandoff;

public final class MarketplaceReportService {
    private MarketplaceReportService() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: MarketplaceReportService <order-id> <seller-id> <buyer-email> <asset-name>");
            System.exit(2);
        }
        MarketplaceMailConfig config = MarketplaceMailConfig.fromEnvironment();
        OrderHandoffMailer mailer = new OrderHandoffMailer(new InfraiEmailClient(config), new OrderHandoffReport());
        var result = mailer.notifyBuyer(new OrderHandoff(args[0], args[1], args[2], args[3],
                Instant.now(), true, false));
        System.out.println("decision=" + result.decision() + " message_id=" + result.messageId());
    }
}
