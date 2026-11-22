package top.yealike.pep.control.runs.domain;

import java.util.Map;

public record RuntimeEventFingerprint(long sourceSeq, String type, Map<String, Object> payload) {}
