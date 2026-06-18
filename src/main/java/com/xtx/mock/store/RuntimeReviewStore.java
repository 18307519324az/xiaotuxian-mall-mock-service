package com.xtx.mock.store;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.support.MockJsonPersistence;
import com.xtx.mock.support.MockRuntimePaths;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RuntimeReviewStore — 评价模块运行时存储。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理 runtimeReviews 内存态 Map（按 userId 隔离的用户评价）</li>
 *   <li>管理 runtimeGoodsReviews 内存态 Map（按 goodsId 索引的商品详情评价）</li>
 *   <li>管理 evaluateBasePraiseCount（评价基础点赞数）</li>
 *   <li>管理 praisedSet（用户点赞关系）</li>
 *   <li>商品详情评价文件持久化（data/user-goods-reviews-runtime.json）</li>
 *   <li>新用户空数据初始化</li>
 *   <li>用户数据移除（重置/注销）</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockReviewController} 直接实例化。
 */
@Slf4j
public class RuntimeReviewStore {

    /** 运行时用户评价 (不持久化), keyed by userId → JSONArray of review records */
    private final Map<String, JSONArray> runtimeReviews = new ConcurrentHashMap<>();

    /** 运行时商品详情评价 (文件持久化), keyed by goodsId → JSONArray of evaluation records */
    private final Map<String, JSONArray> runtimeGoodsReviews = new ConcurrentHashMap<>();

    /** 评价基础点赞数 (从 master 文件加载), evaluateId → basePraiseCount */
    private final Map<String, Integer> evaluateBasePraiseCount = new ConcurrentHashMap<>();

    /** 用户点赞关系 (幂等), "userId:evaluateId" */
    private final Set<String> praisedSet = ConcurrentHashMap.newKeySet();

    /** 持久化文件路径：商品详情评价（用户提交的 runtime 评价） */
    private static final String PERSISTENCE_PATH = MockRuntimePaths.USER_GOODS_REVIEWS;

    // ==================== 用户评价访问 ====================

    /**
     * 获取指定用户的评价列表。如果首次访问且数据不存在，返回空数组。
     */
    public JSONArray getUserReviews(String uid) {
        return runtimeReviews.computeIfAbsent(uid, k -> new JSONArray());
    }

    /**
     * 获取指定用户的评价列表（直接从内存取，不创建默认值）。
     */
    public JSONArray getUserReviewsRaw(String uid) {
        return runtimeReviews.get(uid);
    }

    // ==================== 商品详情评价访问 ====================

    /**
     * 获取指定商品的运行时评价列表（用户提交的）。
     */
    public JSONArray getGoodsReviews(String goodsId) {
        return runtimeGoodsReviews.get(goodsId);
    }

    /**
     * 获取或创建指定商品的运行时评价列表。
     */
    public JSONArray getOrCreateGoodsReviews(String goodsId) {
        return runtimeGoodsReviews.computeIfAbsent(goodsId, k -> new JSONArray());
    }

    // ==================== 评价点赞 ====================

    /**
     * 检查用户是否点赞了某条评价。
     */
    public boolean isPraised(String userId, String evaluateId) {
        return praisedSet.contains(userId + ":" + evaluateId);
    }

    /**
     * 获取点赞关系集合（用于遍历统计）。
     */
    public Set<String> getPraisedSet() {
        return praisedSet;
    }

    /**
     * 设置/取消点赞关系。
     */
    public void setPraised(String userId, String evaluateId, boolean praised) {
        String key = userId + ":" + evaluateId;
        if (praised) {
            praisedSet.add(key);
        } else {
            praisedSet.remove(key);
        }
    }

    /**
     * 统计某条评价获得的活跃点赞数。
     */
    public int countActiveLikes(String evaluateId) {
        String suffix = ":" + evaluateId;
        int count = 0;
        for (String entry : praisedSet) {
            if (entry.endsWith(suffix)) count++;
        }
        return count;
    }

