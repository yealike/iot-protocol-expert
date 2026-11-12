package top.yealike.ipe.protocol.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 聚合各协议在线会话，供 REST 控制台使用。 */
@Component
public class OnlineDeviceDirectoryService {

    private final List<OnlineDeviceDirectory> directories;

    public OnlineDeviceDirectoryService(List<OnlineDeviceDirectory> directories) {
        this.directories = directories;
    }

    public boolean isOnline(String pileCode) {
        return directories.stream().anyMatch(d -> d.isOnline(pileCode));
    }

    public Set<String> onlinePiles() {
        Set<String> result = new LinkedHashSet<>();
        directories.forEach(d -> result.addAll(d.onlinePiles()));
        return result;
    }
}
