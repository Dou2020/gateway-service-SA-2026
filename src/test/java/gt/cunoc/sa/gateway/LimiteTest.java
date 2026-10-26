package gt.cunoc.sa.gateway;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** README, "Rate limiting". */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(ContenedorRedis.class)
class LimiteTest extends PruebaConServicioFalso {

    @Autowired
    WebTestClient cliente;
    @Autowired
    ReactiveStringRedisTemplate redis;

    /**
     * Las otras pruebas del mismo contexto comparten este Redis y la IP 127.0.0.1: antes, para no heredar sus
     * logins; después, para no dejarles vacío el bucket de identity-publico (su registro daría 429). Surefire
     * ordena las clases según el sistema de archivos, así que no se puede contar con correr al final.
     */
    @BeforeEach
    @AfterEach
    void vaciarLosContadores() {
        redis.execute(conexion -> conexion.serverCommands().flushAll()).blockLast();
    }

    @Test
    void elSextoLoginEnUnMinutoDesdeLaMismaIpDa429ComoProblemDetail() {
        for (int i = 1; i <= 5; i++) {
            login().expectStatus().isOk();
        }
        login().expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                // Cuenta tokens, no intentos (cada login cuesta 60 y se reponen 5 por segundo).
                .expectHeader().exists("X-RateLimit-Remaining")
                .expectBody().jsonPath("$.detail").isEqualTo("Demasiados intentos, espera un momento");
    }

    @Test
    void elLimiteDelLoginNoFrenaAUnUsuarioConToken() {
        for (int i = 1; i <= 6; i++) {
            login();
        }
        String token = Tokens.firmar(Tokens.claims(UUID.randomUUID().toString(), "CUSTOMER"));
        cliente.get().uri("/api/perfil").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-RateLimit-Remaining");
    }

    private WebTestClient.ResponseSpec login() {
        return cliente.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"correo\":\"a@b.gt\",\"contrasena\":\"x\"}").exchange();
    }
}
