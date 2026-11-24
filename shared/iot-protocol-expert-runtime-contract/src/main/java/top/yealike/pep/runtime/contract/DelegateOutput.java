package top.yealike.pep.runtime.contract;

/** 对子任务输出格式和返回长度的约束。 */
public record DelegateOutput(String format, Integer maxChars) {}
