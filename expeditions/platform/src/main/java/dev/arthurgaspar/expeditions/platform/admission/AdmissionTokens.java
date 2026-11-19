package dev.arthurgaspar.expeditions.platform.admission;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Proof that the queue let an adventurer through. The queue signs, reservation verifies with the same key,
 * so reservation never has to ask the queue (or Redis) whether someone was admitted.
 * <p>
 * Format: {@code base64url(expeditionId:expiresEpochSecond:adventurerId) . base64url(HMAC-SHA256)}.
 */
public final class AdmissionTokens {

    public record Admission(long expeditionId, String adventurerId, Instant expiresAt) {}

    public static class InvalidAdmission extends RuntimeException {
        public InvalidAdmission(String message) {
            super(message);
        }
    }

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    public AdmissionTokens(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("The admission secret needs at least 32 bytes");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public String issue(Admission admission) {
        String body = admission.expeditionId() + ":" + admission.expiresAt().getEpochSecond() + ":" + admission.adventurerId();
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(bodyBytes) + "." + ENCODER.encodeToString(mac(bodyBytes));
    }

    public Admission verify(String token, Instant now) {
        if (token == null || token.isBlank()) {
            throw new InvalidAdmission("Admission token is missing");
        }
        int dot = token.indexOf('.');
        if (dot < 0) {
            throw new InvalidAdmission("Admission token is malformed");
        }
        byte[] body;
        byte[] signature;
        try {
            body = DECODER.decode(token.substring(0, dot));
            signature = DECODER.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new InvalidAdmission("Admission token is malformed");
        }
        if (!MessageDigest.isEqual(mac(body), signature)) {
            throw new InvalidAdmission("Admission token signature does not match");
        }
        // the adventurer id goes last so it may contain colons
        String[] parts = new String(body, StandardCharsets.UTF_8).split(":", 3);
        if (parts.length != 3) {
            throw new InvalidAdmission("Admission token is malformed");
        }
        Admission admission;
        try {
            admission = new Admission(Long.parseLong(parts[0]), parts[2], Instant.ofEpochSecond(Long.parseLong(parts[1])));
        } catch (NumberFormatException e) {
            throw new InvalidAdmission("Admission token is malformed");
        }
        if (!now.isBefore(admission.expiresAt())) {
            throw new InvalidAdmission("Admission expired; join the queue again");
        }
        return admission;
    }

    private byte[] mac(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
