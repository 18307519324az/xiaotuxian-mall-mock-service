package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.common.core.result.FrontResponse;
import com.xtx.mock.service.BenefitMockService;
import com.xtx.mock.store.RuntimeBenefitStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * MockBenefitController — 优惠券和礼品卡模块控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>GET  /member/coupon         — 获取当前用户优惠券列表</li>
 *   <li>POST /member/coupon/exchange — 兑换优惠券</li>
 *   <li>GET  /member/gift-card      — 获取当前用户礼品卡列表</li>
 *   <li>POST /member/gift-card/bind  — 绑定礼品卡</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link MockController} 在 {@code @PostConstruct} 中通过
 * {@link #setRuntimeTokens(Map)} 注入。
 * <p>
 * MockController 通过 {@link #getStore()} 访问底层数据，
 * 用于订单创建流程中读取和修改优惠券/礼品卡状态。
 */
@Slf4j
@RestController
public class MockBenefitController {

    private final RuntimeBenefitStore benefitStore;
    private final BenefitMockService benefitService;

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /**
     * 构造器：创建 Store 和 Service 并加载持久化数据。
     * Spring 实例化时自动调用。
     */
    public MockBenefitController() {
        this.benefitStore = new RuntimeBenefitStore();
        this.benefitService = new BenefitMockService(benefitStore);
        this.benefitStore.loadFromFile();
        log.info("MockBenefitController initialized, store loaded from file");
    }

    /**
     * 由 MockController 在 @PostConstruct 中调用，注入运行时 token 引用。
     *
     * @param tokens MockController 的 runtimeTokens (Map<String, String>)
     */
    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
        log.info("MockBenefitController received runtime tokens reference");
    }

    /**
     * 返回底层 Store 引用，供 MockController 在订单创建流程中读取/修改数据。
     */
    public RuntimeBenefitStore getStore() {
        return benefitStore;
    }

    // ==================== 优惠券列表 ====================

    /**
     * GET /member/coupon — 获取当前用户优惠券列表。
     * <p>
     * 保持响应结构：
     * <pre>
     * { "code": "20000", "msg": "成功", "result": { "items": [...], "summary": {...} } }
     * </pre>
     */
    @GetMapping("/member/coupon")
    public FrontResponse<Object> memberCoupons(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        JSONObject result = benefitService.getCoupons(uid);
        return FrontResponse.success(result);
    }

    // ==================== 兑换优惠券 ====================

    /**
     * POST /member/coupon/exchange — 兑换优惠券。
     * <p>
     * 请求体：{ "code": "XTX-XXXX" }
     * <p>
     * 校验规则：
     * <ul>
     *   <li>code 不能为空 → {"code": "50000", "msg": "兑换码不能为空"}</li>
     *   <li>code 不以 XTX 开头 → {"code": "50000", "msg": "兑换码无效"}</li>
     *   <li>成功 → 返回新优惠券对象</li>
     * </ul>
     */
    @PostMapping("/member/coupon/exchange")
    public FrontResponse<Object> exchangeCoupon(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        String code = params != null && params.get("code") != null
                ? params.get("code").toString().trim().toUpperCase() : "";
        if (code.isEmpty()) {
            return FrontResponse.failure("兑换码不能为空");
        }
        if (!code.startsWith("XTX")) {
            return FrontResponse.failure("兑换码无效");
        }
        JSONObject coupon = benefitService.exchangeCoupon(uid, code);
        return FrontResponse.success(coupon);
    }

    // ==================== 礼品卡列表 ====================

    /**
     * GET /member/gift-card — 获取当前用户礼品卡列表。
     * <p>
     * 保持响应结构：
     * <pre>
     * { "code": "20000", "msg": "成功", "result": { "cards": [...], "summary": {...} } }
     * </pre>
     */
    @GetMapping("/member/gift-card")
    public FrontResponse<Object> memberGiftCards(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        JSONObject result = benefitService.getGiftCards(uid);
        return FrontResponse.success(result);
    }

    // ==================== 绑定礼品卡 ====================

    /**
     * POST /member/gift-card/bind — 绑定礼品卡。
     * <p>
     * 请求体：{ "code": "GIFT-XXXX" }
     * <p>
     * 校验规则：
     * <ul>
     *   <li>code 不能为空 → {"code": "50000", "msg": "礼品卡卡号不能为空"}</li>
     *   <li>code 不以 GIFT 开头 → {"code": "50000", "msg": "礼品卡卡号无效"}</li>
     *   <li>code 已绑定 → {"code": "50000", "msg": "该礼品卡已绑定"}</li>
     *   <li>成功 → 返回新礼品卡对象</li>
     * </ul>
     */
    @PostMapping("/member/gift-card/bind")
    public FrontResponse<Object> bindGiftCard(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        String code = params != null && params.get("code") != null
                ? params.get("code").toString().trim().toUpperCase() : "";
        if (code.isEmpty()) {
            return FrontResponse.failure("礼品卡卡号不能为空");
        }
        if (!code.startsWith("GIFT")) {
            return FrontResponse.failure("礼品卡卡号无效");
        }
        JSONObject card = benefitService.bindGiftCard(uid, code);
        if (card == null) {
            return FrontResponse.failure("该礼品卡已绑定");
        }
        return FrontResponse.success(card);
    }

    // ==================== 新用户初始化 ====================

    /**
     * 为新用户初始化空的优惠券和礼品卡。
     * 由 MockController.initNewUserFeatureData() 在注册时调用。
     *
     * @param userId 新注册用户 ID
     */
    public void initNewUserBenefits(String userId) {
        benefitStore.initNewUserBenefits(userId);
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
     * 返回 token 对应的 userId，未找到返回 null。
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
