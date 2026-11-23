package top.yealike.pep.ops.devices.thingmodel.domain;

/** 静态标签：安装位置、额定功率、SIM 卡号这类不随时间变化的信息。 */
public record TagMetadata(String id, String name, ValueType valueType, String description) {}
