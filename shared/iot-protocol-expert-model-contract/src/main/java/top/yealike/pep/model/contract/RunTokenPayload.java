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
        String jti,
        RunTokenLineage lineage) {

    /** 构造兼容既有网关的 v1 凭据。 */
    public RunTokenPayload(
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
            String jti) {
        this(aud, sub, scopeKind, actor, tenant, project, task, run, modelProfile, exp, jti, null);
    }

    /** 根 Run 归属，v1 用户凭据视作根执行。 */
    public String rootRun() {
        return lineage == null ? run : lineage.rootRun();
    }
}
