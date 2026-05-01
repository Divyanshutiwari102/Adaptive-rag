package com.ai.rag.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.List;
import java.util.function.Function;

@Component
@Slf4j
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    // FIX: was "${jwt.expiration-ms}" — that key doesn't exist in application.yml.
    // application.yml defines jwt.access-expiration-ms (900000 = 15 minutes).
    @Value("${jwt.access-expiration-ms}")
    private long expirationMs;

    @PostConstruct
    public void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret must be set via JWT_SECRET environment variable. Application cannot start.");
        }
        byte[] decoded;
        try {
            decoded = Decoders.BASE64.decode(secret);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "jwt.secret must be a valid Base64-encoded string. " + e.getMessage());
        }
        if (decoded.length < 32) {
            throw new IllegalStateException(
                    "jwt.secret decoded length is " + decoded.length +
                            " bytes. Minimum is 32 bytes (256 bits) for HS256.");
        }
        log.info("[Security] JWT secret validated: {} bytes", decoded.length);
    }

    // FIX: added — called by AuthController.buildTokenResponse()
    public String generateAccessToken(UserDetails userDetails) {
        return generateToken(userDetails);
    }

    public String generateToken(UserDetails userDetails) {
        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        return Jwts.builder()
                .setSubject(userDetails.getUsername())
                .claim("roles", roles)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            final String username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[Security] JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    // FIX: added — called by AuthController.buildTokenResponse()
    public long getAccessExpirationMs() { return expirationMs; }

    // kept for any other internal callers
    public long getExpirationMs() { return expirationMs; }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }
}