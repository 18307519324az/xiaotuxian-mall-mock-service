package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.mock.service.order.OrderQueryMockService;
import com.xtx.mock.store.RuntimeOrderStore;
import com.xtx.common.core.result.FrontResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * MockOrderQueryController — 订单查询模块控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>GET /member/order               — 订单列表（分页）</li>
 *   <li>GET /member/order/{orderId}     — 订单详情</li>
 *   <li>GET /member/order/{orderId}/logistics — 物流信息</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link com.xtx.mock.controller.MockController} 在 {@code @PostConstruct} 中通过 setter 注入：
 * <ul>
 *   <li>{@link #setRuntimeTokens(Map)} — Token 映射（用于认证）</li>
 *   <li>{@link #setRuntimeOrders(Map)} — 运行时订单数据引用</li>
 *   <li>{@link #setMasterProducts(JSONObject)} — 商品主数据（用于 SKU 补齐）</li>
 *   <li>{@link #setLogisticsData(Object)} — 物流 Mock 数据</li>
 *   <li>{@link #setImageNormalizer(BiFunction)} — 图片 URL 规范化器</li>
 *   <li>{@link #setDefaultFallbackImage(String)} — 缺省图片 URL</li>
 *   <li>{@link #setSaveOrdersCallback(Runnable)} — 订单持久化回调</li>
 * </ul>
 */
@Slf4j
@RestController
public class MockOrderQueryController {

    private RuntimeOrderStore orderStore;
    private OrderQueryMockService orderQueryService;

    // ==================== 注入的依赖引用 ====================

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /** MockController 的 masterProducts 引用（只读） */
    private JSONObject masterProducts;

    /** 物流 Mock 数据 */
    private Object logisticsData;

    /** 图片 URL 规范化器（委托到 MockController.normalizeImageUrl） */
    private BiFunction<String, String, String> imageNormalizer;

    /** 缺省图片 URL（MockController.DEFAULT_FALLBACK_IMAGE） */
    private String defaultFallbackImage;

    /**
     * 构造器：创建 Store 并占位 Service。
     */
    public MockOrderQueryController() {
        this.orderStore = new RuntimeOrderStore();
        log.info("MockOrderQueryController initialized");
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    public void setRuntimeOrders(Map<String, JSONObject> orders) {
        this.orderStore.setRuntimeOrders(orders);
    }

    public void setMasterProducts(JSONObject products) {
        this.masterProducts = products;
    }

    public void setLogisticsData(Object data) {
        this.logisticsData = data;
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
    private OrderQueryMockService getService() {
        if (orderQueryService == null) {
            this.orderQueryService = new OrderQueryMockService(
                    orderStore, masterProducts, imageNormalizer,
                    defaultFallbackImage, logisticsData);
        }
        return orderQueryService;
    }

    /**
     * 后台关闭已到达付款截止时间的待付款订单。
     */
    @Scheduled(
            initialDelayString = "${mock.order-expiration-initial-delay-ms:1000}",
            fixedDelayString = "${mock.order-expiration-scan-ms:1000}")
    public void expirePendingOrdersOnSchedule() {
        int expiredCount = getService().expireAllPendingOrders();
        if (expiredCount > 0) {
            log.info("后台付款超时扫描已取消 {} 个订单", expiredCount);
        }
    }

    // ==================== GET /member/order ====================

    /**
     * GET /member/order — 订单列表（分页）。
     */
    @GetMapping("/member/order")
    public Object orderList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(defaultValue = "0") Integer orderState,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        JSONObject result = getService().list(uid, page, pageSize, orderState);
        return FrontResponse.success(result);
    }

    // ==================== GET /member/order/{orderId} ====================

    /**
     * GET /member/order/{orderId} — 订单详情。
     */
    @GetMapping("/member/order/{orderId}")
    public Object orderDetail(
            @PathVariable String orderId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        JSONObject result = getService().detail(orderId, uid);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在: " + orderId);
        }
        return FrontResponse.success(result);
    }

    // ==================== GET /member/order/{orderId}/logistics ====================

    /**
     * GET /member/order/{orderId}/logistics — 物流信息。
     */
    @GetMapping("/member/order/{orderId}/logistics")
    public Object orderLogistics(
            @PathVariable String orderId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        Object result = getService().logistics(orderId, uid);
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
}
