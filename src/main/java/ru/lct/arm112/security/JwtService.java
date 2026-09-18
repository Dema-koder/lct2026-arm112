package ru.lct.arm112.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final Duration tokenTtl;

    public JwtService(JwtEncoder encoder,
                      @Value("${arm112.token-ttl:PT8H}") Duration tokenTtl) {
        this.encoder = encoder;
        this.tokenTtl = tokenTtl;
    }

    public IssuedToken issue(UUID userId, String username) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenTtl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("arm112-local")
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("preferred_username", username)
                .claim("role", "TRAINEE")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {}
}
