package top.yealike.ipe.ai.platform.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.Credential;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.CredentialSecret;

@Repository
public class CredentialStore {

    private static final String COLUMNS =
            "id, credential_key, display_name, current_secret_revision, enabled, revision, created_by, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public CredentialStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Credential> list() {
        return jdbc.query(
                "select " + COLUMNS + " from ai_credential order by credential_key", (rows, index) -> map(rows));
    }

    public Optional<Credential> find(UUID id) {
        return jdbc
                .query("select " + COLUMNS + " from ai_credential where id = ?", (rows, index) -> map(rows), id)
                .stream()
                .findFirst();
    }

    public Optional<Credential> findByKey(String key) {
        return jdbc
                .query(
                        "select " + COLUMNS + " from ai_credential where credential_key = ?",
                        (rows, index) -> map(rows),
                        key)
                .stream()
                .findFirst();
    }

    public void insert(UUID id, String key, String displayName, String actor) {
        jdbc.update(
                "insert into ai_credential (id, credential_key, display_name, current_secret_revision, enabled, revision, created_by) values (?, ?, ?, 0, true, 0, ?)",
                id,
                key,
                displayName,
                actor);
    }

    public void insertSecret(
            UUID credentialId, long revision, byte[] ciphertext, byte[] nonce, String keyId, String actor) {
        jdbc.update(
                "insert into ai_credential_secret (id, credential_id, secret_revision, ciphertext, nonce, key_id, state) values (?, ?, ?, ?, ?, ?, 'ACTIVE')",
                UUID.randomUUID(),
                credentialId,
                revision,
                ciphertext,
                nonce,
                keyId);
        jdbc.update(
                "update ai_credential set current_secret_revision = ?, revision = revision + 1, updated_at = now() where id = ?",
                revision,
                credentialId);
        jdbc.update(
                "update ai_credential_secret set state = 'RETIRED' where credential_id = ? and secret_revision < ?",
                credentialId,
                revision);
    }

    public Optional<CredentialSecret> findSecret(UUID credentialId, long revision) {
        return jdbc
                .query(
                        "select ciphertext, nonce, key_id, state from ai_credential_secret where credential_id = ? and secret_revision = ?",
                        (rows, index) -> new CredentialSecret(
                                rows.getBytes("ciphertext"),
                                rows.getBytes("nonce"),
                                rows.getString("key_id"),
                                rows.getString("state")),
                        credentialId,
                        revision)
                .stream()
                .findFirst();
    }

    public void setEnabled(UUID id, boolean enabled) {
        jdbc.update(
                "update ai_credential set enabled = ?, revision = revision + 1, updated_at = now() where id = ?",
                enabled,
                id);
    }

    public long references(UUID credentialId) {
        Long count = jdbc.queryForObject(
                "select (select count(*) from ai_model_profile where credential_id = ?) + (select count(*) from ai_mcp_release where credential_id = ?)",
                Long.class,
                credentialId,
                credentialId);
        return count == null ? 0 : count;
    }

    private Credential map(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new Credential(
                JdbcRows.uuid(rows, "id"),
                rows.getString("credential_key"),
                rows.getString("display_name"),
                rows.getLong("current_secret_revision"),
                rows.getBoolean("enabled"),
                rows.getLong("revision"),
                rows.getString("created_by"),
                JdbcRows.instant(rows, "created_at"),
                JdbcRows.instant(rows, "updated_at"));
    }
}
