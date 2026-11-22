package top.yealike.pep.control.agents.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import top.yealike.pep.control.agents.domain.Credential;
import top.yealike.pep.control.agents.domain.CredentialSecret;
import top.yealike.pep.control.agents.mapper.CredentialMapper;

@Repository
public class CredentialStore {

    private final CredentialMapper mapper;

    public CredentialStore(CredentialMapper mapper) {
        this.mapper = mapper;
    }

    public List<Credential> list() {
        return mapper.list();
    }

    public Optional<Credential> find(UUID id) {
        return mapper.find(id).stream().findFirst();
    }

    public Optional<Credential> findByKey(String key) {
        return mapper.findByKey(key).stream().findFirst();
    }

    public Optional<Credential> lock(UUID id) {
        return mapper.lock(id).stream().findFirst();
    }

    public void insert(UUID id, String key, String displayName, String actor) {
        mapper.insert(id, key, displayName, actor);
    }

    public void insertSecret(
            UUID credentialId, long revision, byte[] ciphertext, byte[] nonce, String keyId, String actor) {
        mapper.insertSecret(UUID.randomUUID(), credentialId, revision, ciphertext, nonce, keyId);
        mapper.updateCurrentRevision(credentialId, revision);
        mapper.retireOlderSecrets(credentialId, revision);
    }

    public Optional<CredentialSecret> findSecret(UUID credentialId, long revision) {
        return mapper.findSecret(credentialId, revision).stream().findFirst();
    }

    public void setEnabled(UUID id, boolean enabled) {
        mapper.setEnabled(id, enabled);
    }

    public long references(UUID credentialId) {
        Long count = mapper.references(credentialId);
        return count == null ? 0 : count;
    }
}
