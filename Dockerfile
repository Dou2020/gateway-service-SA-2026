# Etapa 1: compila. Las dependencias van en una capa propia (solo pom.xml), así
# un cambio en src/ no vuelve a descargar todo Maven Central.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

# Etapa 2: solo el JRE y el jar. La imagen trae curl, que usa el healthcheck.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
