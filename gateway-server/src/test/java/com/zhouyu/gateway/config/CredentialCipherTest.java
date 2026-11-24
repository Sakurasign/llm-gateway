package com.zhouyu.gateway.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialCipherTest {
    @Test
    void ciphertextIsRandomAndCannotBeMovedBetweenAccounts() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        String secret = "test-credential";
        var first = new CredentialCipher(key, "unused");
        byte[] ciphertext = first.encrypt(secret, "account:one");
        assertThat(ciphertext).isNotEqualTo(first.encrypt(secret, "account:one"));
        assertThat(new String(ciphertext, StandardCharsets.UTF_8)).doesNotContain(secret);
        assertThat(new CredentialCipher(key, "different-jwt").decrypt(ciphertext, "account:one")).isEqualTo(secret);
        assertThatThrownBy(() -> first.decrypt(ciphertext, "account:two")).isInstanceOf(IllegalStateException.class);
    }
}
