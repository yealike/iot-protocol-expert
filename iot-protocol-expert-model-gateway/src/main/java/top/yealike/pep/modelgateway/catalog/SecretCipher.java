package top.yealike.pep.modelgateway.catalog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import top.yealike.pep.modelgateway.config.GatewayProperties;
import top.yealike.pep.modelgateway.support.ApiException;

/** 供应商密钥只以 AES-256-GCM 密文保存，主密钥来自环境变量或挂载文件。 */
@Component
public class SecretCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final GatewayProperties properties;
    private volatile SecretKeySpec key;
    private volatile String keyId;

    public SecretCipher(GatewayProperties properties) {
        this.properties = properties;
    }

    public Sealed seal(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw ApiException.badRequest("MODEL_REQUEST_INVALID", "供应商密钥不能为空");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            return new Sealed(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), nonce, keyId());
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("供应商密钥加密失败", exception);
        }
    }

    public String open(byte[] ciphertext, byte[] nonce) {
        if (ciphertext == null || nonce == null) {
            throw ApiException.unavailable("MODEL_UNAVAILABLE", "模型供应商未配置密钥");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException exception) {
            throw ApiException.unavailable("MODEL_UNAVAILABLE", "模型供应商密钥无法解密");
        }
    }

    private SecretKeySpec key() {
        SecretKeySpec current = key;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (key == null) {
                String material = properties.getCredentialMasterKey();
                if (material == null || material.isBlank()) {
                    String file = properties.getCredentialMasterKeyFile();
                    if (file != null && !file.isBlank()) {
                        try {
                            material = Files.readString(Path.of(file), StandardCharsets.UTF_8)
                                    .strip();
                        } catch (java.io.IOException exception) {
                            throw ApiException.unavailable("MODEL_UNAVAILABLE", "模型密钥主密钥文件不可读");
                        }
                    }
                }
                if (material == null || material.isBlank()) {
                    throw ApiException.unavailable("MODEL_UNAVAILABLE", "未配置模型网关密钥主密钥");
                }
                byte[] digest = sha256(material);
                key = new SecretKeySpec(digest, "AES");
                keyId = HexFormat.of().formatHex(digest).substring(0, 16);
            }
            return key;
        }
    }

    private String keyId() {
        key();
        return keyId;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Sealed(byte[] ciphertext, byte[] nonce, String keyId) {}
}
