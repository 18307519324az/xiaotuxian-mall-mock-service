package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.mock.service.order.OrderCreateMockService;
import com.xtx.mock.service.order.StockChangeLogService;
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
 */
@Slf4j
@RestController
public class MockOrderCreateController {

    private RuntimeOrderStore orderStore;
    private OrderCreateMockService createService;
    private StockChangeLogService stockChangeLogService;

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
        this.stockChangeLogService = new StockChangeLogService();
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

    public void setStockChangeLogService(StockChangeLogService service) {
        this.stockChangeLogService = service;
    }

    // ==================== Service 延迟初始化 ====================

    private OrderCreateMockService getService() {
        if (createService == null) {
            this.createService = new OrderCreateMockService(
                    orderStore, cartStore, addressStore, benefitStore,
                    masterSkus, masterProducts, orderCounter,
                    imageNormalizer, defaultFallbackImage, formatSpecsHelper,
                    stockChangeLogService);
        }
        return createService;
    }

    // ==================== POST /member/order ====================

    @PostMapping("/member/order")
    public Object orderSubmit(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> params) {
        String userId = requireUserId(authHeader);
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

    private String requireUserId(String authHeader) {
        String userId = getUserIdFromToken(authHeader);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }

    private String getUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) return null;
        return runtimeTokens.get(token);
    }
}
