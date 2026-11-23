package top.yealike.pep.simulator.runtime;

import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** OCPP WebSocket 事件监听；文本消息按分片聚合后交给客户端处理。 */
final class OcppWebSocketListener implements WebSocket.Listener {

    private static final Logger log = LoggerFactory.getLogger(OcppWebSocketListener.class);

    private final OcppSimulatorClient owner;
    private final StringBuilder buffer = new StringBuilder();

    OcppWebSocketListener(OcppSimulatorClient owner) {
        this.owner = owner;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            String message = buffer.toString();
            buffer.setLength(0);
            try {
                owner.handle(message);
            } catch (RuntimeException e) {
                log.warn("处理 OCPP 下行消息失败: {}", e.getMessage(), e);
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, java.nio.ByteBuffer message) {
        webSocket.sendPong(message);
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        owner.socketTerminated(webSocket, "通道关闭 " + statusCode + " " + reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        owner.socketTerminated(webSocket, "通道异常: " + OcppSimulatorClient.rootMessage(error));
    }
}
