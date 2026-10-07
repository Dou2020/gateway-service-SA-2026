# api-gateway

Puerto `8080` · sin base (usa Redis) · paquete `gt.cunoc.sa.gateway`. Única puerta de entrada:
el diseño de autenticación y rate limiting está en el [README del proyecto](../README.md).

## Librerías

Spring Boot `3.5.16` y Spring Cloud `2025.0.3` sobre Java 21. Las versiones que el `pom.xml` no fija
las ponen esos dos BOM; aquí van resueltas (las mismas que empaqueta `target/app.jar`).

| Librería | Versión | Para qué |
|---|---|---|
| Spring Cloud Gateway (`server-webflux`) · Spring WebFlux · Reactor Netty | 4.3.5 · 6.2.19 · 1.2.18 | Ruteo de `/api/**` y de la documentación, filtro del JWT y `RequestRateLimiter` por ruta |
| Spring Data Redis Reactive · Lettuce | 3.5.13 · 6.6.0.RELEASE | Revocación de JWT (`MGET` de `auth:*`) y `RedisRateLimiter` |
| Spring Boot Actuator · Micrometer | 3.5.16 · 1.15.12 | `/actuator/health`, el healthcheck de Compose |
| springdoc-openapi (`webflux-ui`) · Swagger UI | 2.8.17 · 5.32.2 | Swagger UI agregado: muestra el `/v3/api-docs` de cada servicio |
| Nimbus JOSE + JWT | 10.10 | Verifica el HS256 del JWT que firma identity |

Sin Spring Security, a propósito: la autenticación es un `GlobalFilter` que además borra los `X-User-*`
del cliente en todas las rutas, también en las públicas.

| Pruebas | Versión | Para qué |
|---|---|---|
| JUnit Jupiter · Mockito · AssertJ (`spring-boot-starter-test`) | 5.12.2 · 5.17.0 · 3.27.7 | Pruebas unitarias y de integración (`@SpringBootTest`) |
| Testcontainers (`junit-jupiter`) + `spring-boot-testcontainers` | 1.21.4 · 3.5.16 | Redis real (`ContenedorRedis`) para revocación y rate limiting |
| Reactor Test | 3.7.19 | Declarada, sin uso todavía (`StepVerifier` para probar filtros reactivos) |

Imágenes: `maven:3.9-eclipse-temurin-21` compila y `eclipse-temurin:21-jre` corre (`Dockerfile`). Sin
base propia: usa el Redis de `infra` (`redis:7-alpine`), y las pruebas levantan otro con esa imagen.

## Estructura

