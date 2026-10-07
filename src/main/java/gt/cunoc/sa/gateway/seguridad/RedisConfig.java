package gt.cunoc.sa.gateway.seguridad;

import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.lettuce.core.ClientOptions;

@Configuration
public class RedisConfig {

    /**
     * Con Redis caído, Lettuce por defecto encola los comandos mientras reintenta conectar y
     * cada request esperaría el timeout completo (200 ms por la revocación y otros tantos por el
     * limitador). Rechazarlos al instante hace que el "dejar pasar" no agregue latencia.
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer redisFallaAlInstante() {
        return builder -> {
            ClientOptions actuales = builder.build().getClientOptions().orElseGet(ClientOptions::create);
            builder.clientOptions(actuales.mutate()
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .build());
        };
    }
}
