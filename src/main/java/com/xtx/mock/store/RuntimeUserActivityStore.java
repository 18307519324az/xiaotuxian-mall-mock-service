package com.xtx.mock.store;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.support.MockJsonPersistence;
import com.xtx.mock.support.MockRuntimePaths;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RuntimeUserActivityStore — 用户行为（收藏/浏览历史）运行时存储。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理 runtimeCollects 内存态 Map（按 userId 隔离的商品收藏）</li>
 *   <li>管理 runtimeHistory 内存态 Map（按 userId 隔离的浏览历史）</li>
 *   <li>管理 runtimeTopicCollects 内存态 Map（按 userId 隔离的专题收藏）</li>
 *   <li>文件持久化（data/user-collects-runtime.json, user-history-runtime.json, user-topic-collects-runtime.json）</li>
 *   <li>新用户空数据初始化</li>
 *   <li>用户数据移除（重置/注销）</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockUserActivityController} 直接实例化。
 */
@Slf4j
public class RuntimeUserActivityStore {

    /** 运行时商品收藏 (文件持久化), keyed by userId → Set of goodsId */
    private final Map<String, Set<String>> runtimeCollects = new ConcurrentHashMap<>();

    /** 运行时浏览历史 (文件持久化), keyed by userId → List of goodsId (最新在前) */
    private final Map<String, List<String>> runtimeHistory = new ConcurrentHashMap<>();

    /** 运行时专题收藏 (文件持久化), keyed by userId → Set of topicId */
    private final Map<String, Set<String>> runtimeTopicCollects = new ConcurrentHashMap<>();

    // ==================== 持久化路径 ====================

    private static final String COLLECTS_PATH = MockRuntimePaths.USER_COLLECTS;
    private static final String HISTORY_PATH = MockRuntimePaths.USER_HISTORY;
    private static final String TOPIC_COLLECTS_PATH = MockRuntimePaths.USER_TOPIC_COLLECTS;

    // ==================== 商品收藏访问 ====================

    /**
     * 获取指定用户的收藏商品 ID 集合。如果首次访问，创建空集合。
     */
    public Set<String> getCollects(String uid) {
        return runtimeCollects.computeIfAbsent(uid, k -> ConcurrentHashMap.newKeySet());
    }

    /**
     * 获取指定用户的收藏商品 ID 集合（直接从内存取，不创建默认值）。
     */
    public Set<String> getCollectsRaw(String uid) {
        return runtimeCollects.get(uid);
    }

    /**
     * 添加商品到用户收藏。
     */
    public boolean addCollect(String uid, String goodsId) {
        return getCollects(uid).add(goodsId);
    }

    /**
     * 从用户收藏中移除指定商品。
     */
    public boolean removeCollect(String uid, String goodsId) {
        Set<String> userCollects = runtimeCollects.get(uid);
        if (userCollects == null) return false;
        return userCollects.remove(goodsId);
    }

    /**
     * 从用户收藏中批量移除多个商品。
     */
    public void removeCollects(String uid, List<String> ids) {
        Set<String> userCollects = runtimeCollects.get(uid);
        if (userCollects == null) return;
        userCollects.removeAll(ids);
    }

    /**
     * 获取完整的收藏 Map（用于遍历等操作）。
     */
    public Map<String, Set<String>> getCollectsMap() {
        return runtimeCollects;
    }

    // ==================== 浏览历史访问 ====================

    /**
     * 获取指定用户的浏览历史列表。如果首次访问，创建空列表。
     */
    public List<String> getHistory(String uid) {
        return runtimeHistory.computeIfAbsent(uid, k -> new ArrayList<>());
    }

    /**
     * 获取指定用户的浏览历史列表（直接从内存取，不创建默认值）。
     */
    public List<String> getHistoryRaw(String uid) {
        return runtimeHistory.get(uid);
    }

    /**
     * 添加浏览历史（去重，最新在前，限 100 条）。
     */
    public void addHistory(String uid, String goodsId) {
        List<String> list = getHistory(uid);
        list.remove(goodsId);
        list.add(0, goodsId);
        if (list.size() > 100) {
            list.subList(100, list.size()).clear();
        }
    }

    /**
     * 清空指定用户的浏览历史。
     */
    public void clearHistory(String uid) {
        List<String> list = runtimeHistory.get(uid);
        if (list != null) list.clear();
    }

    /**
     * 获取完整的历史 Map（用于遍历等操作）。
     */
    public Map<String, List<String>> getHistoryMap() {
        return runtimeHistory;
    }

    // ==================== 专题收藏访问 ====================

    /**
     * 获取指定用户的专题收藏 ID 集合。如果首次访问，创建空集合。
     */
    public Set<String> getTopicCollects(String uid) {
        return runtimeTopicCollects.computeIfAbsent(uid, k -> new LinkedHashSet<>());
    }

