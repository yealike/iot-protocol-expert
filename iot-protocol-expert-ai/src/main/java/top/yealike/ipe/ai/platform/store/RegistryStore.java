package top.yealike.ipe.ai.platform.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.RegistryProjection;

@Repository
public class RegistryStore {

    private static final String SELECT =
            "select id, resource_type, release_id, service_name, provider_id, desired_state, observed_state, owner_node, last_success_at, last_error from ai_registry_projection";

    private final JdbcTemplate jdbc;

    public RegistryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(
            String resourceType,
            UUID releaseId,
            String serviceName,
            String providerId,
            String desiredState,
            String observedState,
            String error) {
        int updated = jdbc.update(
                "update ai_registry_projection set desired_state = ?, observed_state = ?, last_success_at = case when ? = 'READY' then now() else last_success_at end, last_error = ? where resource_type = ? and release_id = ? and provider_id = ?",
                desiredState,
                observedState,
                observedState,
                error,
                resourceType,
                releaseId,
                providerId);
        if (updated == 0) {
            jdbc.update(
                    "insert into ai_registry_projection (id, resource_type, release_id, service_name, provider_id, desired_state, observed_state, last_success_at, last_error) values (?, ?, ?, ?, ?, ?, ?, case when ? = 'READY' then now() else null end, ?)",
                    UUID.randomUUID(),
                    resourceType,
                    releaseId,
                    serviceName,
                    providerId,
                    desiredState,
                    observedState,
                    observedState,
                    error);
        }
    }

    public List<RegistryProjection> list() {
        return jdbc.query(SELECT + " order by resource_type, service_name", (rows, index) -> map(rows));
    }

    public Optional<RegistryProjection> find(String resourceType, UUID releaseId) {
        return jdbc
                .query(
                        SELECT
                                + " where resource_type = ? and release_id = ? order by last_success_at desc nulls last limit 1",
                        (rows, index) -> map(rows),
                        resourceType,
                        releaseId)
                .stream()
                .findFirst();
    }

    public void markObserved(String resourceType, UUID releaseId, String observedState, String error) {
        jdbc.update(
                "update ai_registry_projection set observed_state = ?, last_success_at = case when ? = 'READY' then now() else last_success_at end, last_error = ? where resource_type = ? and release_id = ?",
                observedState,
                observedState,
                error,
                resourceType,
                releaseId);
    }

    private RegistryProjection map(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new RegistryProjection(
                JdbcRows.uuid(rows, "id"),
                rows.getString("resource_type"),
                JdbcRows.uuid(rows, "release_id"),
                rows.getString("service_name"),
                rows.getString("provider_id"),
                rows.getString("desired_state"),
                rows.getString("observed_state"),
                rows.getString("owner_node"),
                JdbcRows.instant(rows, "last_success_at"),
                rows.getString("last_error"));
    }
}
