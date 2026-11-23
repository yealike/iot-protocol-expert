package top.yealike.pep.control.runs.domain;

/** Run 取消受理结果。 */
public record RunCancelResult(boolean ok, String status, String note) {}
