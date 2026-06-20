package com.xtx.mock.service.admin;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.service.activity.UserActivityMockService;
import com.xtx.mock.service.order.StockChangeLogService;
import com.xtx.mock.service.preference.UserPreferenceMockService;
import com.xtx.mock.store.RuntimeCartStore;
import com.xtx.mock.store.RuntimeUserActivityStore;
import com.xtx.mock.util.MockStockUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * AdminRuntimeService — Admin / Runtime Lifecycle 业务逻辑。
 */
@Slf4j
public class AdminRuntimeService {

    // ==================== Callbacks ====================

    private Supplier<Integer> reloadMasterFilesCallback;
    private Runnable saveOrdersCallback;
    private Runnable savePointsCallback;
    private Runnable loadUsersFromFileCallback;
    private Runnable seedDefaultUserCallback;
    private Runnable saveUsersCallback;
    private Consumer<String> resetMemberFeatureDataCallback;
    private Consumer<String> seedMemberFeatureDataCallback;

    public void setReloadMasterFilesCallback(Supplier<Integer> cb) { this.reloadMasterFilesCallback = cb; }
    public void setSaveOrdersCallback(Runnable cb) { this.saveOrdersCallback = cb; }
    public void setSavePointsCallback(Runnable cb) { this.savePointsCallback = cb; }
    public void setLoadUsersFromFileCallback(Runnable cb) { this.loadUsersFromFileCallback = cb; }
    public void setSeedDefaultUserCallback(Runnable cb) { this.seedDefaultUserCallback = cb; }
    public void setSaveUsersCallback(Runnable cb) { this.saveUsersCallback = cb; }
    public void setResetMemberFeatureDataCallback(Consumer<String> cb) { this.resetMemberFeatureDataCallback = cb; }
    public void setSeedMemberFeatureDataCallback(Consumer<String> cb) { this.seedMemberFeatureDataCallback = cb; }

    // ==================== Store / Service 引用 ====================

    private RuntimeUserActivityStore userActivityStore;
    private UserActivityMockService userActivityService;
    private UserPreferenceMockService userPreferenceService;
    private RuntimeCartStore cartStore;
    private StockChangeLogService stockChangeLogService;

    public void setUserActivityStore(RuntimeUserActivityStore s) { this.userActivityStore = s; }
    public void setUserActivityService(UserActivityMockService s) { this.userActivityService = s; }
    public void setUserPreferenceService(UserPreferenceMockService s) { this.userPreferenceService = s; }
    public void setCartStore(RuntimeCartStore s) { this.cartStore = s; }
    public void setStockChangeLogService(StockChangeLogService s) { this.stockChangeLogService = s; }

    // ==================== 数据 Map 引用 ====================

    private Map<String, JSONObject> runtimeUsers;
    private Map<String, JSONObject> runtimeOrders;
    private JSONObject masterOrders;
    private JSONObject masterCarts;
    private JSONObject masterProducts;
    private JSONObject curatedDemoProducts;

    /** masterSkus 引用 — 运行时 SKU 库存数据 */
    private JSONObject masterSkus;

    public void setRuntimeUsers(Map<String, JSONObject> m) { this.runtimeUsers = m; }
    public void setRuntimeOrders(Map<String, JSONObject> m) { this.runtimeOrders = m; }
    public void setMasterOrders(JSONObject o) { this.masterOrders = o; }
    public void setMasterCarts(JSONObject o) { this.masterCarts = o; }
    public void setMasterProducts(JSONObject o) { this.masterProducts = o; }
    public void setCuratedDemoProducts(JSONObject o) { this.curatedDemoProducts = o; }
    public void setMasterSkus(JSONObject o) { this.masterSkus = o; }

    // ==================== 路径常量 ====================

    private String brandFollowRuntimeFile;
    private String orderRuntimeFile;
    private String userPointsPath;

    public void setBrandFollowRuntimeFile(String p) { this.brandFollowRuntimeFile = p; }
    public void setOrderRuntimeFile(String p) { this.orderRuntimeFile = p; }
    public void setUserPointsPath(String p) { this.userPointsPath = p; }

    // ==================== 用户 ID 常量 ====================

    private String defaultUserId;
    private String currentUserId;

