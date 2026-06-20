package com.xtx.mock.store;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.support.MockJsonPersistence;
import com.xtx.mock.support.MockRuntimePaths;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RuntimeAfterSaleStore — 售后模块运行时存储。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理 runtimeAfterSales 内存态 Map（按 userId 隔离）</li>
 *   <li>文件持久化（data/user-after-sales-runtime.json）</li>
 *   <li>新用户空数据初始化</li>
 *   <li>用户数据移除（重置/注销）</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockAfterSaleController} 直接实例化。
 */
@Slf4j
public class RuntimeAfterSaleStore {

    /** 运行时售后数据 (文件持久化), keyed by userId → JSONArray of after-sale records */
    private final Map<String, JSONArray> runtimeAfterSales = new ConcurrentHashMap<>();

    private static final String PERSISTENCE_PATH = MockRuntimePaths.USER_AFTER_SALES;

    // ==================== 售后数据访问 ====================

    /**
     * 获取指定用户的售后记录列表。如果首次访问且数据不存在，返回空数组。
     */
    public JSONArray getAfterSales(String uid) {
        return runtimeAfterSales.computeIfAbsent(uid, k -> new JSONArray());
    }

    /**
     * 获取指定用户的售后记录列表（直接从内存取，不创建默认值）。
     */
    public JSONArray getAfterSalesRaw(String uid) {
        return runtimeAfterSales.get(uid);
    }

    /**
     * 添加一条售后记录到用户列表头部。
     */
    public void addAfterSale(String uid, JSONObject item) {
        JSONArray sales = runtimeAfterSales.computeIfAbsent(uid, k -> new JSONArray());
        sales.add(0, item);
    }

    // ==================== 数据管理 ====================

    /**
     * 初始化新用户的售后数据（空数组）。
     */
    public void initNewUser(String userId) {
        runtimeAfterSales.computeIfAbsent(userId, k -> new JSONArray());
    }

    /**
     * 移除指定用户的所有售后数据（用户注销/重置时调用）。
     */
    public void removeUserData(String userId) {
        runtimeAfterSales.remove(userId);
    }

    /**
     * 获取完整的内存态 Map（用于遍历等操作）。
     */
    public Map<String, JSONArray> getAfterSalesMap() {
        return runtimeAfterSales;
    }

    // ==================== 持久化 ====================

    /**
     * 从文件加载售后数据。
     * <p>
     * 文件格式：{ "user_xxx": [{...record...}], "user_yyy": [{...record...}] }
     */
    public void loadFromFile() {
        JSONObject data = MockJsonPersistence.loadObject(PERSISTENCE_PATH);
        if (data == null) {
            log.info("No after-sales runtime file found at {}, starting fresh", PERSISTENCE_PATH);
            return;
        }
        int count = 0;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String userId = entry.getKey();
            if (entry.getValue() instanceof JSONArray) {
                runtimeAfterSales.put(userId, (JSONArray) entry.getValue());
                count++;
            }
        }
        log.info("Loaded after-sales data for {} users", count);
    }

    /**
     * 将售后数据持久化到文件。
     */
    public void save() {
        JSONObject data = new JSONObject();
        for (Map.Entry<String, JSONArray> entry : runtimeAfterSales.entrySet()) {
            data.set(entry.getKey(), entry.getValue());
        }
        MockJsonPersistence.save(PERSISTENCE_PATH, data);
    }
}
