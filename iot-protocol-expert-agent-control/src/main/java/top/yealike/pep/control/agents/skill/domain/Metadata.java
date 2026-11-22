package top.yealike.pep.control.agents.skill.domain;

import java.util.Map;

public record Metadata(
        String name,
        String description,
        Map<String, String> metadata,
        String license,
        String compatibility,
        String allowedTools) {
    public Metadata(String name, String description, Map<String, String> metadata) {
        this(name, description, metadata, null, null, null);
    }
}
