package gt.cunoc.sa.gateway.seguridad;

import java.text.ParseException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * Verifica firma HS256 y expiración del JWT que emite identity-service, con el mismo
 * JWT_SECRET. La revocación (Redis) va aparte, en Revocaciones.
 *
 * Solo acepta HS256: el procesador rechaza "alg": "none" y cualquier otro algoritmo, así que un
 * token sin firma o firmado de otra forma no pasa aunque traiga los claims correctos.
 */
@Component
public class VerificadorJwt {

    private static final int BYTES_MINIMOS = 32;   // 256 bits
    private static final Set<String> CLAIMS_REQUERIDOS = Set.of("sub", "exp", "iat", "jti", "roles");

    private final DefaultJWTProcessor<SecurityContext> procesador;

    public VerificadorJwt(@Value("${jwt.secreto:}") String secreto) {
        byte[] llave = decodificar(secreto);
        procesador = new DefaultJWTProcessor<>();
        procesador.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.HS256, new ImmutableSecret<>(llave)));
        // exp con 60 s de tolerancia de reloj (el valor por defecto de Nimbus).
        procesador.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(null, CLAIMS_REQUERIDOS));
    }

    /** @return vacío si el token es ilegible, está mal firmado, venció o le falta un claim */
    public Optional<TokenVerificado> verificar(String token) {
        try {
            JWTClaimsSet claims = procesador.process(token, null);
            List<String> roles = claims.getStringListClaim("roles");
            if (roles == null || roles.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new TokenVerificado(claims.getSubject(), roles,
                    claims.getStringClaim("sucursalId"), claims.getStringClaim("choferId"), claims.getJWTID(),
                    claims.getIssueTime().toInstant()));
        } catch (ParseException | BadJOSEException | JOSEException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Falla al arrancar: nunca verificar con un secreto vacío o corto. Mismo formato que identity. */
    static byte[] decodificar(String secreto) {
        if (secreto == null || secreto.isBlank()) {
            throw new IllegalStateException("Falta JWT_SECRET (generarlo con: openssl rand -base64 32)");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secreto.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT_SECRET debe estar en Base64 (openssl rand -base64 32)", e);
        }
        if (bytes.length < BYTES_MINIMOS) {
            throw new IllegalStateException("JWT_SECRET debe tener al menos 256 bits (32 bytes); tiene "
                    + bytes.length * 8);
        }
        return bytes;
    }
}
