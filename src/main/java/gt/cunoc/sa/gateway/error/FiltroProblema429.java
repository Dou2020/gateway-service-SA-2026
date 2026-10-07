package gt.cunoc.sa.gateway.error;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * El RequestRateLimiter responde 429 con el cuerpo vacío (setComplete). Esta envoltura de la
 * respuesta le pone un ProblemDetail sin reescribir el filtro de Spring Cloud Gateway; los
 * encabezados X-RateLimit-* que ya escribió se conservan.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class FiltroProblema429 implements WebFilter {

    private final Problemas problemas;

    public FiltroProblema429(Problemas problemas) {
        this.problemas = problemas;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String ruta = exchange.getRequest().getPath().value();
        ServerHttpResponseDecorator respuesta = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                if (HttpStatus.TOO_MANY_REQUESTS.equals(getStatusCode()) && !isCommitted()) {
                    return problemas.escribir(this, HttpStatus.TOO_MANY_REQUESTS,
                            "Demasiados intentos, espera un momento", ruta);
                }
                return super.setComplete();
            }
        };
        return chain.filter(exchange.mutate().response(respuesta).build());
    }
}
