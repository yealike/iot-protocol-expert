package top.yealike.pep.control.agents.domain;

/** 能力卡片与用于缓存校验的发布摘要。 */
public record AgentCardSnapshot(AgentCapabilityCard card, String manifestDigest) {}
