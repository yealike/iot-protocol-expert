package top.yealike.ipe.ai.contract;

import java.util.List;

/** 不可变 Skill 发布包。 */
public record SkillBundle(
        String key, String version, String name, String description, String digest, List<SkillFile> files) {

    public SkillBundle {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
