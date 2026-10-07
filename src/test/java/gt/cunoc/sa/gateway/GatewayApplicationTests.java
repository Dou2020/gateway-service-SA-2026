package gt.cunoc.sa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Rutas, X-Correlation-Id y lo que el gateway nunca expone. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(ContenedorRedis.class)
class GatewayApplicationTests extends PruebaConServicioFalso {

    @Autowired
    RouteLocator rutas;

    @Autowired
    WebTestClient cliente;

    @Test
    void cargaLasRutasDeIdentity() {
        assertThat(rutas.getRoutes().map(Route::getId).collectList().block())
                .contains("identity-publico", "identity", "identity-docs");
    }

    @Test
    void propagaElCorrelationIdOGeneraUno() {
        cliente.get().uri("/actuator/health").header("X-Correlation-Id", "abc-123").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-Id", "abc-123");
        cliente.get().uri("/actuator/health").header("X-Correlation-Id", "no valido; con espacios").exchange()
                .expectHeader().value("X-Correlation-Id", id -> assertThat(id).isNotEqualTo("no valido; con espacios"));
    }

    @Test
    void internalNoTieneRutaYResponde404ComoProblemDetail() {
        cliente.get().uri("/internal/sucursales/x/administradores").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(404);
    }

    @Test
    void lasDefinicionesOpenApiSonPublicas() {
        cliente.get().uri("/v3/api-docs/identity").exchange().expectStatus().isOk();
        assertThat(ServicioFalso.ultimaRuta()).isEqualTo("/v3/api-docs");
    }
}
