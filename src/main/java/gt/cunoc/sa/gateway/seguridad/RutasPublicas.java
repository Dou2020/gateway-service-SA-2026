package gt.cunoc.sa.gateway.seguridad;

import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Lo que pasa sin token (README, "En el gateway"). Todo lo demás de /api/** exige un JWT válido.
 * identity-service deja pasar las mismas rutas en su SeguridadConfig.
 */
@Component
public class RutasPublicas {

    private record Ruta(HttpMethod metodo, PathPattern patron) {
    }

    private static final List<Ruta> PUBLICAS = List.of(
            ruta(HttpMethod.POST, "/api/auth/login"),
            ruta(HttpMethod.POST, "/api/auth/registro"),
            ruta(HttpMethod.POST, "/api/auth/recuperacion"),
            ruta(HttpMethod.POST, "/api/auth/contrasena"),
            // Las definiciones OpenAPI de cada servicio, para el Swagger agregado.
            ruta(HttpMethod.GET, "/v3/api-docs/**"));

    public boolean esPublica(ServerHttpRequest request) {
        return PUBLICAS.stream().anyMatch(r -> r.metodo().equals(request.getMethod())
                && r.patron().matches(request.getPath().pathWithinApplication()));
    }

    private static Ruta ruta(HttpMethod metodo, String patron) {
        return new Ruta(metodo, PathPatternParser.defaultInstance.parse(patron));
    }
}
