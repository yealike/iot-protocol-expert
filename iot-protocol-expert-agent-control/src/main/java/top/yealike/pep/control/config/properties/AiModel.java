package top.yealike.pep.control.config.properties;

public record AiModel(
        boolean enabled, String baseUrl, String apiKey, String name, int timeoutMs, int maxTokens, double temperature) {

    public boolean usable() {
        return enabled && baseUrl != null && !baseUrl.isBlank();
    }
}
