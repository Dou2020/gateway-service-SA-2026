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
├── docker-compose.yml  proyecto Compose propio, en la red transporte; una <SERVICIO>_URL por servicio (ver "Destinos"); en la VM, la imagen de GATEWAY_IMAGE
├── .dockerignore   deja target/, .env y archivos del IDE fuera de la imagen
├── .gitignore      lo que no se versiona: target/, .env (no .env.example), logs, IDE y SO
├── .env.example    variables de su compose (Redis, JWT_SECRET, FRONTEND_URL, PROXIES_CONFIABLES y las de la VM); se copia a .env, que no se versiona
├── .github/workflows/
│   └── ci-cd.yml   pruebas en dev y main; en main, imagen a GHCR y despliegue en la VM por SSH (README raíz, "Pipeline del gateway")
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
        │   ├── FiltroJwtTest.java             401, X-User-* falsificados, logout no público, CORS (también el preflight de las rutas solo POST)
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
| `customer-recarga` | `POST /api/cartera/recargas` | customer-service | Sí | 10 por minuto por usuario |
| `customer` | `/api/clientes/**`, `/api/cartera/**`, `/api/alquileres/**`, `/api/historial` | customer-service | Sí | 20 por segundo por usuario, ráfagas de 40 |
| `finance` | `/api/categorias-gasto/**`, `/api/gastos-taller/**`, `/api/finanzas/**` | finance-service | Sí | 20 por segundo por usuario, ráfagas de 40 |
| `sales-compra` | `POST /api/boletos` | sales-service | Sí | 10 por minuto por usuario |
| `sales` | `/api/boletos/**` | sales-service | Sí | Sin límite |
| `fleet` | `/api/fleet/**` (buses, choferes y rutas) | fleet-service | Sí | 20 por segundo por usuario, ráfagas de 40 |
| `trip` | `/api/rutas/**`, `/api/viajes/**`, `/api/configuracion/**` | trip-service | Sí | 20 por segundo por usuario, ráfagas de 40 |
| — | `/internal/**` y `/api/interno/**` (llamadas internas de trip) | sin ruta → `404` | — | — |

La compra de boletos y la recarga de cartera tienen su propia ruta, declarada **antes** de la general
de su servicio: el gateway usa la primera que coincide, y cada ruta tiene su propio bucket en Redis.
notification no tiene ruta: no expone endpoints, solo consume eventos.

### Destinos

Cada ruta va a la URL de una variable: en Compose, el nombre del contenedor en la red `transporte`;
fuera de Docker (`mvn spring-boot:run`), el valor por defecto de `application.yml`.

| Variable | En Compose (`docker-compose.yml`) | Fuera de Docker |
|---|---|---|
| `IDENTITY_URL` | `http://identity-service:8081` | `http://localhost:8081` |
| `CUSTOMER_URL` | `http://customer-service:8087` | `http://localhost:8087` |
| `FINANCE_URL` | `http://finance-service:8086` | `http://localhost:8086` |
| `SALES_URL` | `http://sales-service:8083`; se puede cambiar en `.env` | `http://localhost:8083` |
| `FLEET_URL` | `http://fleet-service:8084` | `http://localhost:8084` |
| `TRIP_URL` | `http://trip-service:8082` | `http://localhost:8082` |

