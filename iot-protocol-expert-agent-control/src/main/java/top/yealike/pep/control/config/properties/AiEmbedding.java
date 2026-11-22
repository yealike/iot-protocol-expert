package top.yealike.pep.control.config.properties;

public record AiEmbedding(boolean enabled, String baseUrl, String apiKey, String name, int dimensions, int timeoutMs) {

    public boolean usable() {
        return enabled && baseUrl != null && !baseUrl.isBlank();
    }
}
