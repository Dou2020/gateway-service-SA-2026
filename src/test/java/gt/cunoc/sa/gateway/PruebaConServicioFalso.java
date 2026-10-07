package gt.cunoc.sa.gateway;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Las rutas de identity apuntan al ServicioFalso. */
public abstract class PruebaConServicioFalso {

    @DynamicPropertySource
    static void rutasAlServicioFalso(DynamicPropertyRegistry propiedades) {
        propiedades.add("IDENTITY_URL", ServicioFalso::url);
    }
}
