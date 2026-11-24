package top.yealike.pep.control.api.ai.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** 管理端选择的委派目标与执行限制。 */
@Schema(description = "委派绑定")
public record DelegateBindingRequest(
        @Pattern(regexp = "[a-z][a-z0-9_]{1,39}") @NotNull String alias,
        @NotNull UUID targetReleaseId,
        @NotBlank @Size(max = 512) String description,
        @Size(max = 1024) String whenToUse,
        @Size(max = 32) List<@NotBlank @Size(max = 64) String> allowedTools,
        boolean required,
        @Min(1) @Max(10) int maxCalls,
        @Min(5000) @Max(300000) int timeoutMs,
        @Min(0) @Max(10000) int order) {}
