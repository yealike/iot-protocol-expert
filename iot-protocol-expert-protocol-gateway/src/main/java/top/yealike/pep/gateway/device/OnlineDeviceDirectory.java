package top.yealike.pep.gateway.device;

import java.util.Set;

/** 协议无关的在线会话视图。 */
public interface OnlineDeviceDirectory {

    boolean isOnline(String pileCode);

    Set<String> onlinePiles();
}
