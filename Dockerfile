FROM eclipse-temurin:21-alpine
VOLUME /tmp
COPY build/libs/nucleus.jar nucleus.jar
ENTRYPOINT java $JAVA_OPTS -jar ./nucleus.jar
EXPOSE 8080
