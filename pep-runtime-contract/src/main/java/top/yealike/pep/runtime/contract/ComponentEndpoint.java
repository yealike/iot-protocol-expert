package top.yealike.pep.runtime.contract;

import java.net.URI;
import java.util.Locale;

/**
 * 组件入口的受控 Endpoint。
 *
 * <p>只允许 HTTP/HTTPS，统一校验 scheme、host、port、path 与 query，避免 Control 和 Runtime 各自拼接地址时
 * 丢失 TLS 或产生不同的路径语义。
 */
public record ComponentEndpoint(String scheme, String host, int port, String path, String query) {

    public ComponentEndpoint {
        scheme = scheme == null ? "" : scheme.strip().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("组件入口只允许 http 或 https");
        }
        host = normalizeHost(host);
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("组件入口端口无效");
        }
        path = normalizePath(path);
        query = normalizeQuery(query);
    }

    public static ComponentEndpoint parse(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("组件入口不能为空");
        }
        URI uri;
        try {
            uri = URI.create(endpoint.strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("组件入口不是合法 URI", exception);
        }
        if (uri.getScheme() == null
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("组件入口必须是不含用户信息与片段的绝对地址");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() < 0 ? defaultPort(scheme) : uri.getPort();
        return of(scheme, uri.getHost(), port, uri.getRawPath(), uri.getRawQuery());
    }

    public static ComponentEndpoint of(String scheme, String host, int port, String path, String query) {
        return new ComponentEndpoint(scheme, host, port, path, query);
    }

    public String baseUrl() {
        String authority = host.contains(":") ? "[" + host + "]" : host;
        return scheme + "://" + authority + (port == defaultPort(scheme) ? "" : ":" + port);
    }

    public String toUri() {
        return baseUrl() + path + (query.isBlank() ? "" : "?" + query);
    }

    @Override
    public String toString() {
        return toUri();
    }

    private static String normalizeHost(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.isBlank()
                || normalized.contains("/")
                || normalized.contains("\\")
                || normalized.contains("@")
                || normalized.contains("#")
                || normalized.contains("?")
                || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("组件入口主机名无效");
        }
        return normalized;
    }

    private static String normalizePath(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.contains("#") || normalized.contains("?")) {
            throw new IllegalArgumentException("组件入口路径不能包含查询或片段");
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static String normalizeQuery(String value) {
        String normalized = value == null ? "" : value.strip();
        while (normalized.startsWith("?")) {
            normalized = normalized.substring(1);
        }
        if (normalized.contains("#")) {
            throw new IllegalArgumentException("组件入口查询不能包含片段");
        }
        return normalized;
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }
}
