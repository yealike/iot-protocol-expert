package top.yealike.pep.gateway.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 协议报文解码请求；完整报文与帧体二选一。 */
@Schema(name = "DecodeRequest", description = "协议报文解码请求")
public record DecodeRequest(
        @Schema(description = "完整线上报文的十六进制字符串；提供后优先解码此字段", example = "68...")
        String wireHex,

        @Schema(description = "帧类型编码；仅在未提供 wireHex 时使用", example = "1")
        Integer frameType,

        @Schema(description = "帧体十六进制字符串；仅在未提供 wireHex 时使用", example = "010203")
        String bodyHex) {}
