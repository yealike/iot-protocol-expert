package top.yealike.ipe.protocol.core;

import java.util.Set;

/** 协议无关的在线会话视图。 */
public interface OnlineDeviceDirectory {

    boolean isOnline(String pileCode);

    Set<String> onlinePiles();
}
