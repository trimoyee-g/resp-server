FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
COPY RespServer.java .
RUN javac RespServer.java
EXPOSE 6379
CMD ["java", "RespServer"]