```
api-gateway/
├── pom.xml         Spring Cloud 2025.0.3 (Gateway 4.3, WebFlux), Redis reactivo, springdoc, Nimbus
├── Dockerfile      dos etapas: Maven compila, JRE 21 corre target/app.jar
├── docker-compose.yml  proyecto Compose propio, en la red transporte; una <SERVICIO>_URL por servicio
├── .dockerignore   deja target/, .env y archivos del IDE fuera de la imagen
├── .gitignore      lo que no se versiona: target/, .env (no .env.example), logs, IDE y SO
├── .env.example    variables de su compose (Redis, JWT_SECRET, FRONTEND_URL y PROXIES_CONFIABLES); se copia a .env, que no se versiona
├── README.md       estructura y endpoints (este archivo)
└── src/
    ├── main/
    │   ├── java/gt/cunoc/sa/gateway/
    │   │   ├── GatewayApplication.java            arranque
    │   │   ├── seguridad/
    │   │   │   ├── FiltroJwt.java                 GlobalFilter: borra los X-User-* del cliente, 401 o escribe los del token
    │   │   │   ├── VerificadorJwt.java            firma HS256 y expiración; rechaza "alg": "none"
    │   │   │   ├── TokenVerificado.java           claims del token: usuario, roles, sucursal, chofer, jti, iat
    │   │   │   ├── RutasPublicas.java             login, registro, recuperación, contraseña y /v3/api-docs/**
    │   │   │   ├── Revocaciones.java              MGET de auth:*; si Redis falla deja pasar (WARN + métrica)
    │   │   │   └── RedisConfig.java               REJECT_COMMANDS: con Redis caído falla al instante
    │   │   ├── limite/
    │   │   │   ├── LimitesConfig.java             KeyResolver limitePorIp y limitePorUsuario
    │   │   │   └── ResolutorIp.java               IP del cliente según PROXIES_CONFIABLES (X-Forwarded-For)
    │   │   ├── correlacion/
    │   │   │   └── FiltroCorrelationId.java       WebFilter: genera o propaga X-Correlation-Id
    │   │   └── error/
    │   │       ├── Problemas.java                 escribe un ProblemDetail en la respuesta
    │   │       ├── FiltroProblema429.java         pone un ProblemDetail al 429 del RequestRateLimiter
    │   │       └── ManejadorErroresGateway.java   404, 503 (servicio caído), 504 y 500 como ProblemDetail
    │   └── resources/
    │       └── application.yml   rutas, límites por ruta, CORS, timeouts, Redis y Swagger agregado
    └── test/
        ├── java/gt/cunoc/sa/gateway/
        │   ├── ContenedorRedis.java           Redis en Testcontainers
        │   ├── ServicioFalso.java             HttpServer del JDK en lugar de identity; guarda lo que recibe
        │   ├── PruebaConServicioFalso.java    base: las rutas de identity apuntan al ServicioFalso
        │   ├── Tokens.java                    firma tokens de prueba como EmisorJwt
        │   ├── GatewayApplicationTests.java   rutas, X-Correlation-Id, /internal → 404, docs públicos
        │   ├── FiltroJwtTest.java             401, X-User-* falsificados, logout no público, CORS
        │   ├── RevocacionTest.java            jti revocado y corte por usuario (iat en el mismo segundo pasa)
        │   ├── LimiteTest.java                el sexto login en un minuto → 429
        │   ├── RedisCaidoTest.java            sin Redis la API sigue respondiendo
        │   └── ServicioCaidoTest.java         servicio caído → 503
        └── resources/config/
            └── application.yml   secreto JWT de prueba
```

No sigue `api/domain/application/infrastructure` como los servicios: no tiene dominio ni base.

## Endpoints

### Rutas que reenvía

| Ruta (`id`) | Ruta entrante | Destino | Token | Límite |
|---|---|---|---|---|
| `identity-publico` | `POST /api/auth/login`, `/api/auth/registro`, `/api/auth/recuperacion`, `/api/auth/contrasena` | identity-service | No | 5 por minuto por IP |
| `identity` | `/api/auth/logout`, `/api/perfil`, `/api/usuarios/**`, `/api/sucursales/**`, `/api/tarifas/**` | identity-service | Sí | 20 por segundo por usuario, ráfagas de 40 |
| — | `/api/**` de fleet, trip, sales, customer y finance | se agregan al crear cada servicio (compra de boletos y recarga de cartera: 10 por minuto por usuario) | Sí | — |
| — | `/internal/**` | sin ruta → `404` | — | — |

### Propios

| Método | Ruta | Qué es |
|---|---|---|
| GET | `/swagger-ui.html` | Swagger agregado: un desplegable con la definición de cada servicio |
| GET | `/v3/api-docs/identity` | El `/v3/api-docs` de identity-service, reenviado |
| GET | `/actuator/health` | Salud (healthcheck de Compose) |

### Encabezados y respuestas propias

- Hacia los servicios escribe `X-User-Id`, `X-User-Roles`, `X-Sucursal-Id`, `X-Chofer-Id` (después
  de borrar los que mande el cliente) y `X-Correlation-Id`, que también devuelve en la respuesta.
- Responde él mismo, siempre como `ProblemDetail`: `401` (token ausente, inválido, vencido o
  revocado, con `WWW-Authenticate: Bearer`), `404` (sin ruta), `429` (límite superado, con
  `X-RateLimit-*`), `503` (servicio caído) y `504` (más de 15 s sin respuesta).
- Si Redis no responde, deja pasar sin revisar la revocación ni contar el límite (WARN y la
  métrica `gateway.revocacion.fallos`).

