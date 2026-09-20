package ru.lct.arm112.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import ru.lct.arm112.persistence.UserRepository.AppUser;

import java.time.Duration;
import java.time.Instant;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final Duration tokenTtl;

    public JwtService(JwtEncoder encoder,
                      @Value("${arm112.token-ttl:PT8H}") Duration tokenTtl) {
        this.encoder = encoder;
        this.tokenTtl = tokenTtl;
    }

    public IssuedToken issue(AppUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenTtl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("arm112-local")
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("preferred_username", user.login())
                .claim("name", user.displayName())
                .claim("role", user.role().name())
                .claim("auth_version", user.authVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {}
}
