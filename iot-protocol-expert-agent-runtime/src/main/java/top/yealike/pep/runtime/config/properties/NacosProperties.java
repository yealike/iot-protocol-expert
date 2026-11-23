package top.yealike.pep.runtime.config.properties;

/** Nacos 注册与发现配置。 */
public record NacosProperties(
        String serverAddr, String username, String password, String namespace, String group, boolean enabled) {

    public NacosProperties {
        serverAddr = blank(serverAddr) ? "127.0.0.1:8848" : serverAddr;
        namespace = blank(namespace) ? "public" : namespace;
        group = blank(group) ? "IPE_AI_COMPONENTS" : group;
    }

    static NacosProperties defaults() {
        return new NacosProperties("127.0.0.1:8848", "", "", "public", "IPE_AI_COMPONENTS", false);
    }

    @Override
    public String toString() {
        return "NacosProperties[serverAddr=" + serverAddr
                + ", username=" + username
                + ", password=<redacted>"
                + ", namespace=" + namespace
                + ", group=" + group
                + ", enabled=" + enabled
                + "]";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
