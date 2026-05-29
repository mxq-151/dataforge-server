package com.bigdata.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtTokenUtils {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(Long userId, String username, String role, Long tenantId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", role);
        if (tenantId != null) {
            claims.put("tenantId", tenantId);
        }

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public Claims getTokenClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = getTokenClaims(token);
        return claims.get("userId", Long.class);
    }

    public String getUsernameFromToken(String token) {
        Claims claims = getTokenClaims(token);
        return claims.get("username", String.class);
    }

    public String getRoleFromToken(String token) {
        Claims claims = getTokenClaims(token);
        return claims.get("role", String.class);
    }

    public Long getTenantIdFromToken(String token) {
        Claims claims = getTokenClaims(token);
        Object tenantId = claims.get("tenantId");
        if (tenantId == null) {
            return null;
        }
        if (tenantId instanceof Long) {
            return (Long) tenantId;
        }
        if (tenantId instanceof Integer) {
            return ((Integer) tenantId).longValue();
        }
        return Long.valueOf(tenantId.toString());
    }

    public boolean validateToken(String token) {
        try {
            getTokenClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
