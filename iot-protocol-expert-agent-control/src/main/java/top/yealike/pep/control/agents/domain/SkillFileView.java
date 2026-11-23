package top.yealike.pep.control.agents.domain;

import java.time.Instant;

public record SkillFileView(String path, String digest, long size, boolean entry, Instant updatedAt) {}
