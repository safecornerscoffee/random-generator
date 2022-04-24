FROM openjdk:11 as builder

ADD . /random-generator
WORKDIR /random-generator

RUN ./mvnw package

FROM openjdk:11

EXPOSE 8080

COPY --from=builder /random-generator/target/random-generator*jar /opt/random-generator.jar

WORKDIR /opt

ENTRYPOINT ["java", "-jar", "random-generator.jar"]