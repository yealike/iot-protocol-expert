package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AuthorizeResult", description = "OCPP 卡授权结果")
public record AuthorizeResult(String status) {}
