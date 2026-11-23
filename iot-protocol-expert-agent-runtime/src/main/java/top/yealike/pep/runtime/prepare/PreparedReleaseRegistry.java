package top.yealike.pep.runtime.prepare;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import top.yealike.pep.runtime.prepare.domain.PreparedRelease;

/** 已准备版本的内存注册表；Runtime 重启后由 Control 的 desired-releases 对账重新 prepare。 */
@Component
public class PreparedReleaseRegistry {

    private static final int MAX_RECORDED_FAILURES = 1_024;

    private final ConcurrentHashMap<UUID, PreparedRelease> releases = new ConcurrentHashMap<>();
    private final LinkedHashMap<UUID, SchemaFailure> schemaFailures = new LinkedHashMap<>();

    public Optional<PreparedRelease> find(UUID releaseId) {
        return Optional.ofNullable(releases.get(releaseId));
    }

    public void put(PreparedRelease release) {
        releases.put(release.releaseId(), release);
        clearFailure(release.releaseId());
    }

    public void remove(UUID releaseId) {
        releases.remove(releaseId);
        clearFailure(releaseId);
    }

    public synchronized void recordSchemaFailure(UUID releaseId, String digest, String message) {
        schemaFailures.putLast(releaseId, new SchemaFailure(digest, message));
        if (schemaFailures.size() > MAX_RECORDED_FAILURES) {
            schemaFailures.pollFirstEntry();
        }
    }

    public synchronized Optional<String> schemaFailure(UUID releaseId, String digest) {
        return Optional.ofNullable(schemaFailures.get(releaseId))
                .filter(failure -> failure.digest().equalsIgnoreCase(digest))
                .map(SchemaFailure::message);
    }

    private synchronized void clearFailure(UUID releaseId) {
        schemaFailures.remove(releaseId);
    }

    public List<PreparedRelease> all() {
        return List.copyOf(releases.values());
    }

    private record SchemaFailure(String digest, String message) {}
}
