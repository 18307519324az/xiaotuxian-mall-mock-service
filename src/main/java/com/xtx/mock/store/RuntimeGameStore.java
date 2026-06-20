package com.xtx.mock.store;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.support.MockJsonPersistence;
import com.xtx.mock.support.MockRuntimePaths;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RuntimeGameStore — 抽奖和签到运行时存储。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理 runtimeLottery 和 runtimeSigninRecords 两个内存态 Map（按 userId 隔离）</li>
 *   <li>文件持久化（data/user-lottery-runtime.json 和 data/user-signin-records-runtime.json）</li>
 *   <li>默认抽奖数据构建（6 个奖品 + 规则）</li>
 *   <li>新用户空数据初始化</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockGameController} 直接实例化。
 */
@Slf4j
public class RuntimeGameStore {

    /** 运行时抽奖数据 (按需持久化), keyed by userId */
    private final Map<String, JSONObject> runtimeLottery = new ConcurrentHashMap<>();

    /** 运行时签到记录 (文件持久化), keyed by userId → JSONArray of {date, pointsRecordId, createdAt} */
    private final Map<String, JSONArray> runtimeSigninRecords = new ConcurrentHashMap<>();

    // ==================== 抽奖数据访问 ====================

    /**
     * 获取指定用户的抽奖数据。如果首次访问，创建默认抽奖数据（含 6 奖品 + 空 history）。
     */
    public JSONObject getLottery(String uid) {
        return runtimeLottery.computeIfAbsent(uid, this::buildDefaultLottery);
    }

    /**
     * 判断指定用户是否已有 lottery 数据（不创建默认数据）。
     */
    public boolean hasLottery(String uid) {
        return runtimeLottery.containsKey(uid);
    }

    /**
     * 初始化新用户的 lottery 数据（空 history, chances=0, 默认奖品）。
     */
    public void initNewUserLottery(String userId) {
        runtimeLottery.computeIfAbsent(userId, this::buildDefaultLottery);
    }

    // ==================== 签到记录访问 ====================

    /**
     * 获取指定用户的签到记录列表。
     */
    public JSONArray getSigninRecords(String uid) {
        return runtimeSigninRecords.computeIfAbsent(uid, k -> new JSONArray());
    }

    /**
     * 添加一条签到记录。
     */
    public void addSigninRecord(String uid, JSONObject record) {
        JSONArray records = runtimeSigninRecords.computeIfAbsent(uid, k -> new JSONArray());
        records.add(record);
    }

    // ==================== 数据移除 ====================

    /**
     * 移除指定用户的所有游戏数据（用户注销时调用）。
     */
    public void removeUserData(String userId) {
        runtimeLottery.remove(userId);
        runtimeSigninRecords.remove(userId);
    }

    // ==================== 构建默认数据 ====================

    /**
     * 构造 6 个默认奖品：谢谢参与(20%)、10积分(25%)、20积分(25%)、5元券(15%)、50积分(10%)、20元券(5%)。
     * 概率总和 = 100。
     */
    private JSONObject buildDefaultLottery(String userId) {
        JSONArray prizes = new JSONArray();
        prizes.add(new JSONObject()
                .set("name", "谢谢参与").set("type", "none").set("value", 0)
                .set("probability", 20).set("probabilityPercent", "20%"));
        prizes.add(new JSONObject()
                .set("name", "10 积分").set("type", "points").set("value", 10)
                .set("probability", 25).set("probabilityPercent", "25%"));
        prizes.add(new JSONObject()
                .set("name", "20 积分").set("type", "points").set("value", 20)
                .set("probability", 25).set("probabilityPercent", "25%"));
        prizes.add(new JSONObject()
                .set("name", "5 元优惠券").set("type", "coupon").set("value", 5)
                .set("probability", 15).set("probabilityPercent", "15%"));
        prizes.add(new JSONObject()
                .set("name", "50 积分").set("type", "points").set("value", 50)
                .set("probability", 10).set("probabilityPercent", "10%"));
        prizes.add(new JSONObject()
                .set("name", "20 元优惠券").set("type", "coupon").set("value", 20)
                .set("probability", 5).set("probabilityPercent", "5%"));
        JSONArray rules = new JSONArray();
        rules.add("每次抽奖消耗 30 积分");
        rules.add("积分奖励实时到账，优惠券自动发放到账户");
        rules.add("中奖概率：谢谢参与 20%、10积分 25%、20积分 25%、5元券 15%、50积分 10%、20元券 5%");
        rules.add("抽奖次数由当前积分决定：每 30 积分 = 1 次抽奖机会");
        return new JSONObject()
                .set("chances", 0).set("prizes", prizes)
                .set("history", new JSONArray()).set("rules", rules);
    }

    // ==================== 持久化 ====================

    /**
     * 从文件加载抽奖和签到数据。
     * 在服务启动时由 MockGameController 构造器调用。
     */
    public void loadFromFile() {
        // 加载抽奖数据
        JSONObject lotteryObj = MockJsonPersistence.loadObject(MockRuntimePaths.USER_LOTTERY);
        if (lotteryObj != null) {
            for (Map.Entry<String, Object> entry : lotteryObj.entrySet()) {
                if (entry.getValue() instanceof JSONObject) {
                    runtimeLottery.put(entry.getKey(), (JSONObject) entry.getValue());
                }
            }
            log.info("Loaded lottery for {} users", runtimeLottery.size());
        }

        // 加载签到记录
        JSONObject signinObj = MockJsonPersistence.loadObject(MockRuntimePaths.USER_SIGNIN_RECORDS);
        if (signinObj != null) {
            for (Map.Entry<String, Object> entry : signinObj.entrySet()) {
                if (entry.getValue() instanceof JSONArray) {
                    runtimeSigninRecords.put(entry.getKey(), (JSONArray) entry.getValue());
                }
            }
            log.info("Loaded signin records for {} users", runtimeSigninRecords.size());
        }
    }

    /**
     * 保存所有用户的抽奖数据到文件。
     * 每次抽奖/签到操作后由 MockGameController 调用。
     */
    public void saveLottery() {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, JSONObject> entry : runtimeLottery.entrySet()) {
            obj.set(entry.getKey(), entry.getValue());
        }
        MockJsonPersistence.save(MockRuntimePaths.USER_LOTTERY, obj);
    }

    /**
     * 保存所有用户的签到记录到文件。
     */
    public void saveSigninRecords() {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, JSONArray> entry : runtimeSigninRecords.entrySet()) {
            obj.set(entry.getKey(), entry.getValue());
        }
        MockJsonPersistence.save(MockRuntimePaths.USER_SIGNIN_RECORDS, obj);
    }
}