Los puertos de sales, fleet y trip son los que usan hoy esos servicios, no los de la tabla
["Armar un servicio nuevo"](../README.md#armar-un-servicio-nuevo) del README del proyecto
(8084, 8082 y 8083).

### Propios

| Método | Ruta | Qué es |
|---|---|---|
| GET | `/swagger-ui.html` | Swagger agregado: un desplegable con la definición de cada uno de los seis servicios de abajo |
| GET | `/v3/api-docs/{identity,customer,finance,sales,fleet,trip}` | El `/v3/api-docs` de cada servicio, reenviado con `RewritePath`. Públicos: no piden token |
| GET | `/actuator/health` | Salud (healthcheck de Compose) |

### Encabezados y respuestas propias

- Hacia los servicios escribe `X-User-Id`, `X-User-Roles`, `X-Sucursal-Id`, `X-Chofer-Id` (después
  de borrar los que mande el cliente) y `X-Correlation-Id`, que también devuelve en la respuesta.
- Responde él mismo, siempre como `ProblemDetail`: `401` (token ausente, inválido, vencido o
  revocado, con `WWW-Authenticate: Bearer`), `404` (sin ruta), `429` (límite superado, con
  `X-RateLimit-*`), `503` (servicio caído o sin contenedor en la red `transporte`) y `504` (más de
  15 s sin respuesta).
- Si Redis no responde, deja pasar sin revisar la revocación ni contar el límite (WARN y la
  métrica `gateway.revocacion.fallos`).


## Levantar en desarrollo

Proyecto Compose propio (`docker-compose.yml` de esta carpeta), sin base: solo el gateway, en la red
`transporte`. Los comandos van desde `api-gateway/`, salvo el de la infra. El
[README del proyecto](../README.md#levantar-en-desarrollo) lo describe con `stack.sh`, que todavía no existe;
aquí va con `docker compose` directo.

1. **Infra**, desde la raíz del proyecto, con su `.env` copiado de `.env.example`. Va primero porque crea la red
   `transporte`, que este compose declara externa, y porque el gateway necesita Redis:

   ```bash
   docker compose up -d --wait                  # Redis, RabbitMQ y la red transporte
   ```

2. **`.env`** de esta carpeta. `REDIS_PASSWORD` tiene que ser el de `../.env`, y `JWT_SECRET` el **mismo** de
   `../identity-service/.env` (se copia, no se genera otro: con uno distinto, todo token da `401`):

   ```bash
   cp .env.example .env
   ```

3. **En Docker**:

   ```bash
   docker compose up -d --build --wait          # espera a que esté healthy
   curl localhost:8080/actuator/health          # {"status":"UP"}
   ```

   Swagger agregado en http://localhost:8080/swagger-ui.html. Conviene levantarlo después de los servicios,
   aunque no es obligatorio: mientras un servicio no está arriba, sus rutas responden `503`. Con los importados
   pasa si su compose no está arriba ([sales](../sales-service/README.md#levantar-en-desarrollo),
   [fleet](../fleet-service/README.md#levantar-en-desarrollo), [trip](../trip-service/README.md#levantar-en-desarrollo)),
   y las rutas de trip que consultan a fleet responden el `503` del propio trip si fleet no está. Para el día a día:

   ```bash
   docker compose logs -f api-gateway
   docker compose up -d --build                 # después de cambiar el código
   docker compose down
   ```

4. **Fuera de Docker**, con Java 21 y Maven. Usa el mismo puerto que su contenedor, así que ese contenedor se
   detiene primero:

   ```bash
   docker compose stop api-gateway              # si estaba arriba
   set -a; . ./.env; set +a; mvn spring-boot:run
   ```

   Fuera de Docker, cada destino es `http://localhost:<puerto>` ([Destinos](#destinos)) y Redis está en
   `localhost:6379`. Los servicios en Docker publican sus puertos en `127.0.0.1`, así que los encuentra igual.

5. **Pruebas**, con Docker corriendo. Testcontainers levanta su propio Redis, y los servicios de destino son
   falsos, así que no usa la infra ni los servicios de arriba:

   ```bash
   mvn -q test
   mvn -q test -Dtest=RevocacionTest            # una clase
   ```

6. **Probar**: un login por el gateway y el perfil con el token que devuelve. identity tiene que estar arriba:

   ```bash
   TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'content-type: application/json' \
     -d '{"correo": "admin@transporte.gt", "contrasena": "<la de ADMIN_CONTRASENA>"}' \
     | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
   curl -s localhost:8080/api/perfil -H "Authorization: Bearer $TOKEN"
   ```

   Sin token, `GET /api/perfil` da `401` `Inicia sesión para continuar`, y la contraseña equivocada da `401`
   `Correo o contraseña incorrectos`. El mismo `TOKEN` sirve para cualquier otra ruta `/api/**`.
