package com.xtx.mock.service.admin;

import cn.hutool.json.JSONObject;
import com.xtx.mock.service.activity.UserActivityMockService;
import com.xtx.mock.service.preference.UserPreferenceMockService;
import com.xtx.mock.store.RuntimeCartStore;
import com.xtx.mock.store.RuntimeUserActivityStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * AdminRuntimeService — Admin / Runtime Lifecycle 业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>POST /admin/reset-member-data — 重置默认用户运行时数据，恢复 seed</li>
 *   <li>POST /admin/reload — 重新加载 master 数据文件，保留 runtime 数据</li>
 * </ul>
 * <p>
 * 数据源引用（由 MockController @PostConstruct 注入）：
 * <ul>
 *   <li>reloadMasterFilesCallback — 重新加载 master JSON 文件并设置私有字段</li>
 *   <li>saveOrdersCallback — 持久化 runtimeOrders 到文件</li>
 *   <li>loadUsersFromFileCallback — 从文件加载 runtimeUsers</li>
 *   <li>seedDefaultUserCallback — 创建默认用户 xiaotuxian001</li>
 *   <li>saveUsersCallback — 持久化 runtimeUsers 到文件</li>
 *   <li>resetMemberFeatureDataCallback — 清除并重新 seed 特征数据</li>
 *   <li>seedMemberFeatureDataCallback — seed 特征数据（computeIfAbsent）</li>
 *   <li>store/service 引用 — 通过公共 API 操作运行时数据</li>
 * </ul>
 */
@Slf4j
public class AdminRuntimeService {

    // ==================== Callbacks（MockController 私有操作） ====================

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

    public void setUserActivityStore(RuntimeUserActivityStore s) { this.userActivityStore = s; }
    public void setUserActivityService(UserActivityMockService s) { this.userActivityService = s; }
    public void setUserPreferenceService(UserPreferenceMockService s) { this.userPreferenceService = s; }
    public void setCartStore(RuntimeCartStore s) { this.cartStore = s; }

    // ==================== 数据 Map 引用 ====================

    private Map<String, JSONObject> runtimeUsers;
    private Map<String, JSONObject> runtimeOrders;
    private JSONObject masterOrders;
    private JSONObject masterCarts;
    private JSONObject masterProducts;
    private JSONObject curatedDemoProducts;

    public void setRuntimeUsers(Map<String, JSONObject> m) { this.runtimeUsers = m; }
    public void setRuntimeOrders(Map<String, JSONObject> m) { this.runtimeOrders = m; }
    public void setMasterOrders(JSONObject o) { this.masterOrders = o; }
    public void setMasterCarts(JSONObject o) { this.masterCarts = o; }
    public void setMasterProducts(JSONObject o) { this.masterProducts = o; }
    public void setCuratedDemoProducts(JSONObject o) { this.curatedDemoProducts = o; }

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
     * <p>
     * 清除 runtime 文件、内存数据、重新 seed 所有默认用户数据。
     * 不影响其他 runtime 用户。
     *
     * @return 成功消息
     */
    public String resetMemberData() {
        log.info("Resetting member data for default user...");
        // 清除 runtime 文件
        deleteFile(brandFollowRuntimeFile);
        deleteFile(orderRuntimeFile);
        deleteFile(userPointsPath);

        // 清除内存数据
        if (userActivityStore != null) userActivityStore.removeUserData(defaultUserId);
        if (userPreferenceService != null) userPreferenceService.removeUserBrandFollows(defaultUserId);

        // 重置运行时订单：清除默认用户订单，从 master 重新 seed
        if (runtimeOrders != null && masterOrders != null) {
            runtimeOrders.entrySet().removeIf(e -> defaultUserId.equals(e.getValue().getStr("userId", "")));
            for (Map.Entry<String, Object> entry : masterOrders.entrySet()) {
                JSONObject order = (JSONObject) entry.getValue();
                order.set("userId", defaultUserId);
                runtimeOrders.put(entry.getKey(), order);
            }
        }

        // 重新 seed
        if (userActivityService != null) {
            userActivityService.seedDefaultCollects(defaultUserId, curatedDemoProducts, masterProducts);
            userActivityService.seedDefaultHistory(defaultUserId, curatedDemoProducts, masterProducts);
            userActivityService.seedDefaultTopicCollects(defaultUserId);
        }
        if (userPreferenceService != null) {
            userPreferenceService.seedDefaultBrandFollows();
            userPreferenceService.saveBrandFollows();
        }

        // 重置特征数据（points/review/afterSale/invite/game）
        if (resetMemberFeatureDataCallback != null) {
            resetMemberFeatureDataCallback.accept(defaultUserId);
        }

        // 重置购物车：从 master 重新 seed 并持久化
        if (cartStore != null && masterCarts != null) {
            cartStore.reloadFromMaster(masterCarts, currentUserId);
            cartStore.save();
        }

        // 持久化
        if (saveOrdersCallback != null) saveOrdersCallback.run();
        if (savePointsCallback != null) savePointsCallback.run();

        return "member data reset OK";
    }

    /**
     * POST /admin/reload — 重新加载所有 master 数据文件，无需重启服务。
     * <p>
     * 保留 runtime 用户数据、地址、订单、积分等持久化状态。
     * 仅当运行时数据为空时从文件加载。
     *
     * @return 成功消息含加载文件数
     */
    public String reloadMasterData() {
        log.info("Reloading master data...");

        // 1. 重新加载 master JSON 文件（由 MockController callback 处理私有字段）
        int loaded = 0;
        if (reloadMasterFilesCallback != null) {
            loaded = reloadMasterFilesCallback.get();
        }

        // 2. 通过 store 重置运行时购物车
        if (cartStore != null && masterCarts != null) {
            cartStore.reloadFromMaster(masterCarts, currentUserId);
            cartStore.save();
        }

        // 3. 地址不做清除，保留持久化状态

        // 4. 用户不做清除，保留持久化状态
        if (runtimeUsers != null && runtimeUsers.isEmpty()) {
            if (loadUsersFromFileCallback != null) {
                loadUsersFromFileCallback.run();
            } else if (seedDefaultUserCallback != null && saveUsersCallback != null) {
                seedDefaultUserCallback.run();
                saveUsersCallback.run();
            }
        }

        // 5. 用户行为不做清除，保留持久化状态
        if (userActivityStore != null) {
            if (userActivityStore.getCollectsMap().isEmpty()) {
                userActivityStore.loadCollectsFromFile();
            }
            if (userActivityStore.getTopicCollectsMap().isEmpty()) {
                userActivityStore.loadTopicCollectsFromFile();
            }
        }

        // 6. 品牌关注不做清除，保留持久化状态
        if (userPreferenceService != null && userPreferenceService.isBrandFollowsEmpty()) {
            userPreferenceService.loadBrandFollowsFromFile();
        }

        // 7. 不 destroy runtime data — seedMemberFeatureData 内部使用 computeIfAbsent，仅补充缺失项
        if (seedMemberFeatureDataCallback != null) {
            seedMemberFeatureDataCallback.accept(defaultUserId);
        }

        log.info("Master data reload complete: {} files loaded", loaded);
        return "Master data reloaded: " + loaded + " files";
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
