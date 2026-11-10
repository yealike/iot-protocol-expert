package top.yealike.ipe.protocol.codec;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 云快充 1.6 帧体 3DES 加密。规范：加密标志 0x01 表示 3DES。
 *
 * <p>实现采用 DESede/ECB/NoPadding，密钥 16 字节自动扩展为 24 字节（K3 = K1），
 * 加密前补 0 至 8 字节对齐（3DES 块长 8）并由 16 字节对齐约束到 16。
 * 解密后保留原始字节，由各帧解码器按字段定义消费，尾部补零不影响解析。
 */
public final class TripleDes {

    private TripleDes() {
    }

    public static byte[] expandKey(byte[] key) {
        if (key == null || key.length == 0) {
            throw new ProtocolException("3DES 密钥不能为空");
        }
        if (key.length == 24) {
            return key;
        }
        if (key.length == 16) {
            byte[] k = new byte[24];
            System.arraycopy(key, 0, k, 0, 16);
            System.arraycopy(key, 0, k, 16, 8);
            return k;
        }
        throw new ProtocolException("3DES 密钥长度必须为 16 或 24 字节，实际 " + key.length);
    }

    public static byte[] encrypt(byte[] plain, byte[] key) {
        try {
            byte[] padded = pad(plain);
            Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(expandKey(key), "DESede"));
            return cipher.doFinal(padded);
        } catch (ProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new ProtocolException("3DES 加密失败: " + e.getMessage(), e);
        }
    }

    public static byte[] decrypt(byte[] cipherText, byte[] key) {
        if (cipherText.length % 8 != 0) {
            throw new ProtocolException("加密数据长度 " + cipherText.length + " 不是 8 的倍数");
        }
        try {
            Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(expandKey(key), "DESede"));
            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            throw new ProtocolException("3DES 解密失败: " + e.getMessage(), e);
        }
    }

    public static byte[] keyFromHex(String hex) {
        if (hex == null || hex.isBlank()) {
            throw new ProtocolException("3DES 密钥未配置");
        }
        String cleaned = hex.replaceAll("\\s+", "");
        if (!cleaned.matches("(?i)[0-9a-f]+") || cleaned.length() % 2 != 0) {
            throw new ProtocolException("3DES 密钥必须是偶数长度十六进制");
        }
        return java.util.HexFormat.of().parseHex(cleaned);
    }

    public static String keyToHex(byte[] key) {
        return java.util.HexFormat.of().withUpperCase().formatHex(key);
    }

    public static String text(byte[] data) {
        return new String(data, StandardCharsets.ISO_8859_1);
    }

    private static byte[] pad(byte[] plain) {
        int block = 8;
        int len = plain.length == 0 ? block : ((plain.length + block - 1) / block) * block;
        return Arrays.copyOf(plain, len);
    }
}