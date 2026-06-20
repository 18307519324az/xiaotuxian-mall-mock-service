package com.xtx.mock.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeBenefitStore;
import lombok.extern.slf4j.Slf4j;

/**
 * BenefitMockService — 优惠券和礼品卡业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>获取用户优惠券列表（含 summary: available / used / expired）</li>
 *   <li>兑换优惠券（兑换码校验）</li>
 *   <li>获取用户礼品卡列表（含 summary: count / balance）</li>
 *   <li>绑定礼品卡（卡号校验、去重）</li>
 *   <li>不直接读写文件，委托 RuntimeBenefitStore 持久化</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockBenefitController} 直接实例化。
 */
@Slf4j
public class BenefitMockService {

    private final RuntimeBenefitStore store;

    /**
     * @param store 优惠券/礼品卡运行时存储
     */
    public BenefitMockService(RuntimeBenefitStore store) {
        this.store = store;
    }

    // ==================== 优惠券列表 ====================

    /**
     * 获取用户优惠券列表，保持旧响应结构：
     * <pre>
     * {
     *   "items": [ { "id": "coupon_1", "name": "会员满减券", ... } ],
     *   "summary": { "available": 2, "used": 1, "expired": 0 }
     * }
     * </pre>
     *
     * @param uid 当前用户 ID
     * @return 含 items 和 summary 的 JSONObject
     */
    public JSONObject getCoupons(String uid) {
        JSONArray items = store.getCoupons(uid);
        int available = 0, used = 0, expired = 0;
        for (Object obj : items) {
            String status = ((JSONObject) obj).getStr("status", "");
            switch (status) {
                case "available" -> available++;
                case "used" -> used++;
                case "expired" -> expired++;
            }
        }
        JSONObject result = new JSONObject();
        JSONObject summary = new JSONObject();
        summary.set("available", available);
        summary.set("used", used);
        summary.set("expired", expired);
        result.set("items", items);
        result.set("summary", summary);
        return result;
    }

    // ==================== 兑换优惠券 ====================

    /**
     * 兑换优惠券。校验兑换码格式，生成新优惠券并添加到列表首位。
     * <p>
     * 兑换码规则：
     * <ul>
     *   <li>不能为空</li>
     *   <li>必须以 "XTX" 开头</li>
     * </ul>
     *
     * @param uid  当前用户 ID
     * @param code 兑换码
     * @return 新创建的优惠券对象，或 null（兑换码无效时由 controller 处理错误）
     */
    public JSONObject exchangeCoupon(String uid, String code) {
        JSONArray items = store.getCoupons(uid);
        JSONObject coupon = new JSONObject()
                .set("id", store.generateNextCouponId())
                .set("name", "兑换专享券")
                .set("description", "满 99 元减 10 元")
                .set("amount", 10)
                .set("threshold", 99)
                .set("couponType", "goods")
                .set("status", "available")
                .set("expiresAt", "2026-08-31")
                .set("source", "兑换码领取")
                .set("exchangeCode", code);
        items.add(0, coupon);
        store.save();
        return coupon;
    }

    // ==================== 礼品卡列表 ====================

    /**
     * 获取用户礼品卡列表，保持旧响应结构：
     * <pre>
     * {
     *   "cards": [ { "id": "gift_1", "name": "严选礼品卡", ... } ],
     *   "summary": { "count": 1, "balance": 188 }
     * }
     * </pre>
     *
     * @param uid 当前用户 ID
     * @return 含 cards 和 summary 的 JSONObject
     */
    public JSONObject getGiftCards(String uid) {
        JSONArray items = store.getGiftCards(uid);
        int balance = items.stream()
                .map(o -> (JSONObject) o)
                .filter(item -> "active".equals(item.getStr("status")))
                .mapToInt(item -> item.getInt("balance", 0))
                .sum();
        JSONObject result = new JSONObject();
        result.set("cards", items);
        result.set("summary", new JSONObject()
                .set("count", items.size())
                .set("balance", balance));
        return result;
    }

    // ==================== 绑定礼品卡 ====================

    /**
     * 绑定礼品卡。校验卡号格式，检查去重，创建新礼品卡。
     * <p>
     * 卡号规则：
     * <ul>
     *   <li>不能为空</li>
     *   <li>必须以 "GIFT" 开头</li>
     *   <li>不能重复绑定</li>
     * </ul>
     *
     * @param uid  当前用户 ID
     * @param code 礼品卡卡号
     * @return 新创建的礼品卡对象，或 null（重复绑定由 controller 处理错误）
     */
    public JSONObject bindGiftCard(String uid, String code) {
        JSONArray items = store.getGiftCards(uid);
        // 去重检查
        for (Object obj : items) {
            JSONObject item = (JSONObject) obj;
            if (code.equals(item.getStr("code"))) {
                return null; // 已绑定，由 controller 处理错误
            }
        }
        JSONObject card = new JSONObject()
                .set("id", store.generateNextGiftId())
                .set("name", "新绑定礼品卡")
                .set("code", code)
                .set("balance", 100)
                .set("status", "active")
                .set("boundAt", java.time.LocalDateTime.now().toString().replace('T', ' ').substring(0, 19));
        items.add(0, card);
        store.save();
        return card;
    }
}
