package gt.cunoc.sa.gateway.limite;

import java.net.InetSocketAddress;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * La IP del cliente para el límite por IP. En producción las requests llegan por el proxy
 * (Caddy), que agrega la IP real a X-Forwarded-For; se toma la entrada que agregó el último
 * proxy confiable, no la primera, que la escribe el cliente.
 *
 * En desarrollo no hay proxy (PROXIES_CONFIABLES=0) y se usa la IP de la conexión: confiar en
 * X-Forwarded-For dejaría saltarse el límite mandando una IP distinta en cada request.
 */
@Component
public class ResolutorIp {

    private final RemoteAddressResolver resolutor;

    public ResolutorIp(@Value("${gateway.proxies-confiables:0}") int proxiesConfiables) {
        this.resolutor = proxiesConfiables > 0
                ? XForwardedRemoteAddressResolver.maxTrustedIndex(proxiesConfiables)
                : new RemoteAddressResolver() {
                };
    }

    public String ip(ServerWebExchange exchange) {
        InetSocketAddress direccion = resolutor.resolve(exchange);
        if (direccion == null) {
            return "desconocida";
        }
        return direccion.getAddress() != null ? direccion.getAddress().getHostAddress() : direccion.getHostString();
    }
}
