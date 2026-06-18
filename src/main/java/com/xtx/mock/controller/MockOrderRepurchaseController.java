package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.mock.service.order.OrderRepurchaseMockService;
import com.xtx.mock.store.RuntimeAddressStore;
import com.xtx.mock.store.RuntimeOrderStore;
import com.xtx.common.core.result.FrontResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * MockOrderRepurchaseController — 再次购买模块控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>GET /member/order/repurchase/{orderId} — 根据历史订单生成再购买预订单数据</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link com.xtx.mock.controller.MockController} 在 {@code @PostConstruct} 中通过 setter 注入：
 * <ul>
 *   <li>{@link #setRuntimeTokens(Map)} — Token 映射（用于认证）</li>
 *   <li>{@link #setRuntimeOrders(Map)} — 运行时订单数据引用</li>
 *   <li>{@link #setMasterSkus(JSONObject)} — SKU 主数据引用（用于价格/库存/图片补齐）</li>
 *   <li>{@link #setAddressStore(RuntimeAddressStore)} — 地址 Store（获取用户地址列表）</li>
 *   <li>{@link #setSpecsFormatter(Function)} — 规格格式化器（委托到 MockController.formatSpecsText）</li>
 *   <li>{@link #setImageNormalizer(BiFunction)} — 图片 URL 规范化器</li>
 *   <li>{@link #setDefaultFallbackImage(String)} — 缺省图片 URL</li>
 *   <li>{@link #setSaveOrdersCallback(Runnable)} — 订单持久化回调（仅用于 store 兼容）</li>
 * </ul>
 */
@Slf4j
@RestController
public class MockOrderRepurchaseController {

    private RuntimeOrderStore orderStore;
    private OrderRepurchaseMockService repurchaseService;

    // ==================== 注入的依赖引用 ====================

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /** MockController 的 masterSkus 引用（只读） */
    private JSONObject masterSkus;

    /** MockController 的 addressStore 引用 */
    private RuntimeAddressStore addressStore;

    /** 规格格式化委托（MockController.formatSpecsText） */
    private Function<Object, String> specsFormatter;

    /** 图片 URL 规范化器（MockController.normalizeImageUrl） */
    private BiFunction<String, String, String> imageNormalizer;

    /** 缺省图片 URL（MockController.DEFAULT_FALLBACK_IMAGE） */
    private String defaultFallbackImage;

    /**
     * 构造器：创建 Store 并占位 Service。
     */
    public MockOrderRepurchaseController() {
        this.orderStore = new RuntimeOrderStore();
        log.info("MockOrderRepurchaseController initialized");
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    public void setRuntimeOrders(Map<String, JSONObject> orders) {
        this.orderStore.setRuntimeOrders(orders);
    }

    public void setMasterSkus(JSONObject skus) {
        this.masterSkus = skus;
    }

    public void setAddressStore(RuntimeAddressStore store) {
        this.addressStore = store;
    }

    public void setSpecsFormatter(Function<Object, String> formatter) {
        this.specsFormatter = formatter;
    }

    public void setImageNormalizer(BiFunction<String, String, String> normalizer) {
        this.imageNormalizer = normalizer;
    }

    public void setDefaultFallbackImage(String fallback) {
        this.defaultFallbackImage = fallback;
    }

    public void setSaveOrdersCallback(Runnable callback) {
        this.orderStore.setSaveOrdersCallback(callback);
    }

    // ==================== Service 延迟初始化 ====================

    /**
     * 延迟初始化 Service（确保 setter 注入完成后才创建）。
     */
    private OrderRepurchaseMockService getService() {
        if (repurchaseService == null) {
            this.repurchaseService = new OrderRepurchaseMockService(
                    orderStore, masterSkus, addressStore,
                    specsFormatter, imageNormalizer, defaultFallbackImage);
        }
        return repurchaseService;
    }

    // ==================== GET /member/order/repurchase/{orderId} ====================

    /**
     * GET /member/order/repurchase/{orderId} — 根据历史订单生成再购买预订单数据。
     */
    @GetMapping("/member/order/repurchase/{orderId}")
    public Object orderRepurchase(
            @PathVariable String orderId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        JSONObject result = getService().buildRepurchase(orderId, uid);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在: " + orderId);
        }
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

    // ==================== Store access for MockController wiring ====================

    public RuntimeOrderStore getStore() {
        return orderStore;
    }
}
