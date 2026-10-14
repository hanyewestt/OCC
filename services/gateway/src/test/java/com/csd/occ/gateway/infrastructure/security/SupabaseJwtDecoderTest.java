package com.csd.occ.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.csd.occ.gateway.infrastructure.config.SupabaseJwtProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

class SupabaseJwtDecoderTest {
    private static final String ISSUER = "https://occ-test.supabase.co/auth/v1";
    private static final String AUDIENCE = "authenticated";
    private static ECKey signingKey;
    private static HttpServer jwksServer;
    private static URI jwksUri;

    @BeforeAll
    static void startLocalJwksServer() throws Exception {
        signingKey = new ECKeyGenerator(Curve.P_256).keyID("gateway-test-key").generate();
        byte[] body = signingKey.toPublicJWK().toJSONString().getBytes(StandardCharsets.UTF_8);
        String jwks = "{\"keys\":[" + new String(body, StandardCharsets.UTF_8) + "]}";
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            byte[] response = jwks.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        jwksServer.start();
        jwksUri = URI.create("http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopLocalJwksServer() {
        if (jwksServer != null) jwksServer.stop(0);
    }

    @Test
    void acceptsAValidEs256TokenFromTheConfiguredIssuerAndAudience() throws Exception {
        Jwt jwt = decoder().decode(token(ISSUER, AUDIENCE, Instant.now().plusSeconds(60), JWSAlgorithm.ES256));

        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(jwt.getAudience()).contains(AUDIENCE);
        assertThat(jwt.getSubject()).isEqualTo("synthetic-user");
    }

    @Test
    void rejectsTokenFromAnotherIssuer() throws Exception {
        String token = token("https://attacker.example/auth/v1", AUDIENCE,
                Instant.now().plusSeconds(60), JWSAlgorithm.ES256);

        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenWithAnotherAudience() throws Exception {
        String token = token(ISSUER, "another-audience", Instant.now().plusSeconds(60), JWSAlgorithm.ES256);

        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String token = token(ISSUER, AUDIENCE, Instant.now().minusSeconds(60), JWSAlgorithm.ES256);

        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsHmacAlgorithmEvenWhenTokenUsesTheConfiguredKeyId() throws Exception {
        String token = token(ISSUER, AUDIENCE, Instant.now().plusSeconds(60), JWSAlgorithm.HS256);

        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    private static org.springframework.security.oauth2.jwt.JwtDecoder decoder() {
        SupabaseJwtProperties properties = new SupabaseJwtProperties(
                URI.create("https://occ-test.supabase.co"), jwksUri, AUDIENCE,
                Duration.ofSeconds(1), List.of("ES256"));
        return new GatewaySecurityConfig().jwtDecoder(properties);
    }

    private static String token(String issuer, String audience, Instant expiresAt,
                                JWSAlgorithm algorithm) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("synthetic-user")
                .audience(audience)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(expiresAt))
                .claim("occ_role", "CONTROLLER")
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID("gateway-test-key").build(), claims);
        if (JWSAlgorithm.HS256.equals(algorithm)) jwt.sign(new MACSigner(new byte[32]));
        else jwt.sign(new ECDSASigner(signingKey));
        return jwt.serialize();
    }
}
