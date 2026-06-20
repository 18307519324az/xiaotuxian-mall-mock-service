package com.xtx.mock.controller;

import com.xtx.common.core.result.FrontResponse;
import com.xtx.mock.service.auth.AuthStubMockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MockAuthStubController — Auth Stub 模块控制器。
 * <p>
 * 负责 8 个 Auth Stub 端点（固定响应，不触碰 runtime 数据）：
 * <ul>
 *   <li>GET  /login/code — 获取登录验证码</li>
 *   <li>POST /login/code — 验证码登录</li>
 *   <li>POST /login/social — 社交登录</li>
 *   <li>GET  /login/social/code — 社交登录验证码</li>
 *   <li>POST /login/social/bind — 社交绑定</li>
 *   <li>POST /login/social/{unionId}/complement — 社交补全信息</li>
 *   <li>GET  /register/check — 注册检查</li>
 *   <li>GET  /register/code — 注册验证码</li>
 * </ul>
 * <p>
 * 遵循 MockGoodsController / MockAuthController 提取模式：
 * 构造器创建 AuthStubMockService，数据源通过 @PostConstruct 由 MockController 注入。
 */
@Slf4j
@RestController
public class MockAuthStubController {

    private final AuthStubMockService stubService;

    public MockAuthStubController() {
        this.stubService = new AuthStubMockService();
        log.info("MockAuthStubController initialized");
    }

    /** 暴露 Service 供 MockController @PostConstruct 注入数据源 */
    public AuthStubMockService getService() {
        return stubService;
    }

    /** 注入 dataCache（仅读取静态 JSON，不写 runtime） */
    public void setDataCache(Map<String, Object> dataCache) {
        stubService.setDataCache(dataCache);
    }

    // ==================== Auth Stub 端点 ====================

    /**
     * GET /login/code — 获取登录验证码
     */
    @GetMapping("/login/code")
    public FrontResponse<Object> loginCode(@RequestParam String mobile) {
        return FrontResponse.success(stubService.loginCode(mobile));
    }

    /**
     * POST /login/code — 验证码登录
     */
    @PostMapping("/login/code")
    public FrontResponse<Object> loginByCode(@RequestBody Map<String, Object> params) {
        return FrontResponse.success(stubService.loginByCode(params));
    }

    /**
     * POST /login/social — 社交登录
     */
    @PostMapping("/login/social")
    public FrontResponse<Object> loginSocial(@RequestBody Map<String, Object> params) {
        return FrontResponse.success(stubService.loginSocial(params));
    }

    /**
     * GET /login/social/code — 社交登录验证码
     */
    @GetMapping("/login/social/code")
    public FrontResponse<Object> loginSocialCode(@RequestParam String mobile) {
        return FrontResponse.success(stubService.loginSocialCode(mobile));
    }

    /**
     * POST /login/social/bind — 社交绑定
     */
    @PostMapping("/login/social/bind")
    public FrontResponse<Object> loginSocialBind(@RequestBody Map<String, Object> params) {
        return FrontResponse.success(stubService.loginSocialBind(params));
    }

    /**
     * POST /login/social/{unionId}/complement — 社交补全信息
     */
    @PostMapping("/login/social/{unionId}/complement")
    public FrontResponse<Object> loginSocialComplement(@PathVariable String unionId,
                                                        @RequestBody Map<String, Object> params) {
        return FrontResponse.success(stubService.loginSocialComplement(unionId, params));
    }

    /**
     * GET /register/check — 注册检查
     */
    @GetMapping("/register/check")
    public FrontResponse<Object> registerCheck(@RequestParam String account) {
        return FrontResponse.success(stubService.registerCheck(account));
    }

    /**
     * GET /register/code — 注册验证码
     */
    @GetMapping("/register/code")
    public FrontResponse<Object> registerCode(@RequestParam String mobile) {
        return FrontResponse.success(stubService.registerCode(mobile));
    }
}
