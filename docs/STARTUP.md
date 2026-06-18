# 启动说明

## 环境要求

| 工具 | 建议版本 |
|---|---|
| JDK | 17+ |
| Maven | 3.8+ |

## 启动服务

在仓库根目录执行：

```bash
mvn spring-boot:run
```

默认服务地址：

```text
http://localhost:8099
```

## 构建 JAR

```bash
mvn clean package -DskipTests
```

构建完成后运行：

```bash
java -jar target/xtx-mock-service-1.0.0.jar
```

## 接口验证

```bash
curl http://localhost:8099/home/goods
curl http://localhost:8099/home/category/head
```
