package com.xtx.mock.store;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.support.MockJsonPersistence;
import com.xtx.mock.support.MockRuntimePaths;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RuntimePointsStore — 积分模块运行时存储。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理 runtimePoints 内存态 Map（按 userId 隔离）</li>
 *   <li>文件持久化（data/user-points-runtime.json）</li>
 *   <li>默认/空积分数据构建</li>
 *   <li>新用户空数据初始化</li>
 *   <li>用户积分重置（仅限当前 userId）</li>
 * </ul>
 * <p>
 * 此 Store 管理完整的 points JSONObject，包含 records 数组和 computed summary 字段。
 * 文件格式兼容旧版：顶层 { "points": { userId: { records: [...], balance, level, ... } } }。
 */
@Slf4j
public class RuntimePointsStore {

    /** 运行时积分数据 (文件持久化)，按 userId 隔离 */
    private final Map<String, JSONObject> runtimePoints = new ConcurrentHashMap<>();

    private String defaultUserId;

    public RuntimePointsStore(String defaultUserId) {
        this.defaultUserId = defaultUserId;
    }

    /**
     * 设置默认用户 ID（由 MockController 在 @PostConstruct 中调用）。
     */
    public void setDefaultUserId(String userId) {
        this.defaultUserId = userId;
    }

    // ==================== 积分数据访问 ====================

    /**
     * 获取指定用户的积分数据。如果首次访问且是默认用户，创建种子数据。
     */
    public JSONObject getPoints(String uid) {
        return runtimePoints.computeIfAbsent(uid, this::buildDefaultPoints);
    }

    /**
     * 获取指定用户的积分数据（不创建默认数据）。
     */
    public JSONObject getPointsRaw(String uid) {
        return runtimePoints.get(uid);
    }

    /**
     * 获取全部的 points map（仅供 PointsMockService 等内部使用）。
     */
    public Map<String, JSONObject> getPointsMap() {
        return runtimePoints;
    }

    // ==================== 数据初始化 ====================

    /**
     * 初始化新用户的积分数据（空记录）。
     */
    public void initNewUserPoints(String userId) {
        runtimePoints.computeIfAbsent(userId, this::buildEmptyPoints);
    }

    /**
     * 初始化默认用户的积分数据（种子数据）。
     */
    public void ensureDefaultUserPoints(String userId) {
        runtimePoints.computeIfAbsent(userId, this::buildDefaultPoints);
    }

    // ==================== 数据移除 ====================

    /**
     * 移除指定用户的所有积分数据（用户注销时调用）。
     */
    public void removeUserData(String userId) {
        runtimePoints.remove(userId);
    }

    /**
     * 重置指定用户的积分数据（清空记录，归零）。不影响其他用户。
     */
    public void resetPoints(String userId) {
        runtimePoints.put(userId, new JSONObject().set("records", new JSONArray()));
    }

    // ==================== 构建数据 ====================

    /**
     * 为默认用户构建带种子记录的积分数据。
     */
    private JSONObject buildDefaultPoints(String userId) {
        JSONArray records = new JSONArray();
        String in30Days = java.time.LocalDateTime.now().plusDays(30).toString().replace('T', ' ').substring(0, 19);
        String in60Days = java.time.LocalDateTime.now().plusDays(60).toString().replace('T', ' ').substring(0, 19);
        // 初始积分流水记录的 expireAt 在 30-60 天之间，以便有即将过期和未过期的
        records.add(new JSONObject()
                .set("id", "points_seed_1")
                .set("title", "邀请好友注册奖励").set("reason", "invite")
                .set("delta", 120).set("type", "income")
                .set("remaining", 120)
                .set("source", "invite")
                .set("createdAt", "2026-06-12 10:20:00")
                .set("expireAt", in30Days)); // 30天内过期 → 计入 expiringSoon
        records.add(new JSONObject()
                .set("id", "points_seed_2")
                .set("title", "签到奖励").set("reason", "sign")
                .set("delta", 20).set("type", "income")
                .set("remaining", 20)
                .set("source", "signin")
                .set("createdAt", "2026-06-11 08:00:00")
                .set("expireAt", in60Days)); // 60天后过期 → 不计入 expiringSoon
        records.add(new JSONObject()
                .set("id", "points_seed_3")
                .set("title", "幸运抽奖消耗").set("reason", "lottery")
                .set("delta", -30).set("type", "expense")
                .set("source", "lottery")
                .set("createdAt", "2026-06-10 20:30:00")
                .set("expireAt", "")); // 消耗不设置过期
        return new JSONObject().set("records", records);
    }

    /**
     * 为普通用户构建空积分数据。
     */
    private JSONObject buildEmptyPoints(String userId) {
        return new JSONObject().set("records", new JSONArray());
    }

    // ==================== 持久化 ====================

    /**
     * 从文件加载积分数据。
     * 在服务启动时由 MockController @PostConstruct 调用。
     */
    public void loadFromFile() {
        JSONObject obj = MockJsonPersistence.loadObject(MockRuntimePaths.USER_POINTS);
        if (obj != null) {
            runtimePoints.clear();
            // 兼容旧格式：直接 userId → pointsObject（无 points 顶层键）
            JSONObject pointsData;
            if (obj.containsKey("points")) {
                pointsData = obj.getJSONObject("points");
            } else {
                pointsData = obj;
            }

            for (Map.Entry<String, Object> entry : pointsData.entrySet()) {
                JSONObject userPoints = (JSONObject) entry.getValue();
                // 数据迁移：为旧数据补全 remaining 字段
                JSONArray records = userPoints.getJSONArray("records");
                if (records != null) {
                    for (int i = 0; i < records.size(); i++) {
                        JSONObject rec = records.getJSONObject(i);
                        if (!rec.containsKey("remaining")) {
                            int delta = rec.getInt("delta", 0);
                            if (delta > 0) {
                                rec.set("remaining", delta);
                            } else {
                                rec.set("remaining", 0);
                            }
                        }
                        if (!rec.containsKey("source")) {
                            String reason = rec.getStr("reason", "");
                            if ("sign".equals(reason)) rec.set("source", "signin");
                            else if ("invite".equals(reason)) rec.set("source", "invite");
                            else if ("lottery".equals(reason) || "lottery_prize".equals(reason))
                                rec.set("source", "lottery");
                            else rec.set("source", "unknown");
                        }
                    }
                }
                runtimePoints.put(entry.getKey(), userPoints);
            }
            log.info("Loaded points data for {} users", runtimePoints.size());
        }
    }

    /**
     * 保存所有用户的积分数据到文件。
     */
    public void save() {
        JSONObject obj = new JSONObject();
        JSONObject pointsData = new JSONObject();
        for (Map.Entry<String, JSONObject> entry : runtimePoints.entrySet()) {
            pointsData.set(entry.getKey(), entry.getValue());
        }
        obj.set("points", pointsData);
        MockJsonPersistence.save(MockRuntimePaths.USER_POINTS, obj);
    }

    /**
     * 原子写保存（含 .tmp 中间文件，避免写中断导致数据损坏）。
     */
    public void saveAtomic() {
        save();
    }
}
