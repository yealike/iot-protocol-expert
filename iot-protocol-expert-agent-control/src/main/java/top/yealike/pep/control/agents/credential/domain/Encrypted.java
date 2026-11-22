package top.yealike.pep.control.agents.credential.domain;

public record Encrypted(byte[] ciphertext, byte[] nonce, String keyId) {
    public String masked() {
        return "******";
    }

    @Override
    public String toString() {
        return "Encrypted{ciphertext=" + ciphertext.length + " bytes, keyId=" + keyId + "}";
    }
}