    /**
     * 获取评价的基础点赞数。
     */
    public int getBasePraiseCount(String evaluateId) {
        return evaluateBasePraiseCount.getOrDefault(evaluateId, 0);
    }

    /**
     * 确保评价的基础点赞数已被初始化。
     * 从 masterEvaluations 中查找对应 evaluateId 的初始 praiseCount。
     */
    public void ensureBasePraiseCount(String evaluateId, JSONObject masterEvaluations) {
        if (evaluateBasePraiseCount.containsKey(evaluateId)) return;

        for (Map.Entry<String, Object> entry : masterEvaluations.entrySet()) {
            JSONObject ev = (JSONObject) entry.getValue();
            if (ev == null) continue;
            JSONObject page = (JSONObject) ev.get("page");
            if (page == null) continue;
            JSONArray items = page.getJSONArray("items");
            if (items == null) continue;
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (evaluateId.equals(item.getStr("id"))) {
                    Object pcRaw = item.get("praiseCount");
                    int fileCount = 0;
                    if (pcRaw instanceof Number) {
                        fileCount = ((Number) pcRaw).intValue();
                    } else if (pcRaw != null) {
                        try { fileCount = Integer.parseInt(pcRaw.toString()); } catch (Exception ignored) {}
                    }
                    evaluateBasePraiseCount.put(evaluateId, fileCount);
                    return;
                }
            }
        }
        // Fallback: if not found in any product, set 0
        evaluateBasePraiseCount.putIfAbsent(evaluateId, 0);
    }

    /**
     * 预置基础点赞数（用于初始化）。
     */
    public void putBasePraiseCount(String evaluateId, int count) {
        evaluateBasePraiseCount.put(evaluateId, count);
    }

    // ==================== 用户数据管理 ====================

    /**
     * 初始化新用户的评价数据（空数组）。
     */
    public void initNewUser(String userId) {
        runtimeReviews.computeIfAbsent(userId, k -> new JSONArray());
    }

    /**
     * 移除指定用户的所有评价数据（用户注销/重置时调用）。
     */
    public void removeUserData(String userId) {
        runtimeReviews.remove(userId);
    }

    /**
     * 获取完整的内存态 runtimeReviews Map（用于遍历等操作）。
     */
    public Map<String, JSONArray> getReviewsMap() {
        return runtimeReviews;
    }

    /**
     * 获取完整的内存态 runtimeGoodsReviews Map。
     */
    public Map<String, JSONArray> getGoodsReviewsMap() {
        return runtimeGoodsReviews;
    }

    /**
     * 清空所有运行时评价数据（重置时调用）。
     */
    public void clearAll() {
        runtimeReviews.clear();
        runtimeGoodsReviews.clear();
        evaluateBasePraiseCount.clear();
        praisedSet.clear();
    }

    // ==================== 持久化 ====================

    /**
     * 从文件加载商品详情评价数据。
     * <p>
     * 文件格式：{ "goods_xxx": [{...evaluation...}], "goods_yyy": [{...evaluation...}] }
     */
    public void loadFromFile() {
        JSONObject data = MockJsonPersistence.loadObject(PERSISTENCE_PATH);
        if (data == null) {
            log.info("No goods reviews runtime file found at {}, starting fresh", PERSISTENCE_PATH);
            return;
        }
        int count = 0;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getValue() instanceof JSONArray) {
                runtimeGoodsReviews.put(entry.getKey(), (JSONArray) entry.getValue());
                count++;
            }
        }
        log.info("Loaded goods reviews for {} products", count);
    }

    /**
     * 将商品详情评价数据持久化到文件。
     */
    public void save() {
        JSONObject data = new JSONObject();
        for (Map.Entry<String, JSONArray> entry : runtimeGoodsReviews.entrySet()) {
            data.set(entry.getKey(), entry.getValue());
        }
        MockJsonPersistence.save(PERSISTENCE_PATH, data);
    }
}
