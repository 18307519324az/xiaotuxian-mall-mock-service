package com.xtx.mock.controller;

import com.xtx.common.core.result.FrontResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端点控制器。
 * <p>
 * 提供服务健康检查、版本信息等管理接口。
 * v1.7 新增 {@code /admin/health} 端点。
 * </p>
 */
@RestController
public class AdminController {

    private static final String SERVICE_NAME = "xtx-mock-service";
    private static final String VERSION = "v1.7";

    /**
     * GET /admin/health
     * 返回服务健康状态（Mock 服务无外部依赖，始终返回 UP）。
     */
    @GetMapping("/admin/health")
    public FrontResponse<Map<String, Object>> health() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("service", SERVICE_NAME);
        info.put("status", "UP");
        info.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        info.put("version", VERSION);
        return FrontResponse.success(info);
    }

    /**
     * GET /admin/version
     * 返回当前服务版本号和 Git commit。
     */
    @GetMapping("/admin/version")
    public FrontResponse<Map<String, Object>> version() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("service", SERVICE_NAME);
        info.put("version", VERSION);
        return FrontResponse.success(info);
    }
}