    public void setDefaultUserId(String id) { this.defaultUserId = id; }
    public void setCurrentUserId(String id) { this.currentUserId = id; }

    // ==================== Admin 操作 ====================

    /**
     * POST /admin/reset-member-data — 重置默认用户运行时数据，恢复 seed。
     */
    public String resetMemberData() {
        log.info("Resetting member data for default user...");
        deleteFile(brandFollowRuntimeFile);
        deleteFile(orderRuntimeFile);
        deleteFile(userPointsPath);

        if (userActivityStore != null) userActivityStore.removeUserData(defaultUserId);
        if (userPreferenceService != null) userPreferenceService.removeUserBrandFollows(defaultUserId);

        if (runtimeOrders != null && masterOrders != null) {
            runtimeOrders.entrySet().removeIf(e -> defaultUserId.equals(e.getValue().getStr("userId", "")));
            for (Map.Entry<String, Object> entry : masterOrders.entrySet()) {
                JSONObject order = (JSONObject) entry.getValue();
                order.set("userId", defaultUserId);
                runtimeOrders.put(entry.getKey(), order);
            }
        }

        if (userActivityService != null) {
            userActivityService.seedDefaultCollects(defaultUserId, curatedDemoProducts, masterProducts);
            userActivityService.seedDefaultHistory(defaultUserId, curatedDemoProducts, masterProducts);
            userActivityService.seedDefaultTopicCollects(defaultUserId);
        }
        if (userPreferenceService != null) {
            userPreferenceService.seedDefaultBrandFollows();
            userPreferenceService.saveBrandFollows();
        }

        if (resetMemberFeatureDataCallback != null) {
            resetMemberFeatureDataCallback.accept(defaultUserId);
        }

        if (cartStore != null && masterCarts != null) {
            cartStore.reloadFromMaster(masterCarts, currentUserId);
            cartStore.save();
        }

        if (saveOrdersCallback != null) saveOrdersCallback.run();
        if (savePointsCallback != null) savePointsCallback.run();

        return "member data reset OK";
    }

    /**
     * POST /admin/reload — 重新加载所有 master 数据文件。
     */
    public String reloadMasterData() {
        log.info("Reloading master data...");

        int loaded = 0;
        if (reloadMasterFilesCallback != null) {
            loaded = reloadMasterFilesCallback.get();
        }

        if (cartStore != null && masterCarts != null) {
            cartStore.reloadFromMaster(masterCarts, currentUserId);
            cartStore.save();
        }

        if (runtimeUsers != null && runtimeUsers.isEmpty()) {
            if (loadUsersFromFileCallback != null) {
                loadUsersFromFileCallback.run();
            } else if (seedDefaultUserCallback != null && saveUsersCallback != null) {
                seedDefaultUserCallback.run();
                saveUsersCallback.run();
            }
        }

        if (userActivityStore != null) {
            if (userActivityStore.getCollectsMap().isEmpty()) {
                userActivityStore.loadCollectsFromFile();
            }
            if (userActivityStore.getTopicCollectsMap().isEmpty()) {
                userActivityStore.loadTopicCollectsFromFile();
            }
        }

        if (userPreferenceService != null && userPreferenceService.isBrandFollowsEmpty()) {
            userPreferenceService.loadBrandFollowsFromFile();
        }

        if (seedMemberFeatureDataCallback != null) {
            seedMemberFeatureDataCallback.accept(defaultUserId);
        }

        log.info("Master data reload complete: {} files loaded", loaded);
        return "Master data reloaded: " + loaded + " files";
    }

    // ==================== 库存一致性检查 ====================

