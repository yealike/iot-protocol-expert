package top.yealike.pep.gateway.device;

/** 平台枪号与第三方协议枪号的映射。 */
public record Connector(String platformGunNo, String gunNo, String protocolConnectorNo, int connectorOrdinal) {

    public String externalYkcGunNo() {
        return protocolConnectorNo;
    }

    public int externalOcppConnectorId() {
        return connectorOrdinal;
    }
}
