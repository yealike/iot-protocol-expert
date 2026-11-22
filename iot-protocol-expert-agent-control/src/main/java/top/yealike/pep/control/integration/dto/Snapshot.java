package top.yealike.pep.control.integration.dto;

import java.net.URI;
import java.util.Set;

public record Snapshot(
        String serverName, String version, URI endpoint, Set<String> tools, String source, String problem) {}
