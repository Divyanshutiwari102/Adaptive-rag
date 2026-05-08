package com.ai.rag.security;

import com.ai.rag.entity.RefreshToken;
import com.ai.rag.entity.User;
import com.ai.rag.exception.InvalidTokenException;
import com.ai.rag.repository.RefreshTokenRepository;
import com.google.common.hash.Hashing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service @RequiredArgsConstructor @Slf4j
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Create a new refresh token for the user. Revokes all existing tokens first (rotation). */
    @Transactional
    public String createRefreshToken(User user) {
        // Revoke all existing tokens (single-device model — swap for multi-device as needed)
        refreshTokenRepository.revokeAllByUser(user);

        byte[] bytes = new byte[48];
        SECURE_RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken rt = RefreshToken.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .expiresAt(LocalDateTime.now().plusNanos(refreshExpirationMs * 1_000_000L))
                .build();
        refreshTokenRepository.save(rt);
        return rawToken;
    }

    /** Validate refresh token and return the associated user. Rotates the token. */
    @Transactional
    public RefreshToken validateAndRotate(String rawToken) {
        String hash = hash(rawToken);
        RefreshToken rt = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not found or already used"));

        if (rt.isRevoked())
            throw new InvalidTokenException("Refresh token has been revoked");
        if (rt.isExpired())
            throw new InvalidTokenException("Refresh token has expired");

        // Revoke on use (rotation — the caller creates a new one)
        rt.setRevoked(true);
        refreshTokenRepository.save(rt);
        return rt;
    }

    @Transactional
    public void revokeAllForUser(User user) {
        refreshTokenRepository.revokeAllByUser(user);
    }

    /** Cleanup expired/revoked tokens daily. */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        int count = refreshTokenRepository.deleteExpiredAndRevoked(LocalDateTime.now());
        log.info("[RefreshToken] Purged {} expired/revoked tokens", count);
    }

    private String hash(String raw) {
        return Hashing.sha256().hashString(raw, StandardCharsets.UTF_8).toString();
    }
}
