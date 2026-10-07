package gt.cunoc.sa.gateway.seguridad;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;

/**
 * Revocación de JWT (README, "Revocación de JWT"): las llaves las escribe identity y aquí solo se
 * leen. Un solo MGET por request para las dos.
 *
 * Si Redis no responde, deja pasar: la revocación es una defensa adicional a la firma y la
 * expiración, y una caída de Redis no debe detener la venta de boletos. El costo es que durante
 * la caída un token revocado vuelve a servir (como máximo hasta que expira).
 */
@Component
public class Revocaciones {

    private static final Logger log = LoggerFactory.getLogger(Revocaciones.class);

    // Contrato con identity-service (infrastructure/redis/Sesiones).
    static final String REVOCADO = "auth:revocado:";
    static final String VALIDO_DESDE = "auth:valido-desde:";
    private static final Duration ESPERA = Duration.ofMillis(200);

    private final ReactiveStringRedisTemplate redis;
    private final Counter fallos;

    public Revocaciones(ReactiveStringRedisTemplate redis, MeterRegistry metricas) {
        this.redis = redis;
        this.fallos = Counter.builder("gateway.revocacion.fallos")
                .description("Requests que pasaron sin revisar la revocación porque Redis no respondió")
                .register(metricas);
    }

    /** @return false si el jti se revocó (logout) o el token es anterior al corte del usuario */
    public Mono<Boolean> vigente(TokenVerificado token) {
        return redis.opsForValue()
                .multiGet(List.of(REVOCADO + token.jti(), VALIDO_DESDE + token.usuarioId()))
                .timeout(ESPERA)
                .map(valores -> !presente(valores.get(0)) && !anteriorAlCorte(token, valores.get(1)))
                .onErrorResume(e -> {
                    fallos.increment();
                    log.warn("Redis no respondió; el token {} pasa sin revisar su revocación: {}", token.jti(),
                            e.toString());
                    return Mono.just(true);
                });
    }

    /**
     * Corte en epoch segundos, como iat. Estricto (<): un token emitido en el mismo segundo del
     * corte sigue valiendo, porque con precisión de segundos no se sabe si fue antes o después, y
     * rechazarlo bloquearía el login que sigue a un cambio de contraseña.
     */
    private static boolean anteriorAlCorte(TokenVerificado token, String corte) {
        if (!presente(corte)) {
            return false;
        }
        try {
            return token.emitidoEn().getEpochSecond() < Long.parseLong(corte.strip());
        } catch (NumberFormatException e) {
            log.warn("auth:valido-desde de {} no es un número: {}", token.usuarioId(), corte);
            return false;
        }
    }

    /** MGET devuelve null (o vacío, según el driver) para una llave que no existe. */
    private static boolean presente(String valor) {
        return valor != null && !valor.isEmpty();
    }
}
