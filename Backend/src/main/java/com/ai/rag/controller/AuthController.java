package com.ai.rag.controller;

import com.ai.rag.dto.*;
import com.ai.rag.entity.RefreshToken;
import com.ai.rag.entity.User;
import com.ai.rag.repository.UserRepository;
import com.ai.rag.security.JwtUtil;
import com.ai.rag.security.RefreshTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

// FIX: removed unused import com.ai.rag.exception.InvalidTokenException

@RestController @RequestMapping("/auth") @RequiredArgsConstructor @Slf4j
public class AuthController {

    private final AuthenticationManager authManager;
    private final JwtUtil               jwtUtil;
    private final RefreshTokenService   refreshTokenService;
    private final UserRepository        userRepository;
    private final PasswordEncoder       passwordEncoder;

    @PostMapping("/signup")
    public ResponseEntity<TokenResponse> signup(@Valid @RequestBody SignupRequest req) {
        if (userRepository.existsByEmail(req.getEmail()))
            throw new IllegalArgumentException("Email already registered: " + req.getEmail());

        User user = User.builder()
                .name(req.getName())
                .email(req.getEmail().toLowerCase())
                .password(passwordEncoder.encode(req.getPassword()))
                .role("USER")
                .build();
        userRepository.save(user);
        log.info("[Auth] New user registered: {}", user.getEmail());

        return ResponseEntity.ok(buildTokenResponse(user));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        Authentication auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        req.getEmail().toLowerCase(), req.getPassword()));
        User user = (User) auth.getPrincipal();
        log.info("[Auth] Login: {}", user.getEmail());
        return ResponseEntity.ok(buildTokenResponse(user));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        RefreshToken rt = refreshTokenService.validateAndRotate(req.getRefreshToken());
        User user = rt.getUser();
        log.info("[Auth] Token refreshed for: {}", user.getEmail());
        return ResponseEntity.ok(buildTokenResponse(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            UserDetails userDetails) {
        userRepository.findByEmail(userDetails.getUsername()).ifPresent(user -> {
            refreshTokenService.revokeAllForUser(user);
            log.info("[Auth] Logout + tokens revoked: {}", user.getEmail());
        });
        return ResponseEntity.noContent().build();
    }

    private TokenResponse buildTokenResponse(User user) {
        String accessToken  = jwtUtil.generateAccessToken(user);   // now exists in JwtUtil
        String refreshToken = refreshTokenService.createRefreshToken(user);
        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .accessExpiresInMs(jwtUtil.getAccessExpirationMs()) // now exists in JwtUtil
                .tokenType("Bearer")
                .build();
    }
}