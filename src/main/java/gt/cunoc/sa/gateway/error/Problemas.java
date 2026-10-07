package gt.cunoc.sa.gateway.error;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

/**
 * Las respuestas que da el propio gateway (401, 404, 429, 503…) salen como ProblemDetail, igual
 * que las de los servicios: el frontend siempre muestra el "detail".
 */
@Component
public class Problemas {

    private final ObjectMapper json;

    public Problemas(ObjectMapper json) {
        this.json = json;
    }

    public Mono<Void> escribir(ServerWebExchange exchange, HttpStatusCode estado, String detalle) {
        return escribir(exchange.getResponse(), estado, detalle, exchange.getRequest().getPath().value());
    }

    public Mono<Void> escribir(ServerHttpResponse respuesta, HttpStatusCode estado, String detalle, String ruta) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
        problema.setInstance(java.net.URI.create(ruta));
        byte[] cuerpo;
        try {
            cuerpo = json.writeValueAsBytes(problema);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        respuesta.setStatusCode(estado);
        respuesta.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        respuesta.getHeaders().setContentLength(cuerpo.length);
        return respuesta.writeWith(Mono.just(respuesta.bufferFactory().wrap(cuerpo)));
    }
}
