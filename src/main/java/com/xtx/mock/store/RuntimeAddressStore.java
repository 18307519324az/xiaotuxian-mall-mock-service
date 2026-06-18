package com.xtx.mock.store;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xtx.mock.support.MockJsonPersistence;
import com.xtx.mock.support.MockRuntimePaths;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户收货地址运行时存储。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理 runtimeAddresses 内存态 Map</li>
 *   <li>文件持久化（data/user-addresses-runtime.json，通过 MockJsonPersistence）</li>
 *   <li>从 seed JSON 初始化</li>
 *   <li>用户隔离查询、默认地址互斥</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockAddressController} 直接实例化。
 */
@Slf4j
public class RuntimeAddressStore {

    private static final String CURRENT_USER_ID = "xiaotuxian001";

    /** 运行时地址 (内存态, 持久化到文件), keyed by address ID */
    private final Map<String, JSONObject> runtimeAddresses = new ConcurrentHashMap<>();

    private JSONArray masterUserAddresses;

    /**
     * 无参构造器：由 MockAddressController 直接实例化。
     */
    public RuntimeAddressStore() {
    }

    // ==================== 初始化 ====================

    /**
     * 从文件或 seed 加载地址。在服务启动 /admin/reload 时调用。
     */
    public void init() {
        JSONArray existing = MockJsonPersistence.loadArray(MockRuntimePaths.USER_ADDRESSES);
        if (existing != null && !existing.isEmpty()) {
            loadFromPersistence(existing);
        } else {
            try {
                masterUserAddresses = JSONUtil.parseArray(
                        ResourceUtil.readStr("mock/master/user-addresses.json", StandardCharsets.UTF_8));
            } catch (Exception e) {
                masterUserAddresses = new JSONArray();
            }
            loadFromSeed();
            saveToFile();
        }
    }

    // ==================== 查询 ====================

    /**
     * 返回指定用户的所有地址列表，按默认地址优先。
     */
    public JSONArray listByUserId(String uid) {
        JSONArray result = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : runtimeAddresses.entrySet()) {
            JSONObject addr = entry.getValue();
            if (uid.equals(addr.getStr("userId"))) {
                result.add(addr);
            }
        }
        return result;
    }

    /**
     * 根据 ID 获取地址。
     */
    public JSONObject getById(String id) {
        return runtimeAddresses.get(id);
    }

    // ==================== 写操作 ====================

    /**
     * 新增地址，返回完整地址对象（含 id）。
     * 自动处理默认地址互斥。
     */
    public JSONObject add(Map<String, Object> params, String uid) {
        String id = "addr_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        JSONObject addr = new JSONObject();
        addr.set("id", id);
        addr.set("userId", uid);
        for (String key : new String[]{"receiver", "contact", "provinceCode", "cityCode", "countyCode",
                "address", "postalCode", "addressTags", "isDefault", "fullLocation"}) {
            if (params.containsKey(key)) {
                addr.set(key, params.get(key));
            }
        }
        runtimeAddresses.put(id, addr);
        // isDefault enforcement: isDefault===0 表示默认，仅允许一个默认地址
        if (addr.getInt("isDefault", 1) == 0) {
            for (Map.Entry<String, JSONObject> entry : runtimeAddresses.entrySet()) {
                JSONObject a = entry.getValue();
                if (uid.equals(a.getStr("userId")) && !a.getStr("id").equals(id) && a.getInt("isDefault", 1) == 0) {
                    a.set("isDefault", 1);
                }
            }
        }
        saveToFile();
        return addr;
    }

    /**
     * 更新地址字段，返回更新后的地址对象。
     * 自动处理默认地址互斥。
     */
    public JSONObject update(String id, Map<String, Object> params, String uid) {
        JSONObject existing = runtimeAddresses.get(id);
        if (existing == null) return null;
        if (!uid.equals(existing.getStr("userId"))) return null;
        for (String key : new String[]{"receiver", "contact", "provinceCode", "cityCode", "countyCode",
                "address", "postalCode", "addressTags", "isDefault", "fullLocation"}) {
            if (params.containsKey(key)) {
                existing.set(key, params.get(key));
            }
        }
        // isDefault enforcement: isDefault===0 表示默认，仅允许一个默认地址
        int newDefault = existing.getInt("isDefault", 1);
        if (newDefault == 0) {
            for (Map.Entry<String, JSONObject> entry : runtimeAddresses.entrySet()) {
                JSONObject a = entry.getValue();
                if (uid.equals(a.getStr("userId")) && !a.getStr("id").equals(id) && a.getInt("isDefault", 1) == 0) {
                    a.set("isDefault", 1);
                }
            }
        }
        saveToFile();
        return existing;
    }

    /**
     * 删除地址。
     */
    public boolean delete(String id, String uid) {
        JSONObject existing = runtimeAddresses.get(id);
        if (existing == null) return false;
        if (!uid.equals(existing.getStr("userId"))) return false;
        runtimeAddresses.remove(id);
        saveToFile();
        return true;
    }

    /**
     * 重置为 seed 地址。
     */
    public void resetToSeed() {
        runtimeAddresses.clear();
        loadFromSeed();
        saveToFile();
    }

    /**
     * 返回当前地址数量。
     */
    public int count() {
        return runtimeAddresses.size();
    }

    // ==================== 持久化 ====================

    /** 持久化地址数据到文件 */
    private synchronized void saveToFile() {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject addr : runtimeAddresses.values()) {
                if (!addr.containsKey("userId")) {
                    addr.set("userId", CURRENT_USER_ID);
                }
                arr.add(addr);
            }
            MockJsonPersistence.save(MockRuntimePaths.USER_ADDRESSES, arr);
        } catch (Exception e) {
            log.error("保存地址失败", e);
        }
    }

    /** 从持久化文件加载地址数据 */
    private void loadFromPersistence(JSONArray arr) {
        runtimeAddresses.clear();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject addr = arr.getJSONObject(i);
            if (!addr.containsKey("userId")) {
                addr.set("userId", CURRENT_USER_ID);
            }
            runtimeAddresses.put(addr.getStr("id"), addr);
        }
        log.info("从文件加载 {} 条地址", runtimeAddresses.size());
    }

    /** 从 seed JSON 加载默认地址 */
    private void loadFromSeed() {
        try {
            JSONArray seedArr = (masterUserAddresses != null) ? masterUserAddresses
                    : JSONUtil.parseArray(ResourceUtil.readStr("mock/master/user-addresses.json", StandardCharsets.UTF_8));
            for (int i = 0; i < seedArr.size(); i++) {
                JSONObject addr = seedArr.getJSONObject(i);
                if (!addr.containsKey("userId")) {
                    addr.set("userId", CURRENT_USER_ID);
                }
                if (!addr.containsKey("isDefault")) {
                    addr.set("isDefault", 0);
                }
                if (!addr.containsKey("id")) {
                    addr.set("id", "address_seed_" + i);
                }
                runtimeAddresses.put(addr.getStr("id"), addr);
            }
            log.info("从 seed 加载 {} 条地址（已补齐 userId/isDefault/id）", runtimeAddresses.size());
        } catch (Exception e) {
            log.error("加载 seed 地址失败", e);
        }
    }
}
