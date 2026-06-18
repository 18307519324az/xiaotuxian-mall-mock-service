package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.common.core.result.FrontResponse;
import com.xtx.mock.service.auth.AuthMockService;
import com.xtx.mock.service.auth.AuthMockService.AuthServiceException;
import com.xtx.mock.service.invite.InviteMockService;
import com.xtx.mock.service.points.PointsMockService;
import com.xtx.mock.store.RuntimePointsStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * MockAuthController — Auth Core 认证模块控制器。
 * <p>
 * 负责 7 个 Auth Core 端点：
 * <ul>
 *   <li>POST /member/register — 注册</li>
 *   <li>POST /login — 登录</li>
 *   <li>GET /member/profile — 获取资料</li>
 *   <li>PUT /member/profile — 更新资料</li>
 *   <li>PUT /member/password — 修改密码</li>
 *   <li>POST /member/logout — 退出登录</li>
 *   <li>PUT /member/mobile — 绑定手机号</li>
 * </ul>
 * <p>
 * 遵循 MockGoodsController / MockHomeCatalogController 提取模式：
 * 构造器创建 AuthMockService，数据源通过 @PostConstruct 由 MockController 注入。
 */
@Slf4j
@RestController
public class MockAuthController {

    private final AuthMockService authService;

    public MockAuthController() {
        this.authService = new AuthMockService();
        log.info("MockAuthController initialized");
    }

    /** 暴露 Service 供 MockController @PostConstruct 注入数据源 */
    public AuthMockService getService() {
        return authService;
    }

    // ==================== 数据源注入（由 MockController @PostConstruct 调用） ====================

    public void setRuntimeUsers(Map<String, JSONObject> runtimeUsers) { authService.setRuntimeUsers(runtimeUsers); }
    public void setRuntimeTokens(Map<String, String> runtimeTokens) { authService.setRuntimeTokens(runtimeTokens); }
    public void setSaveUsersCallback(Runnable callback) { authService.setSaveUsersCallback(callback); }
    public void setInviteService(InviteMockService inviteService) { authService.setInviteService(inviteService); }
    public void setPointsService(PointsMockService pointsService) { authService.setPointsService(pointsService); }
    public void setPointsStore(RuntimePointsStore pointsStore) { authService.setPointsStore(pointsStore); }
    public void setFeatureCounter(AtomicInteger featureCounter) { authService.setFeatureCounter(featureCounter); }
    public void setInitNewUserCallback(Consumer<String> callback) { authService.setInitNewUserCallback(callback); }

    // ==================== Auth Core 端点 ====================

    /**
     * POST /member/register — 注册新用户
     */
    @PostMapping("/member/register")
    public FrontResponse<Object> register(@RequestBody Map<String, Object> params) {
        try {
            JSONObject result = authService.register(params);
            return FrontResponse.success(result);
        } catch (AuthServiceException e) {
            return FrontResponse.failure(e.getCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return FrontResponse.failure(e.getMessage());
        }
    }

    /**
     * POST /login — 真实凭据校验
     */
    @PostMapping("/login")
    public FrontResponse<Object> login(@RequestBody Map<String, Object> params) {
        try {
            JSONObject result = authService.login(params);
            return FrontResponse.success(result);
        } catch (IllegalArgumentException e) {
            return FrontResponse.failure(e.getMessage());
        }
    }

    /**
     * GET /member/profile — 获取用户资料（不含密码）
     */
    @GetMapping("/member/profile")
    public FrontResponse<Object> memberProfile(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            String uid = authService.requireUserId(authHeader);
            JSONObject result = authService.getProfile(uid);
            return FrontResponse.success(result);
        } catch (IllegalArgumentException e) {
            return FrontResponse.failure(e.getMessage());
        }
    }

    /**
     * PUT /member/profile — 更新用户资料
     */
    @PutMapping("/member/profile")
    public FrontResponse<Object> memberProfileUpdate(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                      @RequestBody Map<String, Object> params) {
        try {
            String uid = authService.requireUserId(authHeader);
            JSONObject result = authService.updateProfile(uid, params);
            return FrontResponse.success(result);
        } catch (IllegalArgumentException e) {
            return FrontResponse.failure(e.getMessage());
        }
    }

    /**
     * PUT /member/password — 修改密码
     */
    @PutMapping("/member/password")
    public FrontResponse<Object> memberPassword(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                 @RequestBody Map<String, Object> params) {
        try {
            String uid = authService.requireUserId(authHeader);
            String oldPassword = params.get("oldPassword") != null ? params.get("oldPassword").toString() : "";
            String newPassword = params.get("newPassword") != null ? params.get("newPassword").toString() : "";
            authService.changePassword(uid, oldPassword, newPassword);
            return FrontResponse.success(Boolean.TRUE);
        } catch (IllegalArgumentException e) {
            return FrontResponse.failure(e.getMessage());
        }
    }

    /**
     * POST /member/logout — 退出登录，移除 token
     */
    @PostMapping("/member/logout")
    public FrontResponse<Object> memberLogout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        // 先验证 token 有效
        authService.requireUserId(authHeader);
        authService.logout(authHeader);
        return FrontResponse.success(Boolean.TRUE);
    }

    /**
     * PUT /member/mobile — 绑定/修改手机号
     */
    @PutMapping("/member/mobile")
    public FrontResponse<Object> memberMobileBind(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                   @RequestBody Map<String, Object> params) {
        try {
            String uid = authService.requireUserId(authHeader);
            String mobile = params.get("mobile") != null ? params.get("mobile").toString() : "";
            JSONObject result = authService.bindMobile(uid, mobile);
            return FrontResponse.success(result);
        } catch (IllegalArgumentException e) {
            return FrontResponse.failure(e.getMessage());
        }
    }
}
