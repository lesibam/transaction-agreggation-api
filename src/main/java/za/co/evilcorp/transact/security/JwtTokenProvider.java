package za.co.evilcorp.transact.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Slf4j
@Component
public class JwtTokenProvider {

    private static final long TOKEN_VALIDITY_MILLIS = 60 * 60 * 1000L;

    private final SecretKey signingKey;
    private final String issuer;
    private final String audience;

    public JwtTokenProvider(
            @Value("${app.security.jwt-secret:}") String jwtSecret,
            @Value("${app.security.jwt-issuer:transact}") String issuer,
            @Value("${app.security.jwt-audience:transact-api}") String audience) {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "Missing required configuration property 'app.security.jwt-secret'. "
                            + "Set the APP_SECURITY_JWT_SECRET environment variable; "
                            + "no default value is provided by design.");
        }
        byte[] secretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "Configuration property 'app.security.jwt-secret' must be at least 32 bytes "
                            + "(256 bits) to support the HMAC-SHA signing algorithm.");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.issuer = issuer;
        this.audience = audience;
    }

    public String createToken(String customerId, String tenantId, List<String> roles) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + TOKEN_VALIDITY_MILLIS);
        return Jwts.builder()
                .subject(customerId)
                .claim("tenantId", tenantId)
                .claim("roles", roles == null ? List.of() : roles)
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Rejected JWT: {}", e.getMessage());
            return false;
        }
    }

    public Claims getClaims(String token) {
        return parseClaims(token);
    }

    private Claims parseClaims(String token) {
        return newParser().parseSignedClaims(token).getPayload();
    }

    private JwtParser newParser() {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .requireAudience(audience)
                .build();
    }
}
