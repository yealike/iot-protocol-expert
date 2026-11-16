package top.yealike.ipe.ai.tool;

import java.util.List;

/** 一期受控工具清单。业务工具默认只读，不开放 shell、代码执行、写 SQL 或设备控制。 */
public final class ToolNames {

    private ToolNames() {}

    public static final String SEARCH_PROTOCOL_KNOWLEDGE = "search_protocol_knowledge";
    public static final String GET_ORDER_DETAIL = "get_order_detail";
    public static final String GET_ORDER_BILLS = "get_order_bills";
    public static final String GET_DEVICE_DETAIL = "get_device_detail";
    public static final String GET_ORDER_TELEMETRY_SUMMARY = "get_order_telemetry_summary";
    public static final String ASK_PROTOCOL_AGENT = "ask_protocol_agent";
    public static final String ASK_ORDER_AGENT = "ask_order_agent";

    /** 只在应用内部调用、不交给模型的远程能力。 */
    public static final String RESOLVE_SESSION = "resolve_session";

    public static final String RESOLVE_ACTOR = "resolve_actor";
    public static final String LIST_DOCUMENTS = "list_documents";
    public static final String READ_DOCUMENT = "read_document";

    /** 必须出现在 Nacos MCP 目录里的工具；缺失说明目录与实际运维接口已漂移。 */
    public static final List<String> REMOTE_TOOLS = List.of(
            GET_ORDER_DETAIL,
            GET_ORDER_BILLS,
            GET_DEVICE_DETAIL,
            GET_ORDER_TELEMETRY_SUMMARY,
            RESOLVE_SESSION,
            RESOLVE_ACTOR,
            LIST_DOCUMENTS,
            READ_DOCUMENT);

    public static final List<String> BUSINESS_TOOLS = List.of(
            GET_ORDER_DETAIL,
            GET_ORDER_BILLS,
            GET_DEVICE_DETAIL,
            GET_ORDER_TELEMETRY_SUMMARY,
            SEARCH_PROTOCOL_KNOWLEDGE);

    public static final List<String> DELEGATION_TOOLS = List.of(ASK_PROTOCOL_AGENT, ASK_ORDER_AGENT);

    public static final List<String> ALL = List.of(
            SEARCH_PROTOCOL_KNOWLEDGE,
            GET_ORDER_DETAIL,
            GET_ORDER_BILLS,
            GET_DEVICE_DETAIL,
            GET_ORDER_TELEMETRY_SUMMARY,
            ASK_PROTOCOL_AGENT,
            ASK_ORDER_AGENT);
}
