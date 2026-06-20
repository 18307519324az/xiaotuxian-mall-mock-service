package com.xtx.mock.store;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.support.MockJsonPersistence;
import com.xtx.mock.support.MockRuntimePaths;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RuntimeCartStore — 购物车运行时存储。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理 runtimeCarts 内存态 Map（按 cartId → JSONObject）</li>
 *   <li>管理 cartIdCounter 自增 ID 生成器</li>
 *   <li>文件持久化（data/user-carts-runtime.json）</li>
 *   <li>新用户空数据初始化</li>
 *   <li>用户数据移除（重置/注销）</li>
 *   <li>提供 selected=true 过滤读访问（供 Order/Checkout 模块使用）</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockCartController} 直接实例化。
 */
@Slf4j
public class RuntimeCartStore {

    /** 运行时购物车 (文件持久化), keyed by cart entry ID (e.g. "cart_001") */
    private final Map<String, JSONObject> runtimeCarts = new ConcurrentHashMap<>();

    /** 购物车 ID 自增计数器 */
    private final AtomicInteger cartIdCounter = new AtomicInteger(0);

    // ==================== 持久化路径 ====================

    private static final String CARTS_PATH = MockRuntimePaths.USER_CARTS;

    // ==================== 购物车访问 ====================

    /**
     * 获取完整的购物车 Map。
     */
    public Map<String, JSONObject> getAllCarts() {
        return runtimeCarts;
    }

