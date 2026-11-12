package top.yealike.ipe.protocol.core;

/** 协议服务端扩展点。一个协议一个监听器，由 {@link ProtocolServerManager} 统一启停。 */
public interface ProtocolServer {

    ProtocolType protocol();

    /** 是否在配置中启用。 */
    boolean enabled();

    int port();

    void start();

    void stop();

    boolean isRunning();
}
