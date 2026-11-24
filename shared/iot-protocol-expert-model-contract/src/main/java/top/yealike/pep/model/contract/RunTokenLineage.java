package top.yealike.pep.model.contract;

/** v2 模型凭据中的委派归属。 */
public record RunTokenLineage(String rootRun, String parentRun, String a2aTask, int depth, long maxTotalTokens) {

    public RunTokenLineage {
        if (rootRun == null
                || rootRun.isBlank()
                || depth < 0
                || depth > 3
                || maxTotalTokens < 1
                || (depth == 0 && (parentRun != null || a2aTask != null))
                || (depth > 0 && (parentRun == null || parentRun.isBlank() || a2aTask == null || a2aTask.isBlank()))) {
            throw new IllegalArgumentException("运行凭据的委派归属不完整");
        }
    }
}
