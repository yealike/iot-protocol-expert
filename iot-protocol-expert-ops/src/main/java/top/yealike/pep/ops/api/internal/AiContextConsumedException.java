package top.yealike.pep.ops.api.internal;

public class AiContextConsumedException extends IllegalArgumentException {
    public AiContextConsumedException() {
        super("Context already consumed");
    }
}
