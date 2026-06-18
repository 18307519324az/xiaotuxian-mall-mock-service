package com.xtx.mock.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.service.GameMockService;
import com.xtx.mock.service.points.PointsMockService;
import com.xtx.mock.store.RuntimeBenefitStore;
import com.xtx.mock.store.RuntimeGameStore;
import com.xtx.mock.store.RuntimePointsStore;
import com.xtx.common.core.result.FrontResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MockGameController — 抽奖和签到模块控制器。
 * <p>
 * 职责：
 * <ul>
 *   <li>GET  /member/lottery      — 获取当前用户抽奖信息</li>
 *   <li>POST /member/lottery/draw  — 执行抽奖（扣积分、选奖品、发奖励）</li>
 *   <li>POST /member/signin       — 每日签到</li>
 * </ul>
 * <p>
 * 由 Spring 管理为 {@link RestController}，但运行时数据依赖
 * 由 {@link MockController} 在 {@code @PostConstruct} 中通过 setter 注入：
 * <ul>
 *   <li>{@link #setRuntimePoints(Map)} — 积分数据引用</li>
 *   <li>{@link #setFeatureCounter(AtomicInteger)} — ID 生成器</li>
 *   <li>{@link #setBenefitStore(RuntimeBenefitStore)} — 优惠券奖品创建</li>
 *   <li>{@link #setSavePointsCallback(Runnable)} — 触发积分持久化</li>
 * </ul>
 * <p>
 * MockController 通过 {@link #getStore()} 访问底层 GameStore，
 * 用于用户注册/注销时初始化或清理游戏数据。
 */
@Slf4j
@RestController
public class MockGameController {

    private final RuntimeGameStore gameStore;
    private final GameMockService gameService;

    // ==================== 注入的依赖引用 ====================

    /** MockController 的 runtimeTokens 引用（@PostConstruct 注入） */
    private Map<String, String> runtimeTokens;

    /** 积分 Store 引用（PointsMockController 共享） */
    private RuntimePointsStore pointsStore;

    /** 积分 Service 引用 */
    private PointsMockService pointsService;

    /** MockController 的 featureCounter 引用（ID 生成） */
    private AtomicInteger featureCounter;

    /** MockBenefitController 的 RuntimeBenefitStore 引用（优惠券奖品） */
    private RuntimeBenefitStore benefitStore;

    /**
     * 构造器：创建 Store 和 Service 并加载持久化数据。
     * Spring 实例化时自动调用。
     */
    public MockGameController() {
        this.gameStore = new RuntimeGameStore();
        this.gameService = new GameMockService(gameStore);
        this.gameStore.loadFromFile();
        log.info("MockGameController initialized, store loaded from file");
    }

    // ==================== Setter 注入 ====================

    public void setRuntimeTokens(Map<String, String> tokens) {
        this.runtimeTokens = tokens;
    }

    public void setPointsStore(RuntimePointsStore store) {
        this.pointsStore = store;
    }

    public void setPointsService(PointsMockService service) {
        this.pointsService = service;
    }

    public void setFeatureCounter(AtomicInteger counter) {
        this.featureCounter = counter;
    }

    public void setBenefitStore(RuntimeBenefitStore store) {
        this.benefitStore = store;
    }

    /**
     * 返回底层 GameStore 引用，供 MockController 在用户注册/注销时初始化或清理数据。
     */
    public RuntimeGameStore getStore() {
        return gameStore;
    }

    // ==================== GET /member/lottery ====================

    /**
     * GET /member/lottery — 获取当前用户抽奖信息。
     * <p>
     * 响应包含 prizes、history、rules、动态 pointBalance 和 chances。
     */
    @GetMapping("/member/lottery")
    public Object memberLottery(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = requireUserId(authHeader);
        JSONObject lottery = gameStore.getLottery(uid);
        JSONObject points = pointsStore.getPoints(uid);
        if (points != null) {
            pointsService.computeSummary(points);
            int pointBalance = points.getInt("balance", 0);
            lottery.set("pointBalance", pointBalance);
            lottery.set("chances", pointBalance / 30);
        } else {
            lottery.set("pointBalance", 0);
            lottery.set("chances", 0);
        }
        return FrontResponse.success(lottery);
    }

    // ==================== POST /member/lottery/draw ====================

    /**
     * POST /member/lottery/draw — 执行抽奖。
     * <p>
     * 流程：
     * <ol>
     *   <li>校验积分余额 >= 30</li>
     *   <li>FIFO 扣减 30 积分</li>
     *   <li>加权随机抽奖</li>
     *   <li>创建积分消耗/奖励流水</li>
     *   <li>若中奖类型为 coupon，通过 benefitStore 创建优惠券</li>
     *   <li>添加抽奖历史记录</li>
     *   <li>持久化</li>
     * </ol>
     */
    @PostMapping("/member/lottery/draw")
    public Object drawLottery(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        JSONObject lottery = gameStore.getLottery(uid);
        JSONObject points = pointsStore.getPoints(uid);
        JSONArray records = points.getJSONArray("records");
        pointsService.computeSummary(points);
        int pointBalance = points.getInt("balance", 0);
        int chances = pointBalance / 30;
        lottery.set("chances", chances);
        if (pointBalance < 30) {
            return FrontResponse.failure(40000, "积分不足，无法抽奖");
        }
        // FIFO 扣减 30 积分
        if (!pointsService.deductFIFO(records, 30)) {
            return FrontResponse.failure(40000, "积分不足，无法抽奖");
        }
        // 加权随机抽奖
        JSONArray prizes = lottery.getJSONArray("prizes");
        JSONObject prize = gameService.drawPrize(prizes);
        String pointsNow = java.time.LocalDateTime.now().toString().replace('T', ' ').substring(0, 19);
        String pointsExpire = java.time.LocalDateTime.now().plusDays(30).toString().replace('T', ' ').substring(0, 19);
        // 记录消耗流水
        records.add(0, new JSONObject()
                .set("id", "points_" + featureCounter.incrementAndGet())
                .set("title", "幸运抽奖消耗").set("reason", "lottery")
                .set("delta", -30).set("type", "expense")
                .set("source", "lottery")
                .set("createdAt", pointsNow).set("expireAt", ""));
        if ("points".equals(prize.getStr("type"))) {
            // 中奖积分奖励
            records.add(0, new JSONObject()
                    .set("id", "points_" + featureCounter.incrementAndGet())
                    .set("title", "抽奖奖励：" + prize.getStr("name")).set("reason", "lottery_prize")
                    .set("delta", prize.getInt("value", 0)).set("type", "income")
                    .set("remaining", prize.getInt("value", 0))
                    .set("source", "lottery")
                    .set("createdAt", pointsNow).set("expireAt", pointsExpire));
        } else if ("coupon".equals(prize.getStr("type"))) {
            // 优惠券奖品：通过 benefitStore 创建
            JSONArray benefitCoupons = benefitStore.getCoupons(uid);
            benefitCoupons.add(0, new JSONObject()
                    .set("id", benefitStore.generateNextCouponId())
                    .set("name", "抽奖奖励券")
                    .set("description", "满 59 元减 " + prize.getInt("value", 0) + " 元")
                    .set("amount", prize.getInt("value", 0))
                    .set("threshold", 59)
                    .set("status", "available")
                    .set("expiresAt", "2026-07-15")
                    .set("source", "幸运抽奖"));
            benefitStore.save();
        }
        // 添加抽奖历史
        String historyId = "lottery_" + featureCounter.incrementAndGet();
        JSONObject history = new JSONObject()
                .set("id", historyId)
                .set("name", prize.getStr("name"))
                .set("type", prize.getStr("type"))
                .set("createdAt", pointsNow);
        lottery.getJSONArray("history").add(0, history);
        // 重新计算积分汇总
        pointsService.computeSummary(points);
        // 持久化
        pointsStore.save();
        gameStore.saveLottery();
        return FrontResponse.success(new JSONObject()
                .set("prize", prize)
                .set("remainingChances", chances)
                .set("pointBalance", points.getInt("balance", 0)));
    }

    // ==================== POST /member/signin ====================

    /**
     * POST /member/signin — 每日签到。
     * <p>
     * 流程：
     * <ol>
     *   <li>检查今日是否已签到（双重验证：points records + signin records）</li>
     *   <li>创建签到积分流水（+20）</li>
     *   <li>记录签到记录</li>
     *   <li>重新计算积分汇总 + 持久化</li>
     * </ol>
     */
    @PostMapping("/member/signin")
    public Object signin(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> params) {
        String uid = requireUserId(authHeader);
        JSONObject points = pointsStore.getPoints(uid);
        JSONArray records = points.getJSONArray("records");
        String today = java.time.LocalDateTime.now().toString().substring(0, 10);
        // 双重验证今日是否已签到
        if (gameService.isSignedToday(uid, today)) {
            return FrontResponse.failure(40900, "今日已签到，请明天再来");
        }
        if (gameService.isSignedTodayFromRecords(records, today)) {
            return FrontResponse.failure(40900, "今日已签到，请明天再来");
        }
        // 签到奖励 +20 积分
        String now = java.time.LocalDateTime.now().toString().replace('T', ' ').substring(0, 19);
        String expireAt = java.time.LocalDateTime.now().plusDays(30).toString().replace('T', ' ').substring(0, 19);
        String recordId = "points_" + featureCounter.incrementAndGet();
        records.add(0, new JSONObject()
                .set("id", recordId)
                .set("title", "签到奖励").set("reason", "sign")
                .set("delta", 20).set("type", "income")
                .set("remaining", 20)
                .set("source", "signin")
                .set("createdAt", now)
                .set("expireAt", expireAt));
        // 记录签到
        gameStore.addSigninRecord(uid, new JSONObject()
                .set("date", today)
                .set("pointsRecordId", recordId)
                .set("createdAt", now));
        // 重新计算积分
        pointsService.computeSummary(points);
        // 持久化
        pointsStore.save();
        gameStore.saveSigninRecords();
        return FrontResponse.success(new JSONObject()
                .set("signed", true)
                .set("rewarded", true)
                .set("delta", 20)
                .set("expireAt", expireAt)
                .set("message", "签到成功，获得 20 积分")
                .set("balance", points.getInt("balance", 0))
                .set("expiringSoon", points.getInt("expiringSoon", 0)));
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
