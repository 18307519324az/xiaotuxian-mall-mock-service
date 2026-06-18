package com.xtx.mock.store;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.support.MockJsonPersistence;
import com.xtx.mock.support.MockRuntimePaths;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RuntimeBenefitStore — 优惠券和礼品卡运行时存储。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理 runtimeCoupons 和 runtimeGiftCards 两个内存态 Map（按 userId 隔离）</li>
 *   <li>文件持久化（data/user-coupons-runtime.json 和 data/user-gift-cards-runtime.json）</li>
 *   <li>ID 自增计数器管理（coupon_ / gift_ 前缀）</li>
 *   <li>新用户空数据初始化</li>
 *   <li>默认用户 seed 数据构建</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockBenefitController} 直接实例化。
 * 订单创建流程通过 {@link #getCoupons(String)} 和 {@link #getGiftCards(String)}
 * 读取数据，通过 {@link #save()} 持久化变更。
 */
@Slf4j
public class RuntimeBenefitStore {

    /** 运行时优惠券 (文件持久化), keyed by userId */
    private final Map<String, JSONArray> runtimeCoupons = new ConcurrentHashMap<>();

    /** 运行时礼品卡 (文件持久化), keyed by userId */
    private final Map<String, JSONArray> runtimeGiftCards = new ConcurrentHashMap<>();

    /** 优惠券 ID 计数器（不与 MockController.featureCounter 共享） */
    private final AtomicInteger couponIdCounter = new AtomicInteger(0);

    /** 礼品卡 ID 计数器（不与 MockController.featureCounter 共享） */
    private final AtomicInteger giftIdCounter = new AtomicInteger(0);

    // ==================== 优惠券读取 ====================

    /**
     * 获取指定用户的优惠券列表。如果首次访问且无持久化数据，创建默认优惠券。
     */
    public JSONArray getCoupons(String uid) {
        return runtimeCoupons.computeIfAbsent(uid, k -> buildDefaultCoupons());
    }

    // ==================== 礼品卡读取 ====================

    /**
     * 获取指定用户的礼品卡列表。如果首次访问且无持久化数据，创建默认礼品卡。
     */
    public JSONArray getGiftCards(String uid) {
        return runtimeGiftCards.computeIfAbsent(uid, k -> buildDefaultGiftCards());
    }

    // ==================== ID 生成 ====================

    /**
     * 生成下一个优惠券 ID（coupon_N 格式）。
     * 由 BenefitMockService 在兑换时调用。
     */
    public String generateNextCouponId() {
        return "coupon_" + couponIdCounter.incrementAndGet();
    }

    /**
     * 生成下一个礼品卡 ID（gift_N 格式）。
     * 由 BenefitMockService 在绑定时调用。
     */
    public String generateNextGiftId() {
        return "gift_" + giftIdCounter.incrementAndGet();
    }

    // ==================== 新用户初始化 ====================

    /**
     * 为新用户初始化空的优惠券和礼品卡（无默认数据）。
     * 由 MockController.initNewUserFeatureData() 调用。
     */
    public void initNewUserBenefits(String userId) {
        runtimeCoupons.computeIfAbsent(userId, k -> new JSONArray());
        runtimeGiftCards.computeIfAbsent(userId, k -> new JSONArray());
    }

    /**
     * 确保默认用户有 seed 数据。
     * 在 @PostConstruct 中调用，避免 initNewUserBenefits 覆盖默认数据。
     */
    public void ensureDefaultUser(String userId) {
        if (!runtimeCoupons.containsKey(userId)) {
            runtimeCoupons.put(userId, buildDefaultCoupons());
        }
        if (!runtimeGiftCards.containsKey(userId)) {
            runtimeGiftCards.put(userId, buildDefaultGiftCards());
        }
    }

    // ==================== 构建默认数据 ====================

    /**
     * 构造 3 条默认优惠券：会员满减券、运费减免券、老客回归券。
     * 使用独立的 couponIdCounter 保证 ID 唯一。
     */
    private JSONArray buildDefaultCoupons() {
        JSONArray arr = new JSONArray();
        arr.add(new JSONObject()
                .set("id", "coupon_" + couponIdCounter.incrementAndGet())
                .set("name", "会员满减券")
                .set("description", "满 199 元减 20 元")
                .set("amount", 20)
                .set("threshold", 199)
                .set("couponType", "goods")
                .set("status", "available")
                .set("expiresAt", "2026-07-31")
                .set("source", "会员专享"));
        arr.add(new JSONObject()
                .set("id", "coupon_" + couponIdCounter.incrementAndGet())
                .set("name", "运费减免券")
                .set("description", "全场运费减 8 元")
                .set("amount", 8)
                .set("threshold", 0)
                .set("couponType", "freight")
                .set("status", "available")
                .set("expiresAt", "2026-06-30")
                .set("source", "签到奖励"));
        arr.add(new JSONObject()
                .set("id", "coupon_" + couponIdCounter.incrementAndGet())
                .set("name", "老客回归券")
                .set("description", "满 299 元减 30 元")
                .set("amount", 30)
                .set("threshold", 299)
                .set("couponType", "goods")
                .set("status", "used")
                .set("expiresAt", "2026-05-31")
                .set("source", "运营发放"));
        return arr;
    }

    /**
     * 构造 1 张默认礼品卡。
     * 使用独立的 giftIdCounter 保证 ID 唯一。
     */
    private JSONArray buildDefaultGiftCards() {
        JSONArray arr = new JSONArray();
        arr.add(new JSONObject()
                .set("id", "gift_" + giftIdCounter.incrementAndGet())
                .set("name", "严选礼品卡")
                .set("code", "GIFT-2026-001")
                .set("balance", 188)
                .set("status", "active")
                .set("boundAt", "2026-06-01 09:30:00"));
        return arr;
    }

    // ==================== 持久化 ====================

    /**
     * 从文件加载优惠券和礼品卡数据，并计算当前最大 ID 编号。
     * 在服务启动时由 MockBenefitController 构造器调用。
     */
    public void loadFromFile() {
        // 加载优惠券
        JSONObject couponsObj = MockJsonPersistence.loadObject(MockRuntimePaths.USER_COUPONS);
        if (couponsObj != null) {
            for (Map.Entry<String, Object> entry : couponsObj.entrySet()) {
                if (entry.getValue() instanceof JSONArray) {
                    runtimeCoupons.put(entry.getKey(), (JSONArray) entry.getValue());
                }
            }
            scanCouponIds();
            log.info("Loaded coupons for {} users, max coupon ID: {}", runtimeCoupons.size(), couponIdCounter.get());
        }

        // 加载礼品卡
        JSONObject giftCardsObj = MockJsonPersistence.loadObject(MockRuntimePaths.USER_GIFT_CARDS);
        if (giftCardsObj != null) {
            for (Map.Entry<String, Object> entry : giftCardsObj.entrySet()) {
                if (entry.getValue() instanceof JSONArray) {
                    runtimeGiftCards.put(entry.getKey(), (JSONArray) entry.getValue());
                }
            }
            scanGiftIds();
            log.info("Loaded gift cards for {} users, max gift ID: {}", runtimeGiftCards.size(), giftIdCounter.get());
        }
    }

    /**
     * 保存所有用户的优惠券和礼品卡到文件。
     * 每次修改后由 BenefitMockService 或 MockController 调用。
     */
    public void save() {
        // 保存优惠券
        JSONObject couponsObj = new JSONObject();
        for (Map.Entry<String, JSONArray> entry : runtimeCoupons.entrySet()) {
            couponsObj.set(entry.getKey(), entry.getValue());
        }
        MockJsonPersistence.save(MockRuntimePaths.USER_COUPONS, couponsObj);

        // 保存礼品卡
        JSONObject giftCardsObj = new JSONObject();
        for (Map.Entry<String, JSONArray> entry : runtimeGiftCards.entrySet()) {
            giftCardsObj.set(entry.getKey(), entry.getValue());
        }
        MockJsonPersistence.save(MockRuntimePaths.USER_GIFT_CARDS, giftCardsObj);
    }

    /**
     * 扫描所有已加载的优惠券，将 couponIdCounter 设为当前最大编号。
     */
    private void scanCouponIds() {
        for (Map.Entry<String, JSONArray> entry : runtimeCoupons.entrySet()) {
            for (Object item : entry.getValue()) {
                if (item instanceof JSONObject) {
                    String id = ((JSONObject) item).getStr("id", "");
                    if (id.startsWith("coupon_")) {
                        try {
                            int num = Integer.parseInt(id.substring(7));
                            couponIdCounter.set(Math.max(couponIdCounter.get(), num));
                        } catch (Exception ignored) {
                            // non-numeric coupon ID suffix, skip
                        }
                    }
                }
            }
        }
    }

    /**
     * 扫描所有已加载的礼品卡，将 giftIdCounter 设为当前最大编号。
     */
    private void scanGiftIds() {
        for (Map.Entry<String, JSONArray> entry : runtimeGiftCards.entrySet()) {
            for (Object item : entry.getValue()) {
                if (item instanceof JSONObject) {
                    String id = ((JSONObject) item).getStr("id", "");
                    if (id.startsWith("gift_")) {
                        try {
                            int num = Integer.parseInt(id.substring(5));
                            giftIdCounter.set(Math.max(giftIdCounter.get(), num));
                        } catch (Exception ignored) {
                            // non-numeric gift ID suffix, skip
                        }
                    }
                }
            }
        }
    }
}
