package top.yealike.pep.gateway.protocol.ocpp16;

import io.netty.channel.Channel;

/** OCPP 1.6J WebSocket 会话。 */
public record Session(Channel channel, String deviceSn, String externalChargePointId) {}
