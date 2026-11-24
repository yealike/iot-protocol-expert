package top.yealike.pep.control.agents.domain;

/** 草稿能力和策略的持久化 JSON 字段。 */
public record AgentTeamSettings(String tagsJson, String capabilitiesJson, String policyJson) {}
