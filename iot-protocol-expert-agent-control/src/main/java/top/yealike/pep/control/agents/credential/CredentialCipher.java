package top.yealike.pep.control.agents.credential;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.yealike.pep.control.config.PlatformProperties;
import top.yealike.pep.control.support.AiExceptions;

/**
 * AES-256-GCM 凭据加密。主密钥只从环境变量或挂载秘密文件读取，绝不与密文同库。
 *
 * <p>本地演示若未显式配置主密钥，允许从既有 MCP 共享密钥派生一个稳定密钥并打印告警；
 * 生产环境必须显式配置。
 */
@Component
public class CredentialCipher {

    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final PlatformProperties properties;
    private volatile SecretKeySpec key;
    private volatile String keyId;

    public CredentialCipher(PlatformProperties properties) {
        this.properties = properties;
    }

    public String keyId() {
        key();
        return keyId;
    }

    public Encrypted encrypt(String credentialId, long revision, String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw AiExceptions.badRequest("凭据内容不能为空");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(credentialId, revision));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(ciphertext, nonce, keyId());
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("凭据加密失败", exception);
        }
    }

    public String decrypt(String credentialId, long revision, byte[] ciphertext, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(credentialId, revision));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException exception) {
            throw AiExceptions.unavailable("凭据无法解密，请检查主密钥");
        }
    }

    private byte[] aad(String credentialId, long revision) {
        return (credentialId + ":" + revision).getBytes(StandardCharsets.UTF_8);
    }

    private SecretKeySpec key() {
        SecretKeySpec current = key;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (key == null) {
                String material = resolveMaterial();
                byte[] digest = sha256(material);
                key = new SecretKeySpec(digest, "AES");
                keyId = HexFormat.of().formatHex(digest).substring(0, 16);
            }
            return key;
        }
    }

    private String resolveMaterial() {
        String explicit = properties.security().masterKey();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String file = properties.security().masterKeyFile();
        if (file != null && !file.isBlank()) {
            try {
                String content =
                        Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
                if (!content.isBlank()) {
                    return content;
                }
            } catch (java.io.IOException exception) {
                throw AiExceptions.unavailable("凭据主密钥文件不可读");
            }
        }
        String fallback = System.getenv("IPE_AI_MCP_TOKEN");
        if (fallback != null && !fallback.isBlank()) {
            log.warn("未配置 IPE_CREDENTIAL_MASTER_KEY，使用既有共享密钥派生本地凭据密钥；生产环境必须显式配置");
            return "local-credential:" + fallback;
        }
        throw AiExceptions.unavailable("未配置凭据主密钥，无法保存或读取模型密钥");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Encrypted(byte[] ciphertext, byte[] nonce, String keyId) {
        public String masked() {
            return "******";
        }

        @Override
        public String toString() {
            return "Encrypted{ciphertext=" + ciphertext.length + " bytes, keyId=" + keyId + "}";
        }
    }
}
