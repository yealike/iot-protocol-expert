package top.yealike.ipe.protocol.ocpp16.codec;

import tools.jackson.databind.JsonNode;

/**
 * OCPP 1.6J 消息。JSON 数组形态： [2, uniqueId, action, payload] / [3, uniqueId, payload] / [4, uniqueId,
 * errorCode, description, details]。
 */
public record OcppMessage(
        int messageTypeId,
        String uniqueId,
        String action,
        JsonNode payload,
        String errorCode,
        String errorDescription) {

    public static final int CALL = 2;
    public static final int CALL_RESULT = 3;
    public static final int CALL_ERROR = 4;

    public static OcppMessage call(String uniqueId, String action, JsonNode payload) {
        return new OcppMessage(CALL, uniqueId, action, payload, null, null);
    }

    public static OcppMessage result(String uniqueId, JsonNode payload) {
        return new OcppMessage(CALL_RESULT, uniqueId, null, payload, null, null);
    }

    public static OcppMessage error(String uniqueId, String errorCode, String description, JsonNode details) {
        return new OcppMessage(CALL_ERROR, uniqueId, null, details, errorCode, description);
    }

    public boolean isCall() {
        return messageTypeId == CALL;
    }

    public boolean isResult() {
        return messageTypeId == CALL_RESULT;
    }

    public boolean isError() {
        return messageTypeId == CALL_ERROR;
    }

    public String typeLabel() {
        return switch (messageTypeId) {
            case CALL -> "CALL";
            case CALL_RESULT -> "CALLRESULT";
            case CALL_ERROR -> "CALLERROR";
            default -> "UNKNOWN";
        };
    }
}
