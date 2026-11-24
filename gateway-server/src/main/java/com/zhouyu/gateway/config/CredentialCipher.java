package com.zhouyu.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class CredentialCipher {
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public CredentialCipher(@Value("${GATEWAY_CONFIG_KEY:}") String configured,
                            @Value("${gateway.admin.jwt-secret}") String jwtSecret) {
        try {
            byte[] bytes;
            if (!configured.isBlank()) {
                bytes = Base64.getDecoder().decode(configured);
                if (bytes.length != 32) throw new IllegalArgumentException("GATEWAY_CONFIG_KEY must decode to 32 bytes");
            } else {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                bytes = mac.doFinal("llm-gateway/config-encryption/v1".getBytes(StandardCharsets.UTF_8));
            }
            key = new SecretKeySpec(bytes, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot initialize credential encryption", e);
        }
    }

    public byte[] encrypt(String secret, String scope) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] encrypted = apply(Cipher.ENCRYPT_MODE, nonce, secret.getBytes(StandardCharsets.UTF_8), scope);
        return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
    }

    public String decrypt(byte[] encrypted, String scope) {
        ByteBuffer data = ByteBuffer.wrap(encrypted);
        byte[] nonce = new byte[12];
        data.get(nonce);
        byte[] payload = new byte[data.remaining()];
        data.get(payload);
        return new String(apply(Cipher.DECRYPT_MODE, nonce, payload, scope), StandardCharsets.UTF_8);
    }

    private byte[] apply(int mode, byte[] nonce, byte[] input, String scope) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot process encrypted gateway credential", e);
        }
    }
}
