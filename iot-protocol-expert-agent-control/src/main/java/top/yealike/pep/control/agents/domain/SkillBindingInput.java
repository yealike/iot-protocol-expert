package top.yealike.pep.control.agents.domain;

import java.util.UUID;

public record SkillBindingInput(UUID skillReleaseId, int order, String loadMode) {}
