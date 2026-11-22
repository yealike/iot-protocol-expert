package top.yealike.pep.control.agents.mcp.domain;

import java.util.List;
import java.util.Map;

public record CatalogEntry(String serverName, String version, String endpoint, List<Map<String, Object>> tools) {}
