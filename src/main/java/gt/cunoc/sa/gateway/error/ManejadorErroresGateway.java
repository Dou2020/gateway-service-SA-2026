package gt.cunoc.sa.gateway.error;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Errores del propio gateway como ProblemDetail. Antes (-2) que el manejador de Spring Boot
 * (-1), que responde un JSON con otro formato.
 *
 * Un servicio caído llega como conexión rechazada o, en Compose, como un nombre que no resuelve
 * (el DNS de Docker quita el nombre de un contenedor detenido). Por defecto serían un 500; aquí
 * son 503, que el frontend puede presentar como "intenta en un momento".
 */
@Component
@Order(-2)
public class ManejadorErroresGateway implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErroresGateway.class);

    private final Problemas problemas;

    public ManejadorErroresGateway(Problemas problemas) {
        this.problemas = problemas;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        HttpStatusCode estado;
        String detalle;
        if (error instanceof ResponseStatusException rse) {
            estado = rse.getStatusCode();
            detalle = switch (estado.value()) {
                case 404 -> "No existe el recurso solicitado";
                case 503 -> "El servicio no está disponible en este momento. Intenta de nuevo en unos minutos";
                case 504 -> "El servicio tardó demasiado en responder. Intenta de nuevo";
                default -> rse.getReason() != null ? rse.getReason() : "La solicitud no se pudo procesar";
            };
        } else if (causa(error, ConnectException.class) || causa(error, UnknownHostException.class)) {
            estado = HttpStatus.SERVICE_UNAVAILABLE;
            detalle = "El servicio no está disponible en este momento. Intenta de nuevo en unos minutos";
        } else if (causa(error, TimeoutException.class)) {
            estado = HttpStatus.GATEWAY_TIMEOUT;
            detalle = "El servicio tardó demasiado en responder. Intenta de nuevo";
        } else {
            log.error("Error no controlado en el gateway", error);
            estado = HttpStatus.INTERNAL_SERVER_ERROR;
            detalle = "Ocurrió un error inesperado. Intenta de nuevo más tarde";
        }
        if (estado.is5xxServerError() && estado.value() != 500) {
            log.warn("{} en {}: {}", estado.value(), exchange.getRequest().getPath(), error.toString());
        }
        return problemas.escribir(exchange, estado, detalle);
    }

    private static boolean causa(Throwable error, Class<? extends Throwable> tipo) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (tipo.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
