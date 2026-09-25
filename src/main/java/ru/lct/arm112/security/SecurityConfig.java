package ru.lct.arm112.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ru.lct.arm112.persistence.UserRepository;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * RBAC по префиксам: /admin — администратор, /teacher — преподаватель, учебные пути — обучающийся
 * и преподаватель (режим «делай, как я»). Роль — клейм role в JWT.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${arm112.swagger.public:true}") boolean swaggerPublic) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh",
                                    "/api/v1/auth/logout", "/actuator/health", "/ws/v1/**").permitAll();
                    if (swaggerPublic) {
                        auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/openapi.yaml", "/webjars/**").permitAll();
                    } else {
                        auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/openapi.yaml", "/webjars/**", "/actuator/prometheus").hasRole("ADMIN");
                    }
                    auth.requestMatchers("/api/v1/auth/**").authenticated()
                            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                            .requestMatchers("/api/v1/teacher/**").hasRole("TEACHER")
                            .requestMatchers("/api/v1/**").hasAnyRole("TRAINEE", "TEACHER")
                            .anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter())))
                .build();
    }

    private JwtAuthenticationConverter roleConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::authorities);
        return converter;
    }

    private static Collection<GrantedAuthority> authorities(Jwt jwt) {
        String role = jwt.getClaimAsString("role");
        return role == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Bean
    SecretKey jwtSecretKey(@Value("${arm112.jwt-secret}") String secret) {
        byte[] source = secret.getBytes(StandardCharsets.UTF_8);
        byte[] key = Arrays.copyOf(source, 32);
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, UserRepository users) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> liveAccount = jwt -> {
            try {
                UUID userId = UUID.fromString(jwt.getSubject());
                String tokenRole = jwt.getClaimAsString("role");
                Number tokenVersion = jwt.getClaim("auth_version");
                boolean valid = users.findById(userId)
                        .filter(UserRepository.AppUser::active)
                        .map(user -> user.role().name().equals(tokenRole) && tokenVersion != null
                                && user.authVersion() == tokenVersion.longValue())
                        .orElse(false);
                return valid ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "Учётная запись заблокирована или её роль изменилась", null));
            } catch (RuntimeException exception) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "Некорректный идентификатор пользователя", null));
            }
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), liveAccount));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${arm112.allowed-origins}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key",
                "X-Request-Id", "X-Contract-Version"));
        config.setExposedHeaders(List.of("X-Request-Id", "ETag", "Content-Disposition"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
