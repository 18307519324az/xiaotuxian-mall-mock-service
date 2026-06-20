package com.xtx.mock.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.service.address.AddressMockService;
import com.xtx.mock.store.RuntimeAddressStore;
import com.xtx.common.core.result.FrontResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * MockAddressController — 地址模块控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>GET    /member/address          — 地址列表</li>
 *   <li>POST   /member/address          — 新增地址</li>
 *   <li>PUT    /member/address/{id}     — 修改地址</li>
 *   <li>DELETE /member/address/{id}     — 删除地址</li>
 *   <li>POST   /admin/reset-addresses   — 恢复 seed 地址</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link com.xtx.mock.controller.MockController} 在 {@code @PostConstruct} 中通过 setter 注入：
 * <ul>
 *   <li>{@link #setRuntimeTokens(Map)} — Token 映射（用于认证）</li>
 * </ul>
 */
@Slf4j
@RestController
public class MockAddressController {

    private final RuntimeAddressStore addressStore;
    private final AddressMockService addressService;

    // ==================== 注入的依赖引用 ====================

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /**
     * 构造器：创建 Store 和 Service 并初始化持久化数据。
     */
    public MockAddressController() {
        this.addressStore = new RuntimeAddressStore();
        this.addressService = new AddressMockService(addressStore);
        log.info("MockAddressController initialized");
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    /**
     * 返回底层 Store 引用，供其他模块在地址读取/校验时使用。
     */
    public RuntimeAddressStore getStore() {
        return addressStore;
    }

    /**
     * 返回底层 Service 引用，供其他模块调用地址业务逻辑。
     */
    public AddressMockService getService() {
        return addressService;
    }

    // ==================== GET /member/address ====================

    /**
     * GET /member/address — 地址列表（按 userId 隔离）。
     */
    @GetMapping("/member/address")
    public Object addressList(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        JSONArray result = addressService.listByUserId(uid);
        return FrontResponse.success(result);
    }

    // ==================== POST /member/address ====================

    /**
     * POST /member/address — 新增地址，返回完整地址对象。
     */
    @PostMapping("/member/address")
    public Object addressAdd(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        JSONObject addr = addressService.add(params, uid);
        return FrontResponse.success(addr);
    }

    // ==================== PUT /member/address/{id} ====================

    /**
     * PUT /member/address/{id} — 修改地址，返回修改后的地址对象。
     */
    @PutMapping("/member/address/{id}")
    public Object addressEdit(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        JSONObject existing = addressService.update(id, params, uid);
        if (existing == null) return FrontResponse.failure("地址不存在或无权操作");
        return FrontResponse.success(existing);
    }

    // ==================== DELETE /member/address/{id} ====================

    /**
     * DELETE /member/address/{id} — 删除地址。
     */
    @DeleteMapping("/member/address/{id}")
    public Object addressDelete(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        boolean deleted = addressService.delete(id, uid);
        if (!deleted) return FrontResponse.failure("地址不存在或无权操作");
        return FrontResponse.success(Boolean.TRUE);
    }

    // ==================== POST /admin/reset-addresses ====================

    /**
     * POST /admin/reset-addresses — 恢复 seed 地址。
     */
    @PostMapping("/admin/reset-addresses")
    public Object resetAddresses() {
        addressService.resetToSeed();
        JSONObject result = new JSONObject();
        result.set("count", addressService.count());
        result.set("message", "地址已重置为 seed 数据");
        return FrontResponse.success(result);
    }

    // ==================== Auth ====================

    /**
     * 要求请求必须携带有效 token，否则抛出 401。
     */
    private String requireUserId(String authHeader) {
        String userId = getUserIdFromToken(authHeader);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }

    /**
     * 从 Authorization header 提取 userId。
     */
    private String getUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) return null;
        return runtimeTokens.get(token);
    }
}
