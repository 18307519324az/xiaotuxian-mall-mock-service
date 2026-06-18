package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.common.core.result.FrontResponse;
import com.xtx.mock.service.activity.UserActivityMockService;
import com.xtx.mock.service.admin.AdminRuntimeService;
import com.xtx.mock.service.preference.UserPreferenceMockService;
import com.xtx.mock.store.RuntimeCartStore;
import com.xtx.mock.store.RuntimeUserActivityStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * MockAdminController — Admin / Runtime Lifecycle 模块控制器。
 * <p>
 * 负责 Admin 端点：
 * <ul>
 *   <li>POST /admin/reset-member-data — 重置默认用户运行时数据</li>
 *   <li>POST /admin/reload — 重新加载 master 数据文件</li>
 * </ul>
 * <p>
 * 遵循 MockGoodsController / MockAuthController 提取模式：
 * 构造器创建 AdminRuntimeService，数据源通过 @PostConstruct 由 MockController 注入。
 */
@Slf4j
@RestController
public class MockAdminController {

    private final AdminRuntimeService adminService;

    public MockAdminController() {
        this.adminService = new AdminRuntimeService();
        log.info("MockAdminController initialized");
    }

    /** 暴露 Service 供 MockController @PostConstruct 注入数据源和回调 */
    public AdminRuntimeService getService() {
        return adminService;
    }

    // ==================== Callback 注入 ====================

    public void setReloadMasterFilesCallback(Supplier<Integer> cb) { adminService.setReloadMasterFilesCallback(cb); }
    public void setSaveOrdersCallback(Runnable cb) { adminService.setSaveOrdersCallback(cb); }
    public void setLoadUsersFromFileCallback(Runnable cb) { adminService.setLoadUsersFromFileCallback(cb); }
    public void setSavePointsCallback(Runnable cb) { adminService.setSavePointsCallback(cb); }
    public void setSeedDefaultUserCallback(Runnable cb) { adminService.setSeedDefaultUserCallback(cb); }
    public void setSaveUsersCallback(Runnable cb) { adminService.setSaveUsersCallback(cb); }
    public void setResetMemberFeatureDataCallback(Consumer<String> cb) { adminService.setResetMemberFeatureDataCallback(cb); }
    public void setSeedMemberFeatureDataCallback(Consumer<String> cb) { adminService.setSeedMemberFeatureDataCallback(cb); }

    // ==================== Store / Service 引用注入 ====================

    public void setUserActivityStore(RuntimeUserActivityStore s) { adminService.setUserActivityStore(s); }
    public void setUserActivityService(UserActivityMockService s) { adminService.setUserActivityService(s); }
    public void setUserPreferenceService(UserPreferenceMockService s) { adminService.setUserPreferenceService(s); }
    public void setCartStore(RuntimeCartStore s) { adminService.setCartStore(s); }

    // ==================== 数据 Map 注入 ====================

    public void setRuntimeUsers(Map<String, JSONObject> m) { adminService.setRuntimeUsers(m); }
    public void setRuntimeOrders(Map<String, JSONObject> m) { adminService.setRuntimeOrders(m); }
    public void setMasterOrders(JSONObject o) { adminService.setMasterOrders(o); }
    public void setMasterCarts(JSONObject o) { adminService.setMasterCarts(o); }
    public void setMasterProducts(JSONObject o) { adminService.setMasterProducts(o); }
    public void setCuratedDemoProducts(JSONObject o) { adminService.setCuratedDemoProducts(o); }

    // ==================== 路径常量注入 ====================

    public void setBrandFollowRuntimeFile(String p) { adminService.setBrandFollowRuntimeFile(p); }
    public void setOrderRuntimeFile(String p) { adminService.setOrderRuntimeFile(p); }
    public void setUserPointsPath(String p) { adminService.setUserPointsPath(p); }

    // ==================== 用户 ID 常量注入 ====================

    public void setDefaultUserId(String id) { adminService.setDefaultUserId(id); }
    public void setCurrentUserId(String id) { adminService.setCurrentUserId(id); }

    // ==================== Admin 端点 ====================

    /**
     * POST /admin/reset-member-data
     * 重置指定用户的收藏和足迹数据（清空后重新 seed）。
     */
    @PostMapping("/admin/reset-member-data")
    public FrontResponse<String> resetMemberData() {
        String result = adminService.resetMemberData();
        return FrontResponse.success(result);
    }

    /**
     * POST /admin/reload
     * 重新加载所有 master 数据文件，无需重启服务。
     */
    @PostMapping("/admin/reload")
    public FrontResponse<String> reloadMasterData() {
        String result = adminService.reloadMasterData();
        return FrontResponse.success(result);
    }
}
