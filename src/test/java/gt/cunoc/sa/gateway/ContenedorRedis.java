package gt.cunoc.sa.gateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/** Redis real (la misma imagen que el compose) para la revocación y el limitador. */
@TestConfiguration(proxyBeanMethods = false)
public class ContenedorRedis {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }
}
