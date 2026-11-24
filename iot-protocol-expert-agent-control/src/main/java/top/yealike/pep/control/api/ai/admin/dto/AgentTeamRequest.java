package top.yealike.pep.control.api.ai.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import top.yealike.pep.runtime.contract.A2aCapability;
import top.yealike.pep.runtime.contract.DelegationPolicy;

/** 按草稿修订号整体保存委派配置。 */
@Schema(description = "更新 Agent 的能力声明和委派配置")
public record AgentTeamRequest(
        @PositiveOrZero long expectedRevision,
        @NotNull @Size(max = 16) List<@NotBlank @Size(max = 40) String> capabilityTags,
        @NotNull @Size(max = 16) List<@NotNull A2aCapability> a2aCapabilities,
        @NotNull @Size(max = 8) List<@NotNull @Valid DelegateBindingRequest> delegates,
        DelegationPolicy delegationPolicy) {}
