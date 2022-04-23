FROM openjdk:11 as builder

ADD . /random-generator
WORKDIR /random-generator

RUN ./mvnw package

FROM openjdk:11

ARG JAR_FILE=target/*.jar

COPY --from=builder /random-generator/${JAR_FILE} app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app.jar"]