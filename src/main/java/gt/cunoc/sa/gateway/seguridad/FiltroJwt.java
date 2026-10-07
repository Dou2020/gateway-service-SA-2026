package gt.cunoc.sa.gateway.seguridad;

import java.util.List;
import java.util.Optional;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import gt.cunoc.sa.gateway.error.Problemas;
import reactor.core.publisher.Mono;

/**
 * El gateway autentica; cada servicio autoriza (README, "Autenticación y autorización").
 *
 * Borra siempre los X-User-* que mande el cliente, también en las rutas públicas, y después
 * escribe los del token. Si no los borrara, un cliente podría mandar X-User-Roles: SYS_ADMIN a
 * /api/auth/**, que no exige token, y el servicio le creería. El borrado va aquí y no en un
 * RemoveRequestHeader de default-filters: así no depende del orden entre filtros, y uno que
 * corriera después borraría los encabezados recién escritos.
 *
 * Es un GlobalFilter (solo corre en requests con ruta) con orden negativo: antes de los filtros
 * de ruta, para que el RequestRateLimiter ya sepa quién es el usuario.
 */
@Component
public class FiltroJwt implements GlobalFilter, Ordered {

    public static final String USUARIO = "X-User-Id";
    public static final String ROLES = "X-User-Roles";
    public static final String SUCURSAL = "X-Sucursal-Id";
    public static final String CHOFER = "X-Chofer-Id";
    public static final List<String> ENCABEZADOS_IDENTIDAD = List.of(USUARIO, ROLES, SUCURSAL, CHOFER);

    /** El token verificado, para limitePorUsuario. */
    public static final String ATRIBUTO_TOKEN = FiltroJwt.class.getName() + ".token";

    private static final String PREFIJO = "Bearer ";

    private final RutasPublicas rutasPublicas;
    private final VerificadorJwt verificador;
    private final Revocaciones revocaciones;
    private final Problemas problemas;

    public FiltroJwt(RutasPublicas rutasPublicas, VerificadorJwt verificador, Revocaciones revocaciones,
            Problemas problemas) {
        this.rutasPublicas = rutasPublicas;
        this.verificador = verificador;
        this.revocaciones = revocaciones;
        this.problemas = problemas;
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Las públicas (login, registro…) no usan identidad: pasan anónimas aunque traigan un
        // token, y sin consultar Redis.
        if (rutasPublicas.esPublica(exchange.getRequest())) {
            return chain.filter(conIdentidad(exchange, null));
        }
        Optional<String> bearer = bearer(exchange.getRequest());
        if (bearer.isEmpty()) {
            return noAutenticado(exchange, "Inicia sesión para continuar");
        }
        Optional<TokenVerificado> token = bearer.flatMap(verificador::verificar);
        if (token.isEmpty()) {
            return noAutenticado(exchange, "Tu sesión no es válida o venció. Inicia sesión de nuevo");
        }
        return revocaciones.vigente(token.get()).flatMap(vigente -> vigente
                ? chain.filter(conIdentidad(exchange, token.get()))
                : noAutenticado(exchange, "Tu sesión se cerró. Inicia sesión de nuevo"));
    }

    private ServerWebExchange conIdentidad(ServerWebExchange exchange, TokenVerificado token) {
        ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> {
            ENCABEZADOS_IDENTIDAD.forEach(h::remove);
            if (token != null) {
                h.set(USUARIO, token.usuarioId());
                h.set(ROLES, String.join(",", token.roles()));
                if (token.sucursalId() != null) {
                    h.set(SUCURSAL, token.sucursalId());
                }
                if (token.choferId() != null) {
                    h.set(CHOFER, token.choferId());
                }
            }
        }).build();
        ServerWebExchange mutado = exchange.mutate().request(request).build();
        if (token != null) {
            mutado.getAttributes().put(ATRIBUTO_TOKEN, token);
        }
        return mutado;
    }

    private static Optional<String> bearer(ServerHttpRequest request) {
        String valor = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (valor == null || !valor.regionMatches(true, 0, PREFIJO, 0, PREFIJO.length())) {
            return Optional.empty();
        }
        String token = valor.substring(PREFIJO.length()).strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private Mono<Void> noAutenticado(ServerWebExchange exchange, String detalle) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return problemas.escribir(exchange, HttpStatus.UNAUTHORIZED, detalle);
    }
}
