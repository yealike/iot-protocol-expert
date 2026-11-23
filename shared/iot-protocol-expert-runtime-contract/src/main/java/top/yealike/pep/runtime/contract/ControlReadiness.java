package top.yealike.pep.runtime.contract;

/** Control 对 Runtime 暴露的就绪信息。 */
public record ControlReadiness(String status, boolean readiness, String service, boolean database) {}
