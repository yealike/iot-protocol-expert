package top.yealike.ipe.ai.platform.credential;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ai.contract.ErrorCodes;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.Credential;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.CredentialSecret;
import top.yealike.ipe.ai.platform.store.CredentialStore;
import top.yealike.ipe.ai.support.AiExceptions;

/** 凭据只写不读：页面只返回 masked、keyId 与 secretRevision。 */
@Service
public class CredentialService {

    private final CredentialStore store;
    private final CredentialCipher cipher;

    public CredentialService(CredentialStore store, CredentialCipher cipher) {
        this.store = store;
        this.cipher = cipher;
    }

    public List<Credential> list() {
        return store.list();
    }

    public Credential require(UUID id) {
        return store.find(id).orElseThrow(() -> AiExceptions.notFound("凭据不存在"));
    }

    @Transactional
    public Credential create(UserIdentity actor, String key, String displayName, String secret) {
        requireKey(key);
        if (store.findByKey(key).isPresent()) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "凭据 key 已存在");
        }
        UUID id = UUID.randomUUID();
        store.insert(id, key, displayName, actor.username());
        rotateInternal(id, secret);
        return require(id);
    }

    @Transactional
    public Credential rotate(UserIdentity actor, UUID id, String secret) {
        Credential credential = require(id);
        rotateInternal(credential.id(), secret);
        return require(id);
    }

    public String resolve(String key, long secretRevision) {
        Credential credential = store.findByKey(key)
                .filter(Credential::enabled)
                .orElseThrow(() -> AiExceptions.unavailable("凭据不存在或已停用"));
        long revision = secretRevision > 0 ? secretRevision : credential.currentSecretRevision();
        CredentialSecret secret =
                store.findSecret(credential.id(), revision).orElseThrow(() -> AiExceptions.unavailable("凭据版本不存在"));
        return cipher.decrypt(credential.id().toString(), revision, secret.ciphertext(), secret.nonce());
    }

    public String resolve(UUID id, long secretRevision) {
        Credential credential = require(id);
        long revision = secretRevision > 0 ? secretRevision : credential.currentSecretRevision();
        CredentialSecret secret = store.findSecret(id, revision).orElseThrow(() -> AiExceptions.unavailable("凭据版本不存在"));
        return cipher.decrypt(id.toString(), revision, secret.ciphertext(), secret.nonce());
    }

    public String keyId() {
        return cipher.keyId();
    }

    public void disable(UUID id) {
        Credential credential = require(id);
        if (store.references(id) > 0) {
            throw AiExceptions.conflict(ErrorCodes.COMPONENT_IN_USE, "凭据仍被模型或 MCP 发布引用");
        }
        store.setEnabled(id, false);
    }

    public java.util.Map<String, Object> summary(Credential credential) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", credential.id());
        data.put("key", credential.key());
        data.put("displayName", credential.displayName());
        data.put("currentSecretRevision", credential.currentSecretRevision());
        data.put("enabled", credential.enabled());
        data.put("revision", credential.revision());
        data.put("masked", "******");
        data.put("keyId", keyId());
        data.put("createdAt", credential.createdAt());
        data.put("updatedAt", credential.updatedAt());
        return data;
    }

    private void rotateInternal(UUID id, String secret) {
        Credential credential = require(id);
        long revision = credential.currentSecretRevision() + 1;
        CredentialCipher.Encrypted encrypted = cipher.encrypt(id.toString(), revision, secret);
        store.insertSecret(id, revision, encrypted.ciphertext(), encrypted.nonce(), encrypted.keyId(), "system");
    }

    public static void requireKey(String key) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9-]{1,39}")) {
            throw AiExceptions.badRequest("凭据 key 只允许 2-40 位小写字母、数字和横线");
        }
    }
}
