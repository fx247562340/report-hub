package com.reporthub.security;

import com.reporthub.config.ReportHubProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

@Service
public class JwtService {
    private final SecretKey key;
    private final long ttlHours;

    public JwtService(ReportHubProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        this.ttlHours = props.getJwt().getTtlHours();
    }

    public String issue(String userId, String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claims(Map.of("username", username, "role", role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlHours * 3600)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
