package top.yealike.pep.control.agents.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.agents.domain.PlatformRecords.Credential;
import top.yealike.pep.control.agents.domain.PlatformRecords.CredentialSecret;

public interface CredentialMapper {

    List<Credential> list();

    List<Credential> find(@Param("id") UUID id);

    List<Credential> findByKey(@Param("key") String key);

    List<Credential> lock(@Param("id") UUID id);

    int insert(
            @Param("id") UUID id,
            @Param("key") String key,
            @Param("displayName") String displayName,
            @Param("actor") String actor);

    int insertSecret(
            @Param("id") UUID id,
            @Param("credentialId") UUID credentialId,
            @Param("revision") long revision,
            @Param("ciphertext") byte[] ciphertext,
            @Param("nonce") byte[] nonce,
            @Param("keyId") String keyId);

    int updateCurrentRevision(@Param("credentialId") UUID credentialId, @Param("revision") long revision);

    int retireOlderSecrets(@Param("credentialId") UUID credentialId, @Param("revision") long revision);

    List<CredentialSecret> findSecret(@Param("credentialId") UUID credentialId, @Param("revision") long revision);

    int setEnabled(@Param("id") UUID id, @Param("enabled") boolean enabled);

    Long references(@Param("credentialId") UUID credentialId);
}
