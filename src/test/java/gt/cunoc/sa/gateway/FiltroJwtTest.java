package gt.cunoc.sa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.sun.net.httpserver.Headers;

/** README, "Pruebas" → "Gateway": el gateway autentica y escribe la identidad; nadie más. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(ContenedorRedis.class)
class FiltroJwtTest extends PruebaConServicioFalso {

    @Autowired
    WebTestClient cliente;

    @BeforeEach
    void limpiar() {
        ServicioFalso.olvidar();
    }

    @Test
    void sinTokenResponde401ComoProblemDetailSinLlegarAlServicio() {
        cliente.get().uri("/api/usuarios").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists("X-Correlation-Id")
                .expectBody().jsonPath("$.detail").isEqualTo("Inicia sesión para continuar");
        assertThat(ServicioFalso.ultimaRuta()).isNull();
    }

    @Test
    void rechazaTokensMalFirmadosSinFirmaOVencidos() {
        String usuario = UUID.randomUUID().toString();
        Instant hace9Horas = Instant.now().minus(Duration.ofHours(9));
        for (String token : new String[] {
                Tokens.firmar(Tokens.claims(usuario, "SYS_ADMIN"), "otro-secreto-de-al-menos-32-bytes!".getBytes()),
                Tokens.sinFirma(Tokens.claims(usuario, "SYS_ADMIN")),
                Tokens.firmar(Tokens.claims(usuario, "SYS_ADMIN", hace9Horas)),
                Tokens.firmar(Tokens.claims(usuario, "SYS_ADMIN").claim("roles", null)),
                "no-es-un-jwt"}) {
            cliente.get().uri("/api/usuarios").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                    .expectStatus().isUnauthorized();
        }
        assertThat(ServicioFalso.ultimaRuta()).isNull();
    }

    @Test
    void conTokenLleganLosEncabezadosDelTokenYNoLosDelCliente() {
        String usuario = UUID.randomUUID().toString();
        String sucursal = UUID.randomUUID().toString();
        String token = Tokens.firmar(Tokens.claims(usuario, "BRANCH_ADMIN").claim("sucursalId", sucursal));

        cliente.get().uri("/api/usuarios")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Roles", "SYS_ADMIN")
                .header("X-Chofer-Id", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isOk();

        Headers recibidos = ServicioFalso.ultimosEncabezados();
        assertThat(recibidos.get("X-User-Id")).containsExactly(usuario);
        assertThat(recibidos.get("X-User-Roles")).containsExactly("BRANCH_ADMIN");
        assertThat(recibidos.get("X-Sucursal-Id")).containsExactly(sucursal);
        assertThat(recibidos.containsKey("X-Chofer-Id")).isFalse();
    }

    @Test
    void enUnaRutaPublicaLaIdentidadQueMandaElClienteNoLlegaAlServicio() {
        cliente.post().uri("/api/auth/registro")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Roles", "SYS_ADMIN")
                .header("X-Sucursal-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        assertThat(ServicioFalso.ultimaRuta()).isEqualTo("/api/auth/registro");
        Headers recibidos = ServicioFalso.ultimosEncabezados();
        assertThat(recibidos.containsKey("X-User-Id")).isFalse();
        assertThat(recibidos.containsKey("X-User-Roles")).isFalse();
        assertThat(recibidos.containsKey("X-Sucursal-Id")).isFalse();
    }

    @Test
    void elLogoutNoEsPublico() {
        cliente.post().uri("/api/auth/logout").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void elPreflightCorsDelFrontendNoPideToken() {
        cliente.options().uri("/api/usuarios")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000");
        cliente.options().uri("/api/usuarios")
                .header(HttpHeaders.ORIGIN, "https://otro-sitio.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden();
    }

    /** identity-publico solo acepta POST: su preflight (OPTIONS) no coincide con la ruta. */
    @Test
    void elPreflightDeLasRutasSoloPostTambienPasa() {
        for (String ruta : new String[] {"/api/auth/login", "/api/auth/recuperacion"}) {
            cliente.options().uri(ruta)
                    .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000");
            cliente.options().uri(ruta)
                    .header(HttpHeaders.ORIGIN, "https://otro-sitio.com")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .exchange()
                    .expectStatus().isForbidden();
        }
        assertThat(ServicioFalso.ultimaRuta()).isNull();
    }
}
