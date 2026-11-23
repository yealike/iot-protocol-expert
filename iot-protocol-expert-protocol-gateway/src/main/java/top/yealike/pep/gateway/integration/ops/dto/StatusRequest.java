package top.yealike.pep.gateway.integration.ops.dto;

/** OCPP 充电枪状态通知。 */
public record StatusRequest(String protocolDeviceId, int connectorId, String status, String errorCode) {}
