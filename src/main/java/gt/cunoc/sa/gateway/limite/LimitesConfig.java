package gt.cunoc.sa.gateway.limite;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import gt.cunoc.sa.gateway.seguridad.FiltroJwt;
import gt.cunoc.sa.gateway.seguridad.TokenVerificado;
import reactor.core.publisher.Mono;

/**
 * Claves del RequestRateLimiter (README, "Rate limiting"). Cada ruta elige la suya con
 * key-resolver: "#{@limitePorIp}" o "#{@limitePorUsuario}".
 *
 * Con prefijo: el bucket en Redis se arma con la ruta y esta clave, y el prefijo evita que el
 * usuario y una IP con el mismo texto compartan cuenta.
 */
@Configuration
public class LimitesConfig {

    /** Rutas públicas (login, registro…): no hay usuario, y se frena la fuerza bruta por IP. */
    @Bean
    KeyResolver limitePorIp(ResolutorIp ip) {
        return exchange -> Mono.just("ip:" + ip.ip(exchange));
    }

    /**
     * Rutas con token: el usuario que dejó FiltroJwt, o la IP si no hay. Es @Primary porque
     * RequestRateLimiter necesita uno por defecto y es el que usan casi todas las rutas.
     */
    @Bean
    @Primary
    KeyResolver limitePorUsuario(ResolutorIp ip) {
        return exchange -> {
            TokenVerificado token = exchange.getAttribute(FiltroJwt.ATRIBUTO_TOKEN);
            return Mono.just(token != null ? "usuario:" + token.usuarioId() : "ip:" + ip.ip(exchange));
        };
    }
}