    /**
     * GET /admin/inventory/check — 检查所有 SKU 和 SPU 库存一致性。
     * <p>
     * 检查项：
     * <ul>
     *   <li>totalStock = availableStock + lockedStock + soldStock</li>
     *   <li>availableStock >= 0</li>
     *   <li>lockedStock >= 0</li>
     *   <li>soldStock >= 0</li>
     *   <li>stockStatus 与 availableStock 匹配</li>
     * </ul>
     */
    public JSONObject checkInventory() {
        JSONArray invalidItems = new JSONArray();

        // 检查 SKU
        if (masterSkus != null) {
            for (Map.Entry<String, Object> entry : masterSkus.entrySet()) {
                JSONObject sku = (JSONObject) entry.getValue();
                String error = MockStockUtils.checkConsistency(sku, entry.getKey());
                if (error != null) {
                    JSONObject item = new JSONObject();
                    item.set("skuId", entry.getKey());
                    item.set("type", "SKU");
                    item.set("reason", error);
                    invalidItems.add(item);
                }
            }
        }

        // 检查 SPU
        if (masterProducts != null) {
            for (Map.Entry<String, Object> entry : masterProducts.entrySet()) {
                JSONObject product = (JSONObject) entry.getValue();
                String error = MockStockUtils.checkConsistency(product, entry.getKey());
                if (error != null) {
                    JSONObject item = new JSONObject();
                    item.set("productId", entry.getKey());
                    item.set("type", "SPU");
                    item.set("reason", error);
                    invalidItems.add(item);
                }
            }
        }

        JSONObject result = new JSONObject();
        result.set("valid", invalidItems.isEmpty());
        result.set("invalidItems", invalidItems);
        // 汇总信息
        JSONObject summary = new JSONObject();
        summary.set("totalSkus", masterSkus != null ? masterSkus.size() : 0);
        summary.set("totalProducts", masterProducts != null ? masterProducts.size() : 0);
        summary.set("invalidCount", invalidItems.size());
        result.set("summary", summary);

        return result;
    }

    // ==================== 库存重置 ====================

    /**
     * POST /admin/reset-inventory-data — 重置所有库存数据。
     * <p>
     * 操作：
     * <ol>
     *   <li>从 master JSON 文件重新加载 SKU 和商品库存</li>
     *   <li>清空库存变更日志</li>
     *   <li>清空订单运行时数据（可选）</li>
     *   <li>清空购物车运行时数据（可选）</li>
     * </ol>
     */
    public String resetInventoryData(boolean clearOrders, boolean clearCarts) {
        log.info("Resetting inventory data (clearOrders={}, clearCarts={})...", clearOrders, clearCarts);

        // 重新加载 master SKU 和 Product 数据
        if (reloadMasterFilesCallback != null) {
            reloadMasterFilesCallback.get();
        }

        // 清空库存变更日志
        if (stockChangeLogService != null) {
            stockChangeLogService.clearAll();
        }

        // 重新 seed 运行时库存字段（添加 lockedStock=0, soldStock=0）
        seedInventoryDerivedFields();

        // 可选：清空订单
        if (clearOrders && runtimeOrders != null) {
            runtimeOrders.clear();
            if (saveOrdersCallback != null) saveOrdersCallback.run();
        }

        // 可选：清空购物车
        if (clearCarts && cartStore != null) {
            cartStore.clearAll();
            cartStore.save();
        }

        return "Inventory data reset OK";
    }

    /**
     * 为所有 SKU 和 SPU 补充 lockedStock=0, soldStock=0 字段（兼容旧数据）。
     */
    private void seedInventoryDerivedFields() {
        if (masterSkus != null) {
            for (Map.Entry<String, Object> entry : masterSkus.entrySet()) {
                JSONObject sku = (JSONObject) entry.getValue();
                if (!sku.containsKey("lockedStock")) sku.set("lockedStock", 0);
                if (!sku.containsKey("soldStock")) sku.set("soldStock", 0);
                MockStockUtils.applyDerivedStockFields(sku);
            }
        }
        if (masterProducts != null) {
            for (Map.Entry<String, Object> entry : masterProducts.entrySet()) {
                JSONObject product = (JSONObject) entry.getValue();
                if (!product.containsKey("lockedStock")) product.set("lockedStock", 0);
                if (!product.containsKey("soldStock")) product.set("soldStock", 0);
                MockStockUtils.applyDerivedStockFields(product);
            }
        }
    }

    // ==================== 辅助方法 ====================

    private void deleteFile(String path) {
        if (path == null) return;
        try {
            java.io.File f = new java.io.File(path);
            if (f.exists()) f.delete();
        } catch (Exception e) {
            log.warn("Failed to delete runtime file: {}", path, e);
        }
    }
}
