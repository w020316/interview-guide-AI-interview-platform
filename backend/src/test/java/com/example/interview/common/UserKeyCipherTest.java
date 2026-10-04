package com.example.interview.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用户自持密钥加密器测试：加解密往返 / 随机 IV / GCM 完整性 / 主密钥隔离。
 *
 * <p>为什么这些很重要：密文落库，解密只在内存瞬间发生——加密器一旦有「同明文同密文」
 * 或「跨主密钥可解」的问题，用户自持 Key 就形同明文存储。
 */
@DisplayName("UserKeyCipher 用户自持密钥加密器")
class UserKeyCipherTest {

    private static final String MASTER_A = "0123456789abcdef0123456789abcdef";
    private static final String MASTER_B = "fedcba9876543210fedcba9876543210";
    private static final String KEY = "sk-test-abcdefghij1234567890";

    private final UserKeyCipher cipher = new UserKeyCipher(MASTER_A);

    @Test
    @DisplayName("加解密往返一致")
    void roundTrip() {
        assertThat(cipher.decrypt(cipher.encrypt(KEY))).isEqualTo(KEY);
    }

    @Test
    @DisplayName("同明文两次加密密文不同（随机 IV），但都能解回原值")
    void randomIv() {
        String c1 = cipher.encrypt(KEY);
        String c2 = cipher.encrypt(KEY);
        assertThat(c1).isNotEqualTo(c2);
        assertThat(cipher.decrypt(c1)).isEqualTo(KEY);
        assertThat(cipher.decrypt(c2)).isEqualTo(KEY);
    }

    @Test
    @DisplayName("密文被篡改 → GCM 完整性校验失败（不能静默解出错误内容）")
    void tamperDetected() {
        String c = cipher.encrypt(KEY);
        byte[] bytes = Base64.getDecoder().decode(c);
        bytes[bytes.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("主密钥不同 → 解密失败（防跨环境串用密文）")
    void differentMasterKey() {
        UserKeyCipher other = new UserKeyCipher(MASTER_B);
        String c = cipher.encrypt(KEY);
        assertThatThrownBy(() -> other.decrypt(c)).isInstanceOf(IllegalStateException.class);
    }
}
