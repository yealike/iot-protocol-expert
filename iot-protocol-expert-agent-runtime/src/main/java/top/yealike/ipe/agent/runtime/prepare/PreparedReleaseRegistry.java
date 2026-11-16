package top.yealike.ipe.agent.runtime.prepare;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 已准备版本的内存注册表；Runtime 重启后由 Control 的 desired-releases 对账重新 prepare。 */
@Component
public class PreparedReleaseRegistry {

    private final ConcurrentHashMap<UUID, PreparedRelease> releases = new ConcurrentHashMap<>();

    public Optional<PreparedRelease> find(UUID releaseId) {
        return Optional.ofNullable(releases.get(releaseId));
    }

    public void put(PreparedRelease release) {
        releases.put(release.releaseId(), release);
    }

    public void remove(UUID releaseId) {
        releases.remove(releaseId);
    }

    public List<PreparedRelease> all() {
        return List.copyOf(releases.values());
    }
}
