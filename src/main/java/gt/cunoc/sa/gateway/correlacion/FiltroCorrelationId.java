package gt.cunoc.sa.gateway.correlacion;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Le da a cada request un X-Correlation-Id (el del cliente si es válido, si no uno
 * nuevo), lo reenvía al servicio y lo devuelve en la respuesta. Desde ahí viaja en los
 * logs de cada servicio y en el sobre de cada evento.
 *
 * Es un WebFilter y no un GlobalFilter: un GlobalFilter solo corre en las requests que
 * coinciden con una ruta, y este también tiene que marcar los 401, 404 y 429 que
 * responde el propio gateway. No lo pone en el MDC: en WebFlux la request cambia de
 * hilo y el MDC no la sigue sin context-propagation.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroCorrelationId implements WebFilter {

    public static final String ENCABEZADO = "X-Correlation-Id";

    // Termina en los logs de los servicios: solo un valor corto y sin caracteres de control.
    private static final Pattern VALIDO = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String recibido = exchange.getRequest().getHeaders().getFirst(ENCABEZADO);
        String correlationId = recibido != null && VALIDO.matcher(recibido).matches()
                ? recibido
                : UUID.randomUUID().toString();
        // Justo antes de enviar, y con set: el servicio devuelve el mismo encabezado y
        // la respuesta llevaría el valor dos veces.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(ENCABEZADO, correlationId);
            return Mono.empty();
        });
        return chain.filter(exchange.mutate()
                .request(r -> r.headers(h -> h.set(ENCABEZADO, correlationId)))
                .build());
    }
}
