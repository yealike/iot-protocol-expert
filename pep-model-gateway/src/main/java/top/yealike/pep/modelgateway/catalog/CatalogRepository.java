package top.yealike.pep.modelgateway.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertProvider(
            UUID id, String code, String name, String baseUrl, byte[] cipher, byte[] nonce, String keyId) {
        jdbc.update("""
                insert into pep_metering.model_provider
                    (id, code, name, base_url, credential_cipher, credential_nonce, credential_key_id)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (code) do update
                   set name = excluded.name,
                       base_url = excluded.base_url,
                       credential_cipher = excluded.credential_cipher,
                       credential_nonce = excluded.credential_nonce,
                       credential_key_id = excluded.credential_key_id,
                       updated_at = now()
                """, id, code, name, baseUrl, cipher, nonce, keyId);
    }

    public List<CatalogRecords.Provider> listProviders() {
        return jdbc.query(
                """
                select id, code, name, base_url, credential_cipher, credential_nonce, state
                  from pep_metering.model_provider
                 order by code
                """,
                (rs, rowNum) -> new CatalogRecords.Provider(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("base_url"),
                        rs.getBytes("credential_cipher"),
                        rs.getBytes("credential_nonce"),
                        rs.getString("state")));
    }

    public Optional<CatalogRecords.Provider> findProvider(String code) {
        return jdbc
                .query(
                        """
                        select id, code, name, base_url, credential_cipher, credential_nonce, state
                          from pep_metering.model_provider
                         where code = ?
                        """,
                        (rs, rowNum) -> new CatalogRecords.Provider(
                                rs.getObject("id", UUID.class),
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getString("base_url"),
                                rs.getBytes("credential_cipher"),
                                rs.getBytes("credential_nonce"),
                                rs.getString("state")),
                        code)
                .stream()
                .findFirst();
    }

    public Optional<CatalogRecords.Provider> findProviderById(UUID id) {
        return jdbc
                .query(
                        """
                        select id, code, name, base_url, credential_cipher, credential_nonce, state
                          from pep_metering.model_provider
                         where id = ?
                        """,
                        (rs, rowNum) -> new CatalogRecords.Provider(
                                rs.getObject("id", UUID.class),
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getString("base_url"),
                                rs.getBytes("credential_cipher"),
                                rs.getBytes("credential_nonce"),
                                rs.getString("state")),
                        id)
                .stream()
                .findFirst();
    }

    public void insertProfile(
            UUID id,
            String key,
            String displayName,
            UUID providerId,
            String upstreamModel,
            Integer contextWindow,
            int maxOutputTokens,
            int weightNum,
            int weightDen,
            String countingMode) {
        jdbc.update(
                """
                insert into pep_metering.model_profile
                    (id, key, display_name, provider_id, upstream_model, context_window,
                     max_output_tokens, token_weight_num, token_weight_den, counting_mode)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (key) do update
                   set display_name = excluded.display_name,
                       provider_id = excluded.provider_id,
                       upstream_model = excluded.upstream_model,
                       context_window = excluded.context_window,
                       max_output_tokens = excluded.max_output_tokens,
                       token_weight_num = excluded.token_weight_num,
                       token_weight_den = excluded.token_weight_den,
                       counting_mode = excluded.counting_mode,
                       updated_at = now()
                """,
                id,
                key,
                displayName,
                providerId,
                upstreamModel,
                contextWindow,
                maxOutputTokens,
                weightNum,
                weightDen,
                countingMode);
    }

    public List<CatalogRecords.ModelProfile> listProfiles() {
        return jdbc.query("""
                select id, key, display_name, provider_id, upstream_model, context_window,
                       max_output_tokens, token_weight_num, token_weight_den, counting_mode, state
                  from pep_metering.model_profile
                 order by key
                """, (rs, rowNum) -> profile(rs));
    }

    public Optional<CatalogRecords.ModelProfile> findProfile(String key) {
        return jdbc.query("""
                        select id, key, display_name, provider_id, upstream_model, context_window,
                               max_output_tokens, token_weight_num, token_weight_den, counting_mode, state
                          from pep_metering.model_profile
                         where key = ?
                        """, (rs, rowNum) -> profile(rs), key).stream().findFirst();
    }

    private static CatalogRecords.ModelProfile profile(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CatalogRecords.ModelProfile(
                rs.getObject("id", UUID.class),
                rs.getString("key"),
                rs.getString("display_name"),
                rs.getObject("provider_id", UUID.class),
                rs.getString("upstream_model"),
                (Integer) rs.getObject("context_window"),
                rs.getInt("max_output_tokens"),
                rs.getInt("token_weight_num"),
                rs.getInt("token_weight_den"),
                rs.getString("counting_mode"),
                rs.getString("state"));
    }
}
