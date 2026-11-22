package top.yealike.pep.control.api.ai.admin.dto;

import java.util.UUID;

public record SkillBindingRequest(
        UUID skillReleaseId, String componentKey, String version, Integer order, String loadMode) {}
