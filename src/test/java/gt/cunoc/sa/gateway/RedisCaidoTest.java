package gt.cunoc.sa.gateway;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Sin Redis la API sigue respondiendo: la revocación y el limitador dejan pasar (README,
 * "Sesiones y límites con Redis"). Redis apunta a un puerto cerrado.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.data.redis.port=1", "management.health.redis.enabled=false"})
class RedisCaidoTest extends PruebaConServicioFalso {

    @Autowired
    WebTestClient cliente;

    @Test
    void unTokenValidoPasaSinRevisarLaRevocacion() {
        String token = Tokens.firmar(Tokens.claims(UUID.randomUUID().toString(), "CUSTOMER"));
        cliente.get().uri("/api/perfil").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isOk();
    }

    @Test
    void laFirmaSeSigueExigiendo() {
        cliente.get().uri("/api/perfil").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void elLimitadorDejaPasarElLogin() {
        for (int i = 1; i <= 7; i++) {
            cliente.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                    .expectStatus().isOk();
        }
    }
}
