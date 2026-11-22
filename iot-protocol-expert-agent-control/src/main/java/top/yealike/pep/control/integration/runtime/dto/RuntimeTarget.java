package top.yealike.pep.control.integration.runtime.dto;

public record RuntimeTarget(String baseUrl, String instanceId, String serviceName) {
    public boolean matchesInstance(String owner) {
        return "direct".equals(serviceName) || owner.equals(instanceId);
    }
}
