package top.yealike.pep.control.agents.domain;

/** 控制面领域记录。 */
public record CredentialSecret(byte[] ciphertext, byte[] nonce, String keyId, String state) {}
