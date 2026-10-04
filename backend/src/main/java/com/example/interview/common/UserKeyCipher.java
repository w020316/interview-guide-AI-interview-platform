package com.example.interview.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 用户自持密钥的对称加密器（AES-256-GCM，第四批 · 竞品清单 #15）。
 *
 * <p><b>密钥派生</b>：加密主密钥由 {@code app.jwt.secret} 经 HMAC-SHA256 派生
 * （info = "user-ai-key-enc"），**不引入新的环境变量** —— 生产部署由代码仓库全权决定，
 * 加环境变量需要人工操作；派生密钥与 JWT 同属一个信任域，部署即生效。
 *
 * <p><b>威胁模型</b>：数据库泄露时攻击者拿到的是密文，解密需要应用进程里的 JWT_SECRET。
 * 这防不了「应用本身被攻破」，但能防「只拖库」——与自持 Key 的敏感等级相称
 * （其泄露后果 = 别人可以消耗你的 AI 额度，而非简历/账号数据泄露）。
 *
 * <p>输出格式：base64(iv[12] + ciphertext+tag)。
 */
@Component
public class UserKeyCipher {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String AES_ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] INFO = "user-ai-key-enc".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public UserKeyCipher(@Value("${app.jwt.secret}") String jwtSecret) {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            // 与 JwtUtil 相同的下限；不足时宁可启动失败，也不落库可被简单还原的密文
            throw new IllegalStateException("JWT secret 缺失或过短，无法安全加密用户自持密钥");
        }
        this.key = new SecretKeySpec(hmacSha256(jwtSecret.getBytes(StandardCharsets.UTF_8), INFO), "AES");
    }

    /** 加密：base64(iv + ciphertext+tag)。同明文两次加密结果不同（随机 IV） */
    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("用户密钥加密失败", e);
        }
    }

    /** 解密：密文损坏或主密钥变更时抛 IllegalStateException（调用方应提示用户重新配置） */
    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance(AES_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            byte[] pt = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("用户密钥解密失败（密文损坏或主密钥已变更）", e);
        }
    }

    private static byte[] hmacSha256(byte[] data, byte[] info) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(data, HMAC_ALGO));
            return mac.doFinal(info);
        } catch (Exception e) {
            throw new IllegalStateException("密钥派生失败", e);
        }
    }
}
