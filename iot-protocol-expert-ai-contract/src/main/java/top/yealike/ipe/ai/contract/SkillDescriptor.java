package top.yealike.ipe.ai.contract;

import java.util.List;

/** Skill 版本元数据；正文必须按文件单独读取，避免一次返回整包。 */
public record SkillDescriptor(
        String key, String version, String name, String description, String digest, List<SkillFileInfo> files) {

    public SkillDescriptor {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
