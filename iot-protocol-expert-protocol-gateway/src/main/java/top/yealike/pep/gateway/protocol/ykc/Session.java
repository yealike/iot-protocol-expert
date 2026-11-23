package top.yealike.pep.gateway.protocol.ykc;

import io.netty.channel.Channel;
import java.util.concurrent.atomic.AtomicInteger;

/** 云快充 TCP 在线会话。 */
public record Session(
        Channel channel, String deviceSn, String externalPileCode, boolean encrypted, AtomicInteger sequence) {
    public Session(Channel channel, String deviceSn, String externalPileCode, boolean encrypted) {
        this(channel, deviceSn, externalPileCode, encrypted, new AtomicInteger());
    }

    public int next() {
        return sequence.getAndUpdate(v -> v >= 65535 ? 0 : v + 1);
    }
}
