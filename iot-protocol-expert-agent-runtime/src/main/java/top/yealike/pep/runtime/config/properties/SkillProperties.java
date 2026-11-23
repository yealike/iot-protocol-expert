package top.yealike.pep.runtime.config.properties;

/** Skill 文本读取上限。 */
public record SkillProperties(int maxReadChars) {

    public SkillProperties {
        maxReadChars = maxReadChars <= 0 ? 12_000 : maxReadChars;
    }

    static SkillProperties defaults() {
        return new SkillProperties(12_000);
    }
}
