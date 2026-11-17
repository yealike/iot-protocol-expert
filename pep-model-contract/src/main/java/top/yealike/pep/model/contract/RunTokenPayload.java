package top.yealike.pep.model.contract;

/**
 * 由控制服务签发、只对模型网关有效的短期运行凭据载荷。
 *
 * <p>模型输入不能覆盖这些字段；Runtime 只负责原样携带，不自行构造扣费主体。
 */
public record RunTokenPayload(
        String aud,
        String sub,
        String scopeKind,
        String actor,
        String tenant,
        String project,
        String task,
        String run,
        String modelProfile,
        long exp,
        String jti) {}
