package gt.cunoc.sa.gateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

/**
 * Hace de identity-service: responde 200 a todo y guarda la ruta y los encabezados de la última
 * request, para ver exactamente qué reenvió el gateway. El HttpServer del JDK basta y no agrega
 * dependencias.
 */
public final class ServicioFalso {

    private static final HttpServer SERVIDOR;
    private static volatile Headers ultimosEncabezados = new Headers();
    private static volatile String ultimaRuta;

    static {
        try {
            SERVIDOR = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SERVIDOR.createContext("/", intercambio -> {
            Headers copia = new Headers();
            copia.putAll(intercambio.getRequestHeaders());
            ultimosEncabezados = copia;
            ultimaRuta = intercambio.getRequestURI().getPath();
            byte[] cuerpo = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(200, cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        SERVIDOR.start();
    }

    private ServicioFalso() {
    }

    public static String url() {
        return "http://127.0.0.1:" + SERVIDOR.getAddress().getPort();
    }

    public static Headers ultimosEncabezados() {
        return ultimosEncabezados;
    }

    public static String ultimaRuta() {
        return ultimaRuta;
    }

    public static void olvidar() {
        ultimosEncabezados = new Headers();
        ultimaRuta = null;
    }
}
