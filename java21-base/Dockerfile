# Drop-in replacement for eventuateio/eventuate-examples-docker-images-spring-example-base-image
# Upgrades Java 17 → 21 (required for class file version 65.0 compiled on jdk-21-version branch)
FROM amazoncorretto:21-alpine
CMD ["/bin/sh", "-c", "java ${JAVA_OPTS:--Xmx64m} -jar ${JAR:-service.jar}"]
