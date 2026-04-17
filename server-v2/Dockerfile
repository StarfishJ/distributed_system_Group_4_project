# Build stage: 使用 Maven 编译代码
FROM maven:3.9.6-eclipse-temurin-17-alpine AS build
WORKDIR /app

# 1. 先拷贝 pom.xml 下载依赖 (利用 Docker 缓存层)
COPY pom.xml .
RUN mvn dependency:go-offline

# 2. 拷贝源码并打包
COPY src ./src
RUN mvn clean package -DskipTests

# Run stage: 使用轻量级 JRE 运行应用
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 从编译阶段拷贝生成的 JAR 包
COPY --from=build /app/target/chat-server-0.0.1-SNAPSHOT.jar app.jar

# 暴露 Spring Boot 默认端口
EXPOSE 8080

# 启动命令
ENTRYPOINT ["java", "-Xmx1g", "-jar", "app.jar"]
