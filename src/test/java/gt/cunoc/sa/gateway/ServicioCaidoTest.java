package gt.cunoc.sa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Un servicio caído se ve como 503 con ProblemDetail, no como un 500 genérico. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(ContenedorRedis.class)
class ServicioCaidoTest {

    @Autowired
    WebTestClient cliente;

    /** Un puerto que estuvo libre: nadie escucha ahí, la conexión se rechaza. */
    @DynamicPropertySource
    static void identityApagado(DynamicPropertyRegistry propiedades) throws IOException {
        int puerto;
        try (ServerSocket socket = new ServerSocket(0)) {
            puerto = socket.getLocalPort();
        }
        propiedades.add("IDENTITY_URL", () -> "http://127.0.0.1:" + puerto);
    }

    @Test
    void unServicioQueNoRespondeDa503() {
        String token = Tokens.firmar(Tokens.claims(UUID.randomUUID().toString(), "CUSTOMER"));
        cliente.get().uri("/api/perfil").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").value(d -> assertThat((String) d)
                        .startsWith("El servicio no está disponible"));
    }
}
