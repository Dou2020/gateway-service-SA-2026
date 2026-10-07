package gt.cunoc.sa.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/** Firma tokens como lo haría identity-service (EmisorJwt), con el secreto de las pruebas. */
public final class Tokens {

    public static final byte[] SECRETO = Base64.getDecoder().decode("Vh93zEp66lBf5j74T1YLv8HjBVnX+Kp+4P+fTSOoBcY=");

    private Tokens() {
    }

    /** Claims de un token recién emitido, de 8 h. */
    public static JWTClaimsSet.Builder claims(String usuarioId, String rol) {
        Instant ahora = Instant.now();
        return claims(usuarioId, rol, ahora);
    }

    public static JWTClaimsSet.Builder claims(String usuarioId, String rol, Instant emitidoEn) {
        return new JWTClaimsSet.Builder()
                .subject(usuarioId)
                .claim("roles", List.of(rol))
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(emitidoEn))
                .expirationTime(Date.from(emitidoEn.plus(Duration.ofHours(8))));
    }

    public static String firmar(JWTClaimsSet.Builder claims) {
        return firmar(claims, SECRETO);
    }

    public static String firmar(JWTClaimsSet.Builder claims, byte[] secreto) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(secreto));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "alg": "none": los claims correctos, sin firma. */
    public static String sinFirma(JWTClaimsSet.Builder claims) {
        return new PlainJWT(claims.build()).serialize();
    }
}