    /**
     * 获取指定用户的所有购物车条目（按 userId 过滤）。
     * 返回条目列表，每个条目已展开为响应格式。
     */
    public List<JSONObject> getCartsByUserId(String uid) {
        List<JSONObject> result = new ArrayList<>();
        for (Map.Entry<String, JSONObject> entry : runtimeCarts.entrySet()) {
            JSONObject cart = entry.getValue();
            if (uid.equals(cart.getStr("userId", ""))) {
                JSONObject item = new JSONObject();
                item.set("id", cart.getStr("id"));
                item.set("skuId", cart.getStr("skuId"));
                item.set("goodsId", cart.getStr("goodsId"));
                item.set("name", cart.getStr("name"));
                item.set("attrsText", cart.getStr("attrsText"));
                item.set("picture", cart.getStr("picture", ""));
                item.set("price", cart.getStr("price"));
                item.set("nowPrice", cart.getStr("nowPrice"));
                item.set("selected", cart.getBool("selected", true));
                item.set("stock", cart.getInt("stock", 100));
                item.set("count", cart.getInt("count", 1));
                item.set("isEffective", cart.getBool("isEffective", true));
                item.set("createTime", cart.getStr("createTime", ""));
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 获取指定用户 selected=true 的购物车条目（供 Order/Checkout 使用）。
     */
    public List<JSONObject> getSelectedCartsByUserId(String uid) {
        List<JSONObject> result = new ArrayList<>();
        for (Map.Entry<String, JSONObject> entry : runtimeCarts.entrySet()) {
            JSONObject cart = entry.getValue();
            if (uid.equals(cart.getStr("userId", "")) && cart.getBool("selected", true)) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    /**
     * 获取指定用户选中商品的 SKU ID 列表（供 Order/Checkout 使用）。
     */
    public List<String> getSelectedSkuIdsByUserId(String uid) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, JSONObject> entry : runtimeCarts.entrySet()) {
            JSONObject cart = entry.getValue();
            if (uid.equals(cart.getStr("userId", "")) && cart.getBool("selected", true)) {
                result.add(cart.getStr("skuId"));
            }
        }
        return result;
    }

    /**
     * 按 entry key 获取购物车条目。
     */
    public JSONObject getCartById(String cartId) {
        return runtimeCarts.get(cartId);
    }

    /**
     * 按 skuId + userId 查找购物车条目。
     *
     * @return 条目 entry, 或 null 如果未找到
     */
    public Map.Entry<String, JSONObject> findCartBySkuId(String skuId, String uid) {
        for (Map.Entry<String, JSONObject> entry : runtimeCarts.entrySet()) {
            JSONObject cart = entry.getValue();
            if (skuId.equals(cart.getStr("skuId")) && uid.equals(cart.getStr("userId", ""))) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 添加购物车条目。
     */
    public void putCart(String cartId, JSONObject cartItem) {
        runtimeCarts.put(cartId, cartItem);
    }

    /**
     * 移除购物车条目。
     */
    public void removeCart(String cartId) {
        runtimeCarts.remove(cartId);
    }

    /**
     * 移除指定用户匹配 ids（支持 cart entry key 或 skuId）的购物车条目。
     */
    public void removeCartsByUserAndIds(String uid, List<?> ids) {
        runtimeCarts.entrySet().removeIf(entry -> {
            JSONObject cart = entry.getValue();
            if (!uid.equals(cart.getStr("userId", ""))) return false;
            return ids.contains(entry.getKey()) || ids.contains(cart.getStr("skuId"));
        });
    }

    /**
     * 获取自增计数器当前值后续 ID 并递增。
     */
    public int incrementAndGetCartId() {
        return cartIdCounter.incrementAndGet();
    }

    /**
     * 获取自增计数器当前值。
     */
    public int getCartIdCounter() {
        return cartIdCounter.get();
    }

    /**
     * 设置自增计数器（从加载的 seed 数据恢复）。
     */
    public void setCartIdCounter(int value) {
        cartIdCounter.set(value);
    }

    // ==================== 用户数据管理 ====================

    /**
     * 从 master 数据中 seed 默认用户的购物车数据。
     * 仅在首次加载（文件不存在）时调用。
     */
    public void seedFromMaster(JSONObject masterCarts, String userId) {
        for (Map.Entry<String, Object> entry : masterCarts.entrySet()) {
            JSONObject cart = (JSONObject) entry.getValue();
            cart.set("userId", userId);
            runtimeCarts.put(entry.getKey(), cart);
            String numPart = entry.getKey().replaceAll("[^0-9]", "");
            try {
                cartIdCounter.set(Math.max(cartIdCounter.get(), Integer.parseInt(numPart)));
            } catch (Exception ignored) {}
        }
        log.info("Seeded {} carts for user {} from master, cartIdCounter={}",
                masterCarts.size(), userId, cartIdCounter.get());
    }

    /**
     * 从 master 数据重新 seed（用于 /admin/reload）。
     * 清除当前所有数据后重新加载。
     */
    public void reloadFromMaster(JSONObject masterCarts, String userId) {
        runtimeCarts.clear();
        cartIdCounter.set(0);
        seedFromMaster(masterCarts, userId);
    }

    /**
     * 初始化新用户的购物车数据（全部为空）。
     */
    public void initNewUser(String userId) {
        // 购物车不需要预创建条目，首次写入时自动创建
        log.debug("Init empty cart for new user: {}", userId);
    }

    /**
     * 移除指定用户的所有购物车数据（用户注销/重置时调用）。
     */
    public void removeUserData(String userId) {
        runtimeCarts.entrySet().removeIf(entry -> {
            JSONObject cart = entry.getValue();
            return userId.equals(cart.getStr("userId", ""));
        });
        log.info("Removed cart data for user: {}", userId);
    }

    /**
     * 清空所有购物车数据（重置时调用）。
     */
    public void clearAll() {
        runtimeCarts.clear();
        cartIdCounter.set(0);
    }

    // ==================== 持久化 ====================

    /**
     * 从文件加载购物车数据。
     */
    public void loadFromFile() {
        JSONObject data = MockJsonPersistence.loadObject(CARTS_PATH);
        if (data == null) return;
        runtimeCarts.clear();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            runtimeCarts.put(entry.getKey(), (JSONObject) entry.getValue());
        }
        // 从已加载的 ID 中恢复计数器
        for (String key : runtimeCarts.keySet()) {
            String numPart = key.replaceAll("[^0-9]", "");
            try {
                cartIdCounter.set(Math.max(cartIdCounter.get(), Integer.parseInt(numPart)));
            } catch (Exception ignored) {}
        }
        log.info("Loaded {} carts from file, cartIdCounter={}", runtimeCarts.size(), cartIdCounter.get());
    }

    /**
     * 将购物车数据持久化到文件。
     */
    public void save() {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, JSONObject> entry : runtimeCarts.entrySet()) {
            obj.set(entry.getKey(), entry.getValue());
        }
        MockJsonPersistence.save(CARTS_PATH, obj);
    }
}
