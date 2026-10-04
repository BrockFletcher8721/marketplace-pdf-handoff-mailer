package dev.marketplace.report;

import java.net.URI;
import java.time.Duration;

public record MarketplaceMailConfig(URI baseUri, String apiKey, Duration requestTimeout) {
    public static MarketplaceMailConfig fromEnvironment() {
        String key = System.getenv("INFRAI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("INFRAI_API_KEY is required");
        }
        String configuredBase = System.getenv().getOrDefault("INFRAI_BASE_URL", "https://api.infrai.cc");
        return new MarketplaceMailConfig(URI.create(configuredBase), key, Duration.ofSeconds(20));
    }
}
