package top.yealike.pep.runtime.contract;

/** PREPARING 也可作为幂等中间态返回；FAILED 必须带可理解原因。 */
public record ReleasePrepareResponse(String state, String instanceId, String manifestDigest, String message) {}
