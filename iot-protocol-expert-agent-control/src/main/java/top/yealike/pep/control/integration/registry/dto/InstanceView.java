package top.yealike.pep.control.integration.registry.dto;

import java.util.Map;

public record InstanceView(String ip, int port, Map<String, String> metadata) {}
