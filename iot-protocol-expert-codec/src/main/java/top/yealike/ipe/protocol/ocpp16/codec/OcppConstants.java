package top.yealike.ipe.protocol.ocpp16.codec;

/** OCPP 1.6J 动作、状态与错误码常量。 */
public final class OcppConstants {

    private OcppConstants() {}

    public static final String PROTOCOL = "OCPP16J";
    public static final String SUB_PROTOCOL = "ocpp1.6";

    public static final String BOOT_NOTIFICATION = "BootNotification";
    public static final String HEARTBEAT = "Heartbeat";
    public static final String STATUS_NOTIFICATION = "StatusNotification";
    public static final String AUTHORIZE = "Authorize";
    public static final String START_TRANSACTION = "StartTransaction";
    public static final String METER_VALUES = "MeterValues";
    public static final String STOP_TRANSACTION = "StopTransaction";
    public static final String DATA_TRANSFER = "DataTransfer";
    public static final String DIAGNOSTICS_STATUS_NOTIFICATION = "DiagnosticsStatusNotification";
    public static final String FIRMWARE_STATUS_NOTIFICATION = "FirmwareStatusNotification";
    public static final String REMOTE_START_TRANSACTION = "RemoteStartTransaction";
    public static final String REMOTE_STOP_TRANSACTION = "RemoteStopTransaction";
    public static final String RESET = "Reset";
    public static final String UNLOCK_CONNECTOR = "UnlockConnector";
    public static final String CHANGE_CONFIGURATION = "ChangeConfiguration";
    public static final String GET_CONFIGURATION = "GetConfiguration";
    public static final String CHANGE_AVAILABILITY = "ChangeAvailability";
    public static final String TRIGGER_MESSAGE = "TriggerMessage";
    public static final String CLEAR_CACHE = "ClearCache";
    public static final String GET_DIAGNOSTICS = "GetDiagnostics";
    public static final String UPDATE_FIRMWARE = "UpdateFirmware";

    public static final String ACCEPTED = "Accepted";
    public static final String REJECTED = "Rejected";
    public static final String PENDING = "Pending";
    public static final String NOT_SUPPORTED = "NotSupported";
    public static final String UNLOCKED = "Unlocked";

    public static final String ERROR_NOT_IMPLEMENTED = "NotImplemented";
    public static final String ERROR_NOT_SUPPORTED = "NotSupported";
    public static final String ERROR_INTERNAL = "InternalError";
    public static final String ERROR_PROTOCOL = "ProtocolError";
    public static final String ERROR_SECURITY = "SecurityError";
    public static final String ERROR_OCCURRENCE = "OccurrenceConstraintViolation";
    public static final String ERROR_PROPERTY = "PropertyConstraintViolation";

    public static final String STATUS_AVAILABLE = "Available";
    public static final String STATUS_PREPARING = "Preparing";
    public static final String STATUS_CHARGING = "Charging";
    public static final String STATUS_SUSPENDED_EVSE = "SuspendedEVSE";
    public static final String STATUS_SUSPENDED_EV = "SuspendedEV";
    public static final String STATUS_FINISHING = "Finishing";
    public static final String STATUS_RESERVED = "Reserved";
    public static final String STATUS_UNAVAILABLE = "Unavailable";
    public static final String STATUS_FAULTED = "Faulted";
}
