package top.yealike.ipe.protocol.codec;

/** 解码后的原始帧（保留线上 hex，便于日志与排查）。 */
public record InboundFrame(Frame frame, String wireHex) {
}
