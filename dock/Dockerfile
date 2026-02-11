# Java 21 runtime
FROM eclipse-temurin:21-jre

WORKDIR /app

# Если jar рядом с Dockerfile (в той же папке), просто копируем его в контейнер
# Переименуем в app.jar для простоты
COPY ./*.jar /app/app.jar

# (опционально) если приложение слушает порт
# EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
