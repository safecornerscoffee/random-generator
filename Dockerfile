FROM openjdk:11 as builder

WORKDIR /random-generator

ADD pom.xml mvnw mvnw.cmd /random-generator/
ADD .mvn /random-generator/.mvn
RUN ./mvnw verify --fail-never

ADD . /random-generator
RUN ./mvnw package

FROM openjdk:11 as extractor

COPY --from=builder /random-generator/target/random-generator-1.0.jar /random-generator/random-generator.jar

WORKDIR /random-generator

RUN java -Djarmode=layertools -jar random-generator.jar extract

FROM openjdk:11

EXPOSE 8080

WORKDIR /opt/random-generator

COPY --from=extractor /random-generator/dependencies/ ./
COPY --from=extractor /random-generator/spring-boot-loader/ ./
COPY --from=extractor /random-generator/application/ ./

ENTRYPOINT ["java", "org.springframework.boot.loader.JarLauncher"]