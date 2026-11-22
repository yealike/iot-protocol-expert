package top.yealike.pep.control.api.ai.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record IndexRequest(
        @Schema(description = "索引完成后是否直接发布") Boolean publish) {}
