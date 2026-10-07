package gt.cunoc.sa.gateway.seguridad;

import java.time.Instant;
import java.util.List;

/**
 * Lo que el gateway saca de un JWT válido: los claims que se vuelven X-User-*, más jti e iat
 * para la revocación.
 *
 * @param sucursalId solo BRANCH_ADMIN y CASHIER
 * @param choferId   solo DRIVER
 */
public record TokenVerificado(
        String usuarioId,
        List<String> roles,
        String sucursalId,
        String choferId,
        String jti,
        Instant emitidoEn) {
}
