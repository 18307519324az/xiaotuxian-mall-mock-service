package com.xtx.mock.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.service.cart.CartMockService;
import com.xtx.mock.store.RuntimeCartStore;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MockCartController — 购物车控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>GET    /member/cart            — 购物车列表</li>
 *   <li>POST   /member/cart            — 添加购物车</li>
 *   <li>DELETE /member/cart            — 删除购物车</li>
 *   <li>PUT    /member/cart/{skuId}    — 更新购物车</li>
 *   <li>POST   /member/cart/merge      — 合并购物车</li>
 *   <li>PUT    /member/cart/selected   — 批量全选/取消全选</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link com.xtx.mock.controller.MockController} 在 {@code @PostConstruct} 中通过 setter 注入：
 * <ul>
 *   <li>{@link #setRuntimeTokens(Map)} — Token 映射（用于认证）</li>
 *   <li>{@link #setMasterSkus(JSONObject)} — SKU 主数据（只读）</li>
 *   <li>{@link #setMasterProducts(JSONObject)} — 商品主数据（只读）</li>
 * </ul>
 */
@Slf4j
@RestController
public class MockCartController {

    private final RuntimeCartStore cartStore;
    private final CartMockService cartService;

    // ==================== 用户级写锁（防止并发购物车操作丢失） ====================

    /** 用户级购物车写锁，同一用户串行化购物车操作。 */
    private final ConcurrentHashMap<String, Object> cartWriteLocks = new ConcurrentHashMap<>();

    // ==================== 注入的依赖引用 ====================

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /** MockController 的 masterSkus 引用（只读） */
    private JSONObject masterSkus;

    /** MockController 的 masterProducts 引用（只读） */
    private JSONObject masterProducts;

    /**
     * 构造器：创建 Store 和 Service 并加载持久化数据。
     */
    public MockCartController() {
        this.cartStore = new RuntimeCartStore();
        this.cartService = new CartMockService(cartStore);
        log.info("MockCartController initialized");
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    public void setMasterSkus(JSONObject skus) {
        this.masterSkus = skus;
    }

    public void setMasterProducts(JSONObject products) {
        this.masterProducts = products;
    }

    /**
     * 返回底层 Store 引用，供其他模块在重置/初始化时操作购物车数据。
     */
    public RuntimeCartStore getStore() {
        return cartStore;
    }

    /**
     * 返回底层 Service 引用，供其他模块调用购物车业务逻辑。
     */
    public CartMockService getService() {
        return cartService;
    }

    // ==================== GET /member/cart ====================

    /**
     * GET /member/cart — 购物车列表（按 userId 隔离）。
     */
    @GetMapping("/member/cart")
    public Object cartList(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        JSONArray result = cartService.queryCartList(uid, masterSkus);
        return FrontResponse.success(result);
    }

    // ==================== POST /member/cart ====================

    /**
     * POST /member/cart — 添加购物车。
     * <p>
     * 已存在同一 SKU 时累加数量，否则从 master 数据构建新条目。
     */
    @PostMapping("/member/cart")
    public Object cartInsert(
            @RequestBody Map<String, Object> params,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        Object skuIdObj = params.get("skuId");
        String skuId = skuIdObj == null ? null : skuIdObj.toString();
        int count = params.containsKey("count") ? ((Number) params.get("count")).intValue() : 1;

        // 用户级写锁：确保同一用户并发加入购物车不丢失数量
        synchronized (cartWriteLocks.computeIfAbsent(uid, k -> new Object())) {
            String error = cartService.addToCart(uid, skuId, count, masterSkus, masterProducts);
            if (error != null) {
                return FrontResponse.failure(error);
            }
            return FrontResponse.success(cartService.queryCartList(uid, masterSkus));
        }
    }

    // ==================== DELETE /member/cart ====================

    /**
     * DELETE /member/cart — 删除购物车。
     * 支持按 cart entry key 或 skuId 删除。
     */
    @DeleteMapping("/member/cart")
    public Object cartDelete(
            @RequestBody Map<String, Object> params,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Object idsObj = params != null ? params.get("ids") : null;
        String uid = resolveUserId(params);
        if (uid == null) uid = getUserIdFromToken(authHeader);
        if (uid == null) uid = "";

        if (idsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<?> ids = (List<?>) idsObj;
            synchronized (cartWriteLocks.computeIfAbsent(uid, k -> new Object())) {
                cartService.deleteFromCart(uid, ids);
            }
        }
        return FrontResponse.success(Boolean.TRUE);
    }

    // ==================== PUT /member/cart/{skuId} ====================

    /**
     * PUT /member/cart/{skuId} — 更新购物车（selected/count）。
     */
    @PutMapping("/member/cart/{skuId}")
    public Object cartUpdate(
            @PathVariable String skuId,
            @RequestBody Map<String, Object> params,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        synchronized (cartWriteLocks.computeIfAbsent(uid, k -> new Object())) {
            boolean found = cartService.updateCart(uid, skuId, params, masterSkus);
            if (!found) {
                return FrontResponse.failure("购物车未找到该商品");
            }
            return FrontResponse.success(Boolean.TRUE);
        }
    }

    // ==================== POST /member/cart/merge ====================

    /**
     * POST /member/cart/merge — 合并购物车。
     * 从 body 中提取 userId，去重累加本地购物车项。
     */
    @PostMapping("/member/cart/merge")
    public Object cartMerge(@RequestBody List<Map<String, Object>> cartList) {
        String uid = cartService.mergeCart(cartList, masterSkus, masterProducts);
        return FrontResponse.success(cartService.queryCartList(uid, masterSkus));
    }

    // ==================== PUT /member/cart/selected ====================

    /**
     * PUT /member/cart/selected — 批量全选/取消全选。
     */
    @PutMapping("/member/cart/selected")
    public Object cartSelected(
            @RequestBody Map<String, Object> params,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        boolean selected = params.containsKey("selected") && Boolean.TRUE.equals(params.get("selected"));
        Object idsObj = params.get("ids");
        String userId = resolveUserId(params);
        if (userId == null) userId = getUserIdFromToken(authHeader);
        if (userId == null) userId = "";

        if (idsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) idsObj;
            synchronized (cartWriteLocks.computeIfAbsent(userId, k -> new Object())) {
                cartService.setSelected(userId, selected, ids);
            }
        }
        return FrontResponse.success(Boolean.TRUE);
    }

    // ==================== Auth ====================

    /**
     * 从请求参数解析用户 ID（用于无 auth header 的接口）。
     */
    private String resolveUserId(Map<String, Object> params) {
        if (params != null && params.get("userId") != null) {
            return params.get("userId").toString();
        }
        return null;
    }

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
