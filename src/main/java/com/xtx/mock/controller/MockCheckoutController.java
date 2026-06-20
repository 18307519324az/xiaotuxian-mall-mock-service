package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.mock.service.checkout.CheckoutMockService;
import com.xtx.mock.store.RuntimeAddressStore;
import com.xtx.mock.store.RuntimeBenefitStore;
import com.xtx.mock.store.RuntimeCartStore;
import com.xtx.common.core.result.FrontResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * MockCheckoutController — 预结算/结算预览控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>GET /member/order/pre — 订单预览</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link com.xtx.mock.controller.MockController} 在 {@code @PostConstruct} 中通过 setter 注入：
 * <ul>
 *   <li>{@link #setRuntimeTokens(Map)} — Token 映射（用于认证）</li>
 *   <li>{@link #setMasterSkus(JSONObject)} — SKU 主数据（只读）</li>
 * </ul>
 */
@Slf4j
@RestController
public class MockCheckoutController {

    private CheckoutMockService checkoutService;

    // ==================== 注入的依赖引用 ====================

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /** MockController 的 masterSkus 引用（只读） */
    private JSONObject masterSkus;

    /** MockController 的 runtimeCartStore 引用（@PostConstruct 注入） */
    private RuntimeCartStore cartStore;

    /** MockController 的 addressStore 引用（@PostConstruct 注入） */
    private RuntimeAddressStore addressStore;

    /** MockController 的 benefitStore 引用（@PostConstruct 注入） */
    private RuntimeBenefitStore benefitStore;

    /**
     * 构造器：由 Spring 实例化，不接收构造参数。
     * Service 在首次访问时由 getService() lazy 创建。
     */
    public MockCheckoutController() {
        log.info("MockCheckoutController initialized (no-arg)");
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    public void setMasterSkus(JSONObject skus) {
        this.masterSkus = skus;
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

    /**
     * 延迟初始化 Service（确保 setter 注入完成后才创建）。
     */
    public CheckoutMockService getService() {
        if (checkoutService == null) {
            this.checkoutService = new CheckoutMockService(cartStore, addressStore, benefitStore);
        }
        return checkoutService;
    }

    // ==================== GET /member/order/pre ====================

    /**
     * GET /member/order/pre — 订单预览（按 userId 隔离）。
     * 从购物车中读取 selected=true 的商品，生成预结算信息。
     */
    @GetMapping("/member/order/pre")
    public Object orderPre(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        JSONObject result = getService().queryOrderPre(uid, masterSkus);
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