    /**
     * 获取指定用户的专题收藏 ID 集合（直接从内存取，不创建默认值）。
     */
    public Set<String> getTopicCollectsRaw(String uid) {
        return runtimeTopicCollects.get(uid);
    }

    /**
     * 添加专题到用户收藏。
     */
    public boolean addTopicCollect(String uid, String topicId) {
        return getTopicCollects(uid).add(topicId);
    }

    /**
     * 从用户收藏中移除指定专题。
     */
    public boolean removeTopicCollect(String uid, String topicId) {
        Set<String> userCollects = runtimeTopicCollects.get(uid);
        if (userCollects == null) return false;
        return userCollects.remove(topicId);
    }

    /**
     * 获取完整的专题收藏 Map（用于遍历等操作）。
     */
    public Map<String, Set<String>> getTopicCollectsMap() {
        return runtimeTopicCollects;
    }

    // ==================== 用户数据管理 ====================

    /**
     * 初始化新用户的收藏/历史数据（全部为空）。
     */
    public void initNewUser(String userId) {
        runtimeCollects.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());
        runtimeHistory.computeIfAbsent(userId, k -> new ArrayList<>());
        runtimeTopicCollects.computeIfAbsent(userId, k -> new LinkedHashSet<>());
    }

    /**
     * 移除指定用户的所有收藏/历史数据（用户注销/重置时调用）。
     */
    public void removeUserData(String userId) {
        runtimeCollects.remove(userId);
        runtimeHistory.remove(userId);
        runtimeTopicCollects.remove(userId);
    }

    // ==================== 持久化：收藏 ====================

    /**
     * 从文件加载商品收藏数据。
     */
    public void loadCollectsFromFile() {
        JSONObject data = MockJsonPersistence.loadObject(COLLECTS_PATH);
        if (data == null) return;
        runtimeCollects.clear();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            JSONArray arr = (JSONArray) entry.getValue();
            Set<String> ids = ConcurrentHashMap.newKeySet();
            for (int i = 0; i < arr.size(); i++) {
                ids.add(arr.getStr(i));
            }
            runtimeCollects.put(entry.getKey(), ids);
        }
        log.info("Loaded {} users' collects from file", runtimeCollects.size());
    }

    /**
     * 将商品收藏数据持久化到文件。
     */
    public void saveCollects() {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, Set<String>> entry : runtimeCollects.entrySet()) {
            JSONArray arr = new JSONArray();
            for (String id : entry.getValue()) {
                arr.add(id);
            }
            obj.set(entry.getKey(), arr);
        }
        MockJsonPersistence.save(COLLECTS_PATH, obj);
    }

    // ==================== 持久化：浏览历史 ====================

    /**
     * 从文件加载浏览历史数据。
     */
    public void loadHistoryFromFile() {
        JSONObject data = MockJsonPersistence.loadObject(HISTORY_PATH);
        if (data == null) return;
        runtimeHistory.clear();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            JSONArray arr = (JSONArray) entry.getValue();
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                ids.add(arr.getStr(i));
            }
            runtimeHistory.put(entry.getKey(), ids);
        }
        log.info("Loaded {} users' history from file", runtimeHistory.size());
    }

    /**
     * 将浏览历史数据持久化到文件。
     */
    public void saveHistory() {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, List<String>> entry : runtimeHistory.entrySet()) {
            JSONArray arr = new JSONArray();
            for (String id : entry.getValue()) {
                arr.add(id);
            }
            obj.set(entry.getKey(), arr);
        }
        MockJsonPersistence.save(HISTORY_PATH, obj);
    }

    // ==================== 持久化：专题收藏 ====================

    /**
     * 从文件加载专题收藏数据。
     */
    public void loadTopicCollectsFromFile() {
        JSONObject data = MockJsonPersistence.loadObject(TOPIC_COLLECTS_PATH);
        if (data == null) return;
        runtimeTopicCollects.clear();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            JSONArray arr = (JSONArray) entry.getValue();
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < arr.size(); i++) {
                ids.add(arr.getStr(i));
            }
            runtimeTopicCollects.put(entry.getKey(), ids);
        }
        log.info("Loaded {} users' topic collects from file", runtimeTopicCollects.size());
    }

    /**
     * 将专题收藏数据持久化到文件。
     */
    public void saveTopicCollects() {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, Set<String>> entry : runtimeTopicCollects.entrySet()) {
            JSONArray arr = new JSONArray();
            for (String id : entry.getValue()) {
                arr.add(id);
            }
            obj.set(entry.getKey(), arr);
        }
        MockJsonPersistence.save(TOPIC_COLLECTS_PATH, obj);
    }

    // ==================== 批量持久化 ====================

    /**
     * 持久化所有数据（收藏 + 历史 + 专题收藏）。
     */
    public void saveAll() {
        saveCollects();
        saveHistory();
        saveTopicCollects();
    }
}
