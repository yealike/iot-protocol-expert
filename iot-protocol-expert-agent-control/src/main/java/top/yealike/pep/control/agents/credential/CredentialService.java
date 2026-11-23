package top.yealike.pep.control.agents.credential;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.control.agents.credential.domain.Encrypted;
import top.yealike.pep.control.agents.domain.Credential;
import top.yealike.pep.control.agents.domain.CredentialSecret;
import top.yealike.pep.control.agents.domain.CredentialSummary;
import top.yealike.pep.control.agents.store.CredentialStore;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.ErrorCodes;
import top.yealike.pep.runtime.contract.UserIdentity;

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
        rotateInternal(require(id), secret);
        return require(id);
    }

    @Transactional
    public Credential rotate(UserIdentity actor, UUID id, String secret) {
        Credential credential = store.lock(id).orElseThrow(() -> AiExceptions.notFound("凭据不存在"));
        rotateInternal(credential, secret);
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
        if (!credential.enabled()) {
            throw AiExceptions.unavailable("凭据不存在或已停用");
        }
        long revision = secretRevision > 0 ? secretRevision : credential.currentSecretRevision();
        CredentialSecret secret = store.findSecret(id, revision).orElseThrow(() -> AiExceptions.unavailable("凭据版本不存在"));
        return cipher.decrypt(id.toString(), revision, secret.ciphertext(), secret.nonce());
    }

    public String keyId() {
        return cipher.keyId();
    }

    public CredentialSummary summary(Credential credential) {
        return CredentialSummary.from(credential, keyId());
    }

    private void rotateInternal(Credential credential, String secret) {
        long revision = credential.currentSecretRevision() + 1;
        Encrypted encrypted = cipher.encrypt(credential.id().toString(), revision, secret);
        store.insertSecret(
                credential.id(), revision, encrypted.ciphertext(), encrypted.nonce(), encrypted.keyId(), "system");
    }

    public static void requireKey(String key) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9-]{1,39}")) {
            throw AiExceptions.badRequest("凭据 key 只允许 2-40 位小写字母、数字和横线");
        }
    }
}
