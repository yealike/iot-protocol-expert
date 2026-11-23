package top.yealike.pep.modelgateway.catalog.domain;

import java.util.UUID;

/** 模型供应商目录行；上游密钥只保存 AES-GCM 密文。 */
public record Provider(
        UUID id,
        String code,
        String name,
        String baseUrl,
        byte[] credentialCipher,
        byte[] credentialNonce,
        String state) {}
