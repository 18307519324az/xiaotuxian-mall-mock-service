package com.xtx.mock.service.auth;

import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * AuthStubMockService — Auth Stub 固定响应服务。
 * <p>
 * 职责：8 个 Auth 相关 stub 端点，返回固定 JSON 响应。
 * <strong>不触碰 runtime 数据、token 生成、用户数据。</strong>
 * <p>
 * 数据源引用（由 MockController @PostConstruct 注入）：
 * <ul>
 *   <li>dataCache — 静态 JSON 缓存（login-code, login, login-social 等）</li>
 * </ul>
 * <p>
 * 遵循 AuthMockService / SearchMockService 提取模式：
 * 构造器无参创建，数据源通过 setter 注入。
 */
@Slf4j
public class AuthStubMockService {

    private Map<String, Object> dataCache;

    public void setDataCache(Map<String, Object> dataCache) {
        this.dataCache = dataCache;
    }

    /**
     * 从 dataCache 查找 mock 数据并返回对象。
     * 与 MockController.ok() 逻辑一致，仅返回数据对象（不包装 FrontResponse）。
     *
     * @param key dataCache key
     * @return 缓存数据对象，未找到时返回空 JSONObject
     */
    private Object ok(String key) {
        Object data = dataCache != null ? dataCache.get(key) : null;
        if (data == null) {
            log.warn("Mock data not found: {}", key);
            return new JSONObject();
        }
        return data;
    }

    // ==================== Auth Stub 端点 ====================

    /** GET /login/code — 获取登录验证码 */
    public Object loginCode(String mobile) {
        return ok("login-code");
    }

    /** POST /login/code — 验证码登录 */
    public Object loginByCode(Map<String, Object> params) {
        return ok("login");
    }

    /** POST /login/social — 社交登录 */
    public Object loginSocial(Map<String, Object> params) {
        return ok("login-social");
    }

    /** GET /login/social/code — 社交登录验证码 */
    public Object loginSocialCode(String mobile) {
        return ok("login-code");
    }

    /** POST /login/social/bind — 社交绑定 */
    public Object loginSocialBind(Map<String, Object> params) {
        return ok("login-social-bind");
    }

    /** POST /login/social/{unionId}/complement — 社交补全信息 */
    public Object loginSocialComplement(String unionId, Map<String, Object> params) {
        return ok("login-social-complement");
    }

    /** GET /register/check — 注册检查 */
    public Object registerCheck(String account) {
        return ok("register-check");
    }

    /** GET /register/code — 注册验证码 */
    public Object registerCode(String mobile) {
        return ok("login-code");
    }
}
