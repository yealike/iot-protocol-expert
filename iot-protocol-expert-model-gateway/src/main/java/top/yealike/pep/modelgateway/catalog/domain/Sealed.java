package top.yealike.pep.modelgateway.catalog.domain;

/** 供应商密钥密文及其随机 nonce。 */
public record Sealed(byte[] ciphertext, byte[] nonce, String keyId) {}
