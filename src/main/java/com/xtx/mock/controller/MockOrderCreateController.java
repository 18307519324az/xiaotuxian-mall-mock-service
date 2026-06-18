package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.mock.service.order.OrderCreateMockService;
import com.xtx.mock.store.RuntimeAddressStore;
import com.xtx.mock.store.RuntimeBenefitStore;
import com.xtx.mock.store.RuntimeCartStore;
import com.xtx.mock.store.RuntimeOrderStore;
import com.xtx.common.core.result.FrontResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * MockOrderCreateController — 订单创建模块控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>POST /member/order — 提交订单（完整创建流程）</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link com.xtx.mock.controller.MockController} 在 {@code @PostConstruct} 中通过 setter 注入：
 * <ul>
 *   <li>{@link #setRuntimeTokens(Map)}           — Token 映射（用于认证）</li>
 *   <li>{@link #setRuntimeOrders(Map)}           — 运行时订单数据引用</li>
 *   <li>{@link #setSaveOrdersCallback(Runnable)} — 订单持久化回调</li>
 *   <li>{@link #setCartStore(RuntimeCartStore)}              — 购物车 Store（读 selected + 清除已结算）</li>
 *   <li>{@link #setAddressStore(RuntimeAddressStore)}        — 地址 Store（校验地址归属）</li>
 *   <li>{@link #setBenefitStore(RuntimeBenefitStore)}        — 优惠券/礼品卡 Store（抵扣计算）</li>
 *   <li>{@link #setMasterSkus(JSONObject)}                   — SKU 主数据只读引用</li>
 *   <li>{@link #setMasterProducts(JSONObject)}               — 商品主数据只读引用</li>
 *   <li>{@link #setOrderCounter(AtomicInteger)}              — 订单 ID 自增计数器（共享）</li>
 *   <li>{@link #setImageNormalizer(BiFunction)}              — 图片 URL 规范化器</li>
 *   <li>{@link #setDefaultFallbackImage(String)}             — 缺省图片 URL</li>
 *   <li>{@link #setFormatSpecsHelper(Function)}              — 规格格式化器</li>
 * </ul>
 * <p>
 * 不创建其他模块的数据（不处理购物车添加、不处理地址新增、不处理优惠券兑换）。
 */
@Slf4j
@RestController
public class MockOrderCreateController {

    private RuntimeOrderStore orderStore;
    private OrderCreateMockService createService;

    // ==================== 注入的依赖引用 ====================

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /** MockController 的 CartStore 引用 */
    private RuntimeCartStore cartStore;

    /** MockController 的 AddressStore 引用 */
    private RuntimeAddressStore addressStore;

    /** MockController 的 BenefitStore 引用 */
    private RuntimeBenefitStore benefitStore;

    /** MockController 的 masterSkus 引用（只读，用于价格校验） */
    private JSONObject masterSkus;

    /** MockController 的 masterProducts 引用（只读，用于 SPU 名称解析） */
    private JSONObject masterProducts;

    /** MockController 的 orderCounter 引用（共享自增计数器） */
    private AtomicInteger orderCounter;

    /** 图片 URL 规范化器（委托到 MockController.normalizeImageUrl） */
    private BiFunction<String, String, String> imageNormalizer;

    /** 缺省图片 URL（MockController.DEFAULT_FALLBACK_IMAGE） */
    private String defaultFallbackImage;

    /** 规格格式化器（委托到 MockController.formatSpecsText） */
    private Function<Object, String> formatSpecsHelper;

    // ==================== 用户级写锁（防止并发订单创建重复） ====================

    /** 用户级订单写锁，同一用户串行化订单创建操作。 */
    private final ConcurrentHashMap<String, Object> orderWriteLocks = new ConcurrentHashMap<>();

    /**
     * 构造器：创建 Store 并占位 Service。
     */
    public MockOrderCreateController() {
        this.orderStore = new RuntimeOrderStore();
        log.info("MockOrderCreateController initialized");
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    public void setRuntimeOrders(Map<String, JSONObject> orders) {
        this.orderStore.setRuntimeOrders(orders);
    }

    public void setSaveOrdersCallback(Runnable callback) {
        this.orderStore.setSaveOrdersCallback(callback);
    }

    public void setCartStore(RuntimeCartStore store) {
        this.cartStore = store;
    }

    public void setAddressStore(RuntimeAddressStore store) {
        this.addressStore = store;
    }

    public void setBenefitStore(RuntimeBenefitStore store) {
        this.benefitStore = store;
    }

    public void setMasterSkus(JSONObject skus) {
        this.masterSkus = skus;
    }

    public void setMasterProducts(JSONObject products) {
        this.masterProducts = products;
    }

    public void setOrderCounter(AtomicInteger counter) {
        this.orderCounter = counter;
    }

    public void setImageNormalizer(BiFunction<String, String, String> normalizer) {
        this.imageNormalizer = normalizer;
    }

    public void setDefaultFallbackImage(String fallback) {
        this.defaultFallbackImage = fallback;
    }

    public void setFormatSpecsHelper(Function<Object, String> helper) {
        this.formatSpecsHelper = helper;
    }

    // ==================== Service 延迟初始化 ====================

    /**
     * 延迟初始化 Service（确保所有 setter 注入完成后才创建）。
     */
    private OrderCreateMockService getService() {
        if (createService == null) {
            this.createService = new OrderCreateMockService(
                    orderStore, cartStore, addressStore, benefitStore,
                    masterSkus, masterProducts, orderCounter,
                    imageNormalizer, defaultFallbackImage, formatSpecsHelper);
        }
        return createService;
    }

    // ==================== POST /member/order ====================

    /**
     * POST /member/order — 提交订单。
     * <p>
     * 流程详见 {@link OrderCreateMockService#createOrder(String, Map)}。
     */
    @PostMapping("/member/order")
    public Object orderSubmit(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> params) {
        String userId = requireUserId(authHeader);
        // 用户级写锁：确保同一用户并发提交订单不重复创建
        synchronized (orderWriteLocks.computeIfAbsent(userId, k -> new Object())) {
            try {
                JSONObject result = getService().createOrder(userId, params);
                return FrontResponse.success(result);
            } catch (IllegalArgumentException e) {
                return FrontResponse.failure(e.getMessage());
            }
        }
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
