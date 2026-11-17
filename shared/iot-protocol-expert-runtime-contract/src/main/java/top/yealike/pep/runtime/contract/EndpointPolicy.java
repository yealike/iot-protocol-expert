package top.yealike.pep.runtime.contract;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 可配置外部 HTTP(S) 入口的统一目标校验。
 *
 * <p>允许内网业务地址，但拒绝本机、链路本地、组播、未指定地址及已知云元数据地址。调用方应在真正发起网络请求前调用
 * {@link #requireAllowedAndResolvable(String)}，避免配置校验后 DNS 结果变化。
 */
public final class EndpointPolicy {

    private EndpointPolicy() {}

    /** 校验并规范化地址；对数字 IP 立即解析，对域名延迟到连接前验证。 */
    public static URI requireAllowed(String endpoint) {
        return URI.create(parseAllowed(endpoint).toUri());
    }

    /** 发起请求前调用：解析全部 A/AAAA 记录，任一地址不安全即拒绝。 */
    public static URI requireAllowedAndResolvable(String endpoint) {
        ComponentEndpoint parsed = parseAllowed(endpoint);
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(parsed.host());
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("入口主机无法解析", exception);
        }
        if (addresses.length == 0) {
            throw new IllegalArgumentException("入口主机无法解析");
        }
        for (InetAddress address : addresses) {
            rejectUnsafeAddress(address);
        }
        return URI.create(parsed.toUri());
    }

    private static ComponentEndpoint parseAllowed(String endpoint) {
        ComponentEndpoint parsed;
        try {
            parsed = ComponentEndpoint.parse(endpoint);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("入口必须是有效的 http(s) 地址", exception);
        }
        String host = normalizedHost(parsed.host());
        if (isKnownMetadataName(host)) {
            throw new IllegalArgumentException("入口不允许云元数据地址");
        }
        if (looksLikeIpLiteral(host)) {
            try {
                rejectUnsafeAddress(InetAddress.getByName(host));
            } catch (UnknownHostException exception) {
                throw new IllegalArgumentException("入口主机无法解析", exception);
            }
        }
        return parsed;
    }

    private static String normalizedHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isKnownMetadataName(String host) {
        return host.equals("metadata.google.internal")
                || host.equals("instance-data.ec2.internal")
                || host.equals("localhost")
                || host.endsWith(".localhost");
    }

    private static boolean looksLikeIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return host.matches("(?i)(?:0x[0-9a-f]+|\\d+)(?:\\.(?:0x[0-9a-f]+|\\d+)){0,3}");
    }

    private static void rejectUnsafeAddress(InetAddress address) {
        String literal = address.getHostAddress();
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || literal.equals("100.100.100.200")
                || literal.equals("fd00:ec2:0:0:0:0:0:254")) {
            throw new IllegalArgumentException("入口不允许本机、链路本地、组播或云元数据地址");
        }
    }
}
