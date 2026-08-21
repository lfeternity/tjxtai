FROM eclipse-temurin:17-jre
LABEL org.opencontainers.image.authors="lfeternity"
LABEL org.opencontainers.image.source="https://github.com/lfeternity/tjxtai"
ENV JAVA_OPTS=""
# 设定时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /app
COPY app.jar /app/app.jar

ENTRYPOINT ["sh","-c","java -jar $JAVA_OPTS /app/app.jar"]
