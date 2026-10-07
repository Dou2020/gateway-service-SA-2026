package gt.cunoc.sa.gateway;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.nimbusds.jwt.JWTClaimsSet;

/**
 * Las llaves auth:* las escribe identity-service; aquí se escriben a mano con el mismo formato
 * (README, "Revocación de JWT").
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(ContenedorRedis.class)
class RevocacionTest extends PruebaConServicioFalso {

    @Autowired
    WebTestClient cliente;
    @Autowired
    ReactiveStringRedisTemplate redis;

    @Test
    void unTokenConElJtiRevocadoDa401() {
        JWTClaimsSet.Builder claims = Tokens.claims(UUID.randomUUID().toString(), "CUSTOMER");
        String token = Tokens.firmar(claims);
        pedir(token).expectStatus().isOk();

        redis.opsForValue().set("auth:revocado:" + claims.build().getJWTID(), "1", Duration.ofHours(8)).block();

        pedir(token).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.detail").isEqualTo("Tu sesión se cerró. Inicia sesión de nuevo");
    }

    @Test
    void unCorteDespuesDelIatInvalidaTodosLosTokensAnterioresDelUsuario() {
        String usuario = UUID.randomUUID().toString();
        Instant emitido = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(10);
        String anterior = Tokens.firmar(Tokens.claims(usuario, "CASHIER", emitido));
        String otroAnterior = Tokens.firmar(Tokens.claims(usuario, "CASHIER", emitido.minusSeconds(60)));

        cortar(usuario, emitido.plusSeconds(1));

        pedir(anterior).expectStatus().isUnauthorized();
        pedir(otroAnterior).expectStatus().isUnauthorized();
        // El token de otro usuario no se ve afectado.
        pedir(Tokens.firmar(Tokens.claims(UUID.randomUUID().toString(), "CASHIER", emitido))).expectStatus().isOk();
    }

    /** iat tiene precisión de segundos: un login en el mismo segundo del corte tiene que servir. */
    @Test
    void unTokenEmitidoEnElMismoSegundoDelCorteSigueValiendo() {
        String usuario = UUID.randomUUID().toString();
        Instant corte = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(5);
        cortar(usuario, corte);

        pedir(Tokens.firmar(Tokens.claims(usuario, "CUSTOMER", corte))).expectStatus().isOk();
        pedir(Tokens.firmar(Tokens.claims(usuario, "CUSTOMER", corte.plusSeconds(3)))).expectStatus().isOk();
    }

    private void cortar(String usuario, Instant corte) {
        redis.opsForValue().set("auth:valido-desde:" + usuario, Long.toString(corte.getEpochSecond()),
                Duration.ofHours(8)).block();
    }

    private WebTestClient.ResponseSpec pedir(String token) {
        return cliente.get().uri("/api/perfil").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }
}
