package top.yealike.ipe.agent.runtime.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import top.yealike.ipe.ai.contract.McpDescriptor;

/** 一条已初始化的 MCP 连接；引用计数归零后才允许空闲回收。 */
public final class McpConnection {

    private final String key;
    private final McpDescriptor descriptor;
    private final McpSyncClient client;
    private final Instant createdAt = Instant.now();
    private final AtomicInteger references = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Instant lastUsed = Instant.now();
    private volatile boolean draining;

    McpConnection(String key, McpDescriptor descriptor, McpSyncClient client) {
        this.key = key;
        this.descriptor = descriptor;
        this.client = client;
    }

    public String key() {
        return key;
    }

    public McpDescriptor descriptor() {
        return descriptor;
    }

    McpSyncClient client() {
        return client;
    }

    public void acquire() {
        references.incrementAndGet();
        lastUsed = Instant.now();
    }

    public void release() {
        if (references.decrementAndGet() < 0) {
            references.set(0);
        }
        lastUsed = Instant.now();
    }

    public int references() {
        return references.get();
    }

    public Instant lastUsed() {
        return lastUsed;
    }

    public boolean draining() {
        return draining;
    }

    public void draining(boolean value) {
        draining = value;
    }

    public boolean close() {
        if (!closed.compareAndSet(false, true)) {
            return false;
        }
        try {
            client.closeGracefully();
        } catch (RuntimeException ignored) {
            try {
                client.close();
            } catch (RuntimeException closeFailure) {
                // 关闭失败不能阻塞连接池回收；底层客户端已不可继续使用。
            }
        }
        return true;
    }

    public boolean closed() {
        return closed.get();
    }
}
