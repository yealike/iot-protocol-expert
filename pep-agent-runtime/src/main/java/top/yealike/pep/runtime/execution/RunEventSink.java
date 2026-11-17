package top.yealike.pep.runtime.execution;

import java.util.Map;

/** 单 Run 事件出口；实现方负责 sourceSeq、批量与重试。 */
@FunctionalInterface
public interface RunEventSink {

    void emit(String type, Map<String, Object> payload);
}
