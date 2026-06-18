package com.xtx.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Mock 服务启动类
 * 为前端提供兼容外部 API 格式的 Mock 数据
 * 响应格式统一为 { msg: "success", result: <data> }
 */
@SpringBootApplication(scanBasePackages = {"com.xtx.mock"})
@EnableScheduling
public class MockApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockApplication.class, args);
    }
}
