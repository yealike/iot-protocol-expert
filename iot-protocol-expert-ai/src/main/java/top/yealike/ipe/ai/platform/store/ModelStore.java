package top.yealike.ipe.ai.platform.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.ModelProfile;
import top.yealike.ipe.ai.support.JsonSupport;

@Repository
public class ModelStore {

    private static final String SELECT = """
            select m.id, m.component_key, m.display_name, m.description, m.provider_type, m.base_url,
                   m.model_name, m.credential_id, c.credential_key, m.options_json, m.enabled, m.revision,
                   m.created_by, m.created_at, m.updated_at
              from ai_model_profile m
              left join ai_credential c on c.id = m.credential_id
            """;

    private final JdbcTemplate jdbc;

    public ModelStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ModelProfile> list() {
        return jdbc.query(SELECT + " order by m.component_key", (rows, index) -> map(rows));
    }

    public Optional<ModelProfile> find(UUID id) {
        return jdbc.query(SELECT + " where m.id = ?", (rows, index) -> map(rows), id).stream()
                .findFirst();
    }

    public Optional<ModelProfile> findByKey(String key) {
        return jdbc.query(SELECT + " where m.component_key = ?", (rows, index) -> map(rows), key).stream()
                .findFirst();
    }

    public UUID insert(
            String key,
            String displayName,
            String description,
            String providerType,
            String baseUrl,
            String modelName,
            UUID credentialId,
            String optionsJson,
            String actor) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into ai_model_profile (id, component_key, display_name, description, provider_type, base_url, model_name, credential_id, options_json, enabled, revision, created_by) values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), true, 0, ?)",
                id,
                key,
                displayName,
                description,
                providerType,
                baseUrl,
                modelName,
                credentialId,
                optionsJson,
                actor);
        return id;
    }

    public int update(
            UUID id,
            long expectedRevision,
            String displayName,
            String description,
            String baseUrl,
            String modelName,
            UUID credentialId,
            String optionsJson,
            boolean enabled) {
        return jdbc.update(
                "update ai_model_profile set display_name = ?, description = ?, base_url = ?, model_name = ?, credential_id = ?, options_json = cast(? as jsonb), enabled = ?, revision = revision + 1, updated_at = now() where id = ? and revision = ?",
                displayName,
                description,
                baseUrl,
                modelName,
                credentialId,
                optionsJson,
                enabled,
                id,
                expectedRevision);
    }

    public int setEnabled(UUID id, boolean enabled) {
        return jdbc.update(
                "update ai_model_profile set enabled = ?, revision = revision + 1, updated_at = now() where id = ?",
                enabled,
                id);
    }

    private ModelProfile map(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new ModelProfile(
                JdbcRows.uuid(rows, "id"),
                rows.getString("component_key"),
                rows.getString("display_name"),
                rows.getString("description"),
                rows.getString("provider_type"),
                rows.getString("base_url"),
                rows.getString("model_name"),
                JdbcRows.uuid(rows, "credential_id"),
                rows.getString("credential_key"),
                JsonSupport.readMap(rows.getString("options_json")),
                rows.getBoolean("enabled"),
                rows.getLong("revision"),
                rows.getString("created_by"),
                JdbcRows.instant(rows, "created_at"),
                JdbcRows.instant(rows, "updated_at"));
    }
}
