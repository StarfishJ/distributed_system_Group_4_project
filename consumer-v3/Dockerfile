# Build stage: 使用 Maven 编译代码
FROM maven:3.9.6-eclipse-temurin-17-alpine AS build
WORKDIR /app

# 1. 拷贝 pom.xml
COPY pom.xml .
RUN mvn dependency:go-offline

# 2. 打包应用
COPY src ./src
RUN mvn clean package -DskipTests

# Run stage: 使用更小的 JRE
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 拷贝 JAR 包
COPY --from=build /app/target/chat-consumer-v3-0.0.1-SNAPSHOT.jar app.jar

# 启动 (Java 堆内存限制建议调优)
ENTRYPOINT ["java", "-Xmx2g", "-jar", "app.jar"]
