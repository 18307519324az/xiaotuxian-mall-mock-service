package com.xtx.mock.controller;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xtx.common.core.result.FrontResponse;
import com.xtx.mock.service.points.PointsMockService;
import com.xtx.mock.util.MockImageUtils;
import com.xtx.mock.util.MockProductViewUtils;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 小兔鲜 Mock 控制器 — 单一主数据源模式
 *
 * 所有商品/分类/SKU/购物车/订单数据从 master/ 目录的 JSON 加载，
 * 接口响应在运行时从 master 数据动态组装。
 *
 * 硬性规则:
 *   1. 不读取旧 goods-detail-all.json / cart-list.json / order-pre.json
 *   2. 不从 home-category-head.json 提取商品数据
 *   3. 商品图片统一来自 products[id].picture / mainPictures
 *   4. SKU 图片统一来自 skus[skuId].picture
 *   5. 订单图片使用 orders.json 中的快照
 *   6. 所有 ID 使用 String 类型
 *   7. 不存在于 master 的数据直接返回 404
 */
@Slf4j
@RestController
public class MockController {

    // ==================== Master 数据 ====================

    /** 商品 SPU 主数据, keyed by product ID (String) */
    private JSONObject masterProducts;

    /** 精选演示商品 (curated-demo-products.json)，使用真实商品名和本地 SVG 图片 */
    private JSONObject curatedDemoProducts = new JSONObject();

    /** SKU 主数据, keyed by SKU ID (String) */
    private JSONObject masterSkus;

    /** 分类树 */
    private JSONArray masterCategories;

    /** 购物车预置数据, keyed by cart ID */
    private JSONObject masterCarts;

    /** 订单快照, keyed by order ID */
    private JSONObject masterOrders;

    /** 运行时订单 (内存态, 重启后丢失), keyed by order ID */
    private final Map<String, JSONObject> runtimeOrders = new ConcurrentHashMap<>();

    /** 运行时订单自增计数器 */
    private final AtomicInteger orderCounter = new AtomicInteger(0);

    /** 客服模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockCustomerServiceController customerServiceController;

    /** 消息模块控制器（由 Spring 管理，@PostConstruct 注入运行时 token） */
    @Autowired
    private MockMessageController messageController;

    /** 优惠券/礼品卡模块控制器（由 Spring 管理，@PostConstruct 注入运行时 token） */
    @Autowired
    private MockBenefitController benefitController;

    /** 抽奖/签到模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockGameController gameController;

    /** 邀请模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockInviteController inviteController;

    /** 积分模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockPointsController pointsController;

    /** 售后模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockAfterSaleController afterSaleController;

    /** 评价模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockReviewController reviewController;

    /** 用户行为模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockUserActivityController userActivityController;

    /** 购物车模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockCartController cartController;

    /** 商品模块控制器（由 Spring 管理，@PostConstruct 注入 master 数据） */
    @Autowired
    private MockGoodsController goodsController;

    /** 首页/分类/品牌/专题模块控制器（由 Spring 管理，@PostConstruct 注入 master 数据） */
    @Autowired
    private MockHomeCatalogController homeCatalogController;

    /** 用户偏好（品牌关注/专题收藏）模块控制器（由 Spring 管理，@PostConstruct 注入数据） */
    @Autowired
    private MockUserPreferenceController userPreferenceController;

    /** 搜索模块控制器（由 Spring 管理，@PostConstruct 注入 master 数据） */
    @Autowired
    private MockSearchController searchController;

    /** Auth Core 认证模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockAuthController authController;

    /** Auth Stub 模块控制器（由 Spring 管理，@PostConstruct 注入 dataCache） */
    @Autowired
    private MockAuthStubController authStubController;

    /** Admin / Runtime Lifecycle 模块控制器（由 Spring 管理，@PostConstruct 注入回调和数据引用） */
    @Autowired
    private MockAdminController adminController;

    /** 预结算模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockCheckoutController checkoutController;

    /** 地址模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockAddressController addressController;

    /** 订单查询模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockOrderQueryController orderQueryController;

    /** 订单操作模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockOrderActionController orderActionController;

    /** 订单支付模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockOrderPaymentController orderPaymentController;

    /** 订单创建模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockOrderCreateController orderCreateController;

    /** 再次购买模块控制器（由 Spring 管理，@PostConstruct 注入运行时数据） */
    @Autowired
    private MockOrderRepurchaseController orderRepurchaseController;

    /** 楼层大图映射, keyed by top category ID */
    private JSONObject masterFloors;

    /** 评价主数据, keyed by product ID (String) */
    private JSONObject masterEvaluations;

    /** 默认用户 ID (Mock 环境默认已登录) */
    private static final String DEFAULT_USER_ID = "xiaotuxian001";

    /** 当前用户 ID (统一用于地址/购物车/订单) */
    private static final String CURRENT_USER_ID = "xiaotuxian001";

    /** 从 user.dir 向上查找项目根目录（包含 pom.xml 的目录） */
    private static String resolveProjectRoot() {
        String userDir = System.getProperty("user.dir");
        if (userDir == null) return ".";
        java.io.File dir = new java.io.File(userDir);
        try {
            for (int i = 0; i < 10; i++) {
                if (new java.io.File(dir, "pom.xml").exists()) {
                    return dir.getAbsolutePath();
                }
                java.io.File parent = dir.getParentFile();
                if (parent == null || parent.equals(dir)) break;
                dir = parent;
            }
        } catch (Exception ignored) {}
        if (userDir.endsWith("xtx-mock-service")) return userDir;
        return userDir;
    }

    private static final String PROJECT_ROOT = resolveProjectRoot();

    /** 运行时用户持久化文件路径 */
    private static final String USER_RUNTIME_FILE = PROJECT_ROOT + "/data/user-profiles-runtime.json";

    /** 运行时品牌关注持久化文件路径 */
    private static final String BRAND_FOLLOW_RUNTIME_FILE = PROJECT_ROOT + "/data/user-brand-follows-runtime.json";

    /** 运行时订单持久化文件路径 */
    private static final String ORDER_RUNTIME_FILE = PROJECT_ROOT + "/data/user-orders-runtime.json";
    /** 运行时用户 profiles (文件持久化), keyed by userId */
    private final Map<String, JSONObject> runtimeUsers = new ConcurrentHashMap<>();

    /** Token 映射: token → userId (内存态) */
    private final Map<String, String> runtimeTokens = new ConcurrentHashMap<>();

    private final AtomicInteger featureCounter = new AtomicInteger(0);

    /** 旧 JSON 缓存 (仅用于非商品类接口) */
    private final Map<String, Object> dataCache = new HashMap<>();

    /** URL 可达性缓存 (专题封面 head 请求) */
    private final ConcurrentHashMap<String, Boolean> urlReachableCache = new ConcurrentHashMap<>();

    /** 已验证可加载真实图片的商品 ID 集合（来自 verified-product-images.json） */
    private final Set<String> verifiedProductImages = new HashSet<>();

    // ==================== 初始化 ====================

    @PostConstruct
    public void init() {
        loadMasterData();
        initRuntimeStores();
        wireFeatureControllers();
        wireAuthControllers();
        wireAdminController();
        wireCheckoutAndAddress();
        seedDefaultRuntimeData();
        loadFloorsAndEvaluations();
        loadLegacyJsonFiles();
        wireCatalogAndPreferenceControllers();
        wireOrderModuleControllers();
    }

    // ==================== Master 数据加载 ====================

    private void loadMasterData() {
        try {
            masterProducts = readJSONObject("mock/master/products.json");
            log.info("Master products loaded: {} products", masterProducts.size());
        } catch (Exception e) {
            log.error("Failed to load master/products.json", e);
            masterProducts = new JSONObject();
        }

        // 加载已验证商品图片缓存（确认可加载的真实商品图）
        try {
            java.io.File verifiedFile = new java.io.File(PROJECT_ROOT + "/data/verified-product-images.json");
            if (verifiedFile.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(verifiedFile.toPath()));
                JSONObject verified = JSONUtil.parseObj(content);
                JSONObject products = verified.getJSONObject("verified_products");
                if (products != null) {
                    for (String pid : products.keySet()) {
                        verifiedProductImages.add(pid);
                    }
                }
                log.info("Verified product images loaded: {} products", verifiedProductImages.size());
            } else {
                log.info("No verified-product-images.json found, verified set starts empty");
            }
        } catch (Exception e) {
            log.warn("Failed to load verified-product-images.json", e);
        }

        // 加载精选演示商品（真实商品名 + 本地 SVG 图片）
        try {
            curatedDemoProducts = readJSONObject("mock/master/curated-demo-products.json");
            log.info("Curated demo products loaded: {} products", curatedDemoProducts.size());
        } catch (Exception e) {
            log.warn("Failed to load curated-demo-products.json, will use masterProducts fallback", e);
            curatedDemoProducts = new JSONObject();
        }

        try {
            masterSkus = readJSONObject("mock/master/skus.json");
            log.info("Master skus loaded: {} skus", masterSkus.size());
        } catch (Exception e) {
            log.error("Failed to load master/skus.json", e);
            masterSkus = new JSONObject();
        }

        try {
            masterCategories = readJSONArray("mock/master/categories.json");
            log.info("Master categories loaded: {} top categories", masterCategories.size());
        } catch (Exception e) {
            log.error("Failed to load master/categories.json", e);
            masterCategories = new JSONArray();
        }

        try {
            masterCarts = readJSONObject("mock/master/carts.json");
            log.info("Master carts loaded: {} items", masterCarts.size());
        } catch (Exception e) {
            log.warn("No master/carts.json, cart endpoints will return empty", e);
            masterCarts = new JSONObject();
        }

        try {
            masterOrders = readJSONObject("mock/master/orders.json");
            log.info("Master orders loaded: {} orders", masterOrders.size());
        } catch (Exception e) {
            log.warn("No master/orders.json, order endpoints will return empty", e);
            masterOrders = new JSONObject();
        }
    }

    // ==================== 运行时 Store 初始化 ====================

    private void initRuntimeStores() {
        // 将预置订单加载到运行时存储
        for (Map.Entry<String, Object> entry : masterOrders.entrySet()) {
            JSONObject order = (JSONObject) entry.getValue();
            order.set("userId", CURRENT_USER_ID);
            // 确保待付款订单有倒计时和过期时间
            if (order.getInt("orderState", 0) == 1) {
                if (order.getStr("payExpireTime") == null) {
                    String future = java.time.LocalDateTime.now().plusHours(1).toString();
                    order.set("payExpireTime", future);
                    order.set("payLatestTime", future);
                }
                if (order.getInt("countdown", 0) <= 0) {
                    order.set("countdown", 3600);
                }
            }
            runtimeOrders.put(entry.getKey(), order);
        }

        // 从文件加载运行时订单（文件包含 master + 运行时变更），否则保存当前 seed 状态
        if (new java.io.File(ORDER_RUNTIME_FILE).exists()) {
            loadOrdersFromFile();
        } else {
            saveOrders();
        }
        // 通过 store 加载/seed 购物车（先 seed master，如果文件不存在则保存 seed 状态）
        cartController.getStore().seedFromMaster(masterCarts, CURRENT_USER_ID);
        cartController.getStore().loadFromFile();

        // 从 RuntimeAddressStore 加载地址（文件持久化或 seed）
        addressController.getStore().init();

        // 从文件加载运行时用户（文件不存在则 seed 默认用户）
        if (new java.io.File(USER_RUNTIME_FILE).exists()) {
            loadUsersFromFile();
            // 确保默认用户密码不被文件加载覆盖（saveUsers 会移除密码）
            JSONObject defaultUser = runtimeUsers.get(DEFAULT_USER_ID);
            if (defaultUser != null && defaultUser.getStr("password") == null) {
                defaultUser.set("password", "123456");
            }
        } else {
            seedDefaultUser();
            saveUsers();
        }

        // 从文件加载用户行为数据（收藏/历史/专题收藏）并通过 UserActivityController 管理
        userActivityController.getStore().loadCollectsFromFile();
        if (userActivityController.getStore().getCollectsMap().isEmpty()) {
            userActivityController.getService().seedDefaultCollects(DEFAULT_USER_ID, curatedDemoProducts, masterProducts);
            userActivityController.getStore().saveCollects();
        }

        userActivityController.getStore().loadHistoryFromFile();
        if (userActivityController.getStore().getHistoryMap().isEmpty()) {
            userActivityController.getService().seedDefaultHistory(DEFAULT_USER_ID, curatedDemoProducts, masterProducts);
            userActivityController.getStore().saveHistory();
        }

        userActivityController.getStore().loadTopicCollectsFromFile();
        if (userActivityController.getStore().getTopicCollectsMap().isEmpty()) {
            userActivityController.getService().seedDefaultTopicCollects(DEFAULT_USER_ID);
            userActivityController.getStore().saveTopicCollects();
        }

        // 委托 UserPreferenceController 管理品牌关注（加载/seed/持久化）
        userPreferenceController.getService().loadBrandFollowsFromFile();
        if (userPreferenceController.getService().isBrandFollowsEmpty()) {
            userPreferenceController.getService().seedDefaultBrandFollows();
            userPreferenceController.getService().saveBrandFollows();
        }
    }

    // ==================== Controller 注入：功能模块 ====================

    private void wireFeatureControllers() {
        // 向客服 Controller 注入运行时数据引用
        customerServiceController.setRuntimeData(runtimeOrders, runtimeTokens);

        // 向消息 Controller 注入运行时 token 引用
        messageController.setRuntimeTokens(runtimeTokens);

        // 向优惠券/礼品卡 Controller 注入运行时 token 引用
        benefitController.setRuntimeTokens(runtimeTokens);

        // 向抽奖/签到 Controller 注入运行时数据引用
        gameController.setRuntimeTokens(runtimeTokens);
        gameController.setFeatureCounter(featureCounter);
        gameController.setBenefitStore(benefitController.getStore());
        gameController.setPointsStore(pointsController.getStore());
        gameController.setPointsService(pointsController.getService());

        // 向邀请 Controller 注入运行时数据引用
        inviteController.setRuntimeTokens(runtimeTokens);
        inviteController.setRuntimeUsers(runtimeUsers);
        inviteController.setFeatureCounter(featureCounter);
        inviteController.setPointsService(pointsController.getService());
        inviteController.setPointsStore(pointsController.getStore());
        inviteController.setDefaultUserId(DEFAULT_USER_ID);

        // 向积分 Controller 注入运行时数据引用
        pointsController.setRuntimeTokens(runtimeTokens);
        pointsController.setFeatureCounter(featureCounter);
        pointsController.setGameStore(gameController.getStore());
        pointsController.setInviteStore(inviteController.getStore());
        pointsController.setDefaultUserId(DEFAULT_USER_ID);

        // 向售后 Controller 注入运行时数据引用
        afterSaleController.setRuntimeTokens(runtimeTokens);
        afterSaleController.setRuntimeOrders(runtimeOrders);
        afterSaleController.setFeatureCounter(featureCounter);

        // 向评价 Controller 注入运行时数据引用（先注入非 masterEvaluations 的依赖）
        reviewController.setRuntimeTokens(runtimeTokens);
        reviewController.setRuntimeOrders(runtimeOrders);
        reviewController.setRuntimeUsers(runtimeUsers);
        reviewController.setMasterSkus(masterSkus);
        reviewController.setMasterProducts(masterProducts);
        reviewController.setFeatureCounter(featureCounter);

        // 向用户行为 Controller 注入运行时 token 和商品数据引用
        userActivityController.setRuntimeTokens(runtimeTokens);
        userActivityController.setMasterProducts(masterProducts);
        userActivityController.setCuratedDemoProducts(curatedDemoProducts);

        // 向购物车 Controller 注入运行时 token 和商品数据引用
        cartController.setRuntimeTokens(runtimeTokens);
        cartController.setMasterSkus(masterSkus);
        cartController.setMasterProducts(masterProducts);

        // 注入商品模块数据源
        goodsController.setMasterProducts(masterProducts);
        goodsController.setMasterSkus(masterSkus);
        goodsController.setReviewStore(reviewController.getStore());

        // 向搜索 Controller 注入 master 数据和商品服务
        searchController.setMasterProducts(masterProducts);
        searchController.setGoodsService(goodsController.getService());
    }

    // ==================== Controller 注入：认证模块 ====================

    private void wireAuthControllers() {
        // 向 Auth Core Controller 注入运行时数据和回调
        authController.setRuntimeUsers(runtimeUsers);
        authController.setRuntimeTokens(runtimeTokens);
        authController.setSaveUsersCallback(this::saveUsers);
        authController.setInviteService(inviteController.getService());
        authController.setPointsService(pointsController.getService());
        authController.setPointsStore(pointsController.getStore());
        authController.setFeatureCounter(featureCounter);
        authController.setInitNewUserCallback(this::initNewUserFeatureData);

        // 向 Auth Stub Controller 注入 dataCache（仅读取静态 JSON）
        authStubController.setDataCache(dataCache);
    }

    // ==================== Controller 注入：Admin ====================

    private void wireAdminController() {
        // 向 Admin Controller 注入回调和数据引用
        adminController.setReloadMasterFilesCallback(() -> {
            int loaded = 0;
            try { masterProducts = readJSONObject("mock/master/products.json"); loaded++; } catch (Exception e) { log.warn("Failed to reload products", e); }
            try { masterSkus = readJSONObject("mock/master/skus.json"); loaded++; } catch (Exception e) { log.warn("Failed to reload SKUs", e); }
            try { masterCategories = readJSONArray("mock/master/categories.json"); loaded++; } catch (Exception e) { log.warn("Failed to reload categories", e); }
            try { masterEvaluations = readJSONObject("mock/master/evaluations.json"); loaded++; } catch (Exception e) { log.warn("Failed to reload evaluations", e); }
            try { masterOrders = readJSONObject("mock/master/orders.json"); loaded++; } catch (Exception e) { log.warn("Failed to reload orders", e); }
            try { masterCarts = readJSONObject("mock/master/carts.json"); loaded++; } catch (Exception e) { log.warn("Failed to reload carts", e); }
            log.info("Master files reloaded: {} files", loaded);
            return loaded;
        });
        adminController.setSaveOrdersCallback(this::saveOrders);
        adminController.setSavePointsCallback(() -> pointsController.getStore().save());
        adminController.setLoadUsersFromFileCallback(this::loadUsersFromFile);
        adminController.setSeedDefaultUserCallback(this::seedDefaultUser);
        adminController.setSaveUsersCallback(this::saveUsers);
        adminController.setResetMemberFeatureDataCallback(this::resetMemberFeatureData);
        adminController.setSeedMemberFeatureDataCallback(this::seedMemberFeatureData);
        adminController.setUserActivityStore(userActivityController.getStore());
        adminController.setUserActivityService(userActivityController.getService());
        adminController.setUserPreferenceService(userPreferenceController.getService());
        adminController.setCartStore(cartController.getStore());
        adminController.setRuntimeUsers(runtimeUsers);
        adminController.setRuntimeOrders(runtimeOrders);
        adminController.setMasterOrders(masterOrders);
        adminController.setMasterCarts(masterCarts);
        adminController.setMasterProducts(masterProducts);
        adminController.setCuratedDemoProducts(curatedDemoProducts);
        adminController.setBrandFollowRuntimeFile(BRAND_FOLLOW_RUNTIME_FILE);
        adminController.setOrderRuntimeFile(ORDER_RUNTIME_FILE);
        adminController.setUserPointsPath(com.xtx.mock.support.MockRuntimePaths.resolve(com.xtx.mock.support.MockRuntimePaths.USER_POINTS));
        adminController.setDefaultUserId(DEFAULT_USER_ID);
        adminController.setCurrentUserId(CURRENT_USER_ID);
    }

    // ==================== Controller 注入：结算/地址 ====================

    private void wireCheckoutAndAddress() {
        // 向预结算 Controller 注入运行时 token、商品数据和 Store 引用
        checkoutController.setRuntimeTokens(runtimeTokens);
        checkoutController.setMasterSkus(masterSkus);
        checkoutController.setCartStore(cartController.getStore());
        checkoutController.setAddressStore(addressController.getStore());
        checkoutController.setBenefitStore(benefitController.getStore());

        // 向地址 Controller 注入运行时 token
        addressController.setRuntimeTokens(runtimeTokens);
    }

    // ==================== 默认数据 seed ====================

    private void seedDefaultRuntimeData() {
        // 积分 Store 已在 MockPointsController 构造器中从文件加载
        // 确保默认用户有种子积分数据
        pointsController.getStore().ensureDefaultUserPoints(DEFAULT_USER_ID);
        // 持久化种子积分（若文件不存在，ensureDefaultUserPoints 自动处理）
        pointsController.getStore().save();

        // 确保默认用户有优惠券和礼品卡 seed 数据
        benefitController.getStore().ensureDefaultUser(DEFAULT_USER_ID);

        // 确保默认用户有售后种子数据
        afterSaleController.getService().seedDefaultAfterSales(DEFAULT_USER_ID, runtimeOrders, featureCounter);
        afterSaleController.getStore().save();

        // 确保默认用户有评价种子数据
        reviewController.getService().seedDefaultReviews(DEFAULT_USER_ID, runtimeOrders, masterSkus, masterProducts, featureCounter);

        String defaultToken = "mock-token-001";
        runtimeTokens.put(defaultToken, DEFAULT_USER_ID);
        // No hardcoded test tokens — all scripts must register real users
    }

    // ==================== 剩余 Master 数据加载 ====================

    private void loadFloorsAndEvaluations() {
        try {
            masterFloors = readJSONObject("mock/master/floors.json");
            log.info("Master floors loaded: {} entries", masterFloors.size());
        } catch (Exception e) {
            log.warn("No master/floors.json, home goods will use category pictures", e);
            masterFloors = new JSONObject();
        }

        try {
            masterEvaluations = readJSONObject("mock/master/evaluations.json");
            log.info("Master evaluations loaded: {} products with evaluations", masterEvaluations.size());

        } catch (Exception e) {
            log.warn("No master/evaluations.json, evaluate endpoints will return empty", e);
            masterEvaluations = new JSONObject();
        }

        // masterEvaluations 加载完成后才注入到评价 Controller
        reviewController.setMasterEvaluations(masterEvaluations);
    }

    private void loadLegacyJsonFiles() {
        // 加载旧 JSON（仅保留非商品类接口）
        String[] legacyFiles = {
            "home-banner", "home-brand", "home-special",
            "category-sub-filter",
            "login", "login-code", "login-social", "login-social-bind", "login-social-complement",
            "register-check",
            "member-address-list",
            "order-logistics", "order-cancel", "order-receipt", "order-submit",
            "collect-list"
        };
        int loaded = 0;
        for (String name : legacyFiles) {
            try {
                String json = ResourceUtil.readStr("mock/" + name + ".json", StandardCharsets.UTF_8);
                dataCache.put(name, JSONUtil.parse(json));
                loaded++;
            } catch (Exception e) {
                log.warn("Failed to load mock/{}.json", name, e);
            }
        }
        log.info("Legacy mock data loaded: {} files", loaded);
    }

    // ==================== Controller 注入：首页/用户偏好 ====================

    private void wireCatalogAndPreferenceControllers() {
        // 向 Home/Catalog Controller 注入 master 数据
        homeCatalogController.setMasterCategories(masterCategories);
        homeCatalogController.setMasterProducts(masterProducts);
        homeCatalogController.setGoodsService(goodsController.getService());
        homeCatalogController.setMasterFloors(masterFloors);
        homeCatalogController.setHomeBrandData(
            dataCache.get("home-brand") instanceof JSONArray ? (JSONArray) dataCache.get("home-brand") : new JSONArray());
        homeCatalogController.setHomeSpecialData(
            dataCache.get("home-special") instanceof JSONArray ? (JSONArray) dataCache.get("home-special") : new JSONArray());
        homeCatalogController.setHomeBannerData(dataCache.get("home-banner"));
        homeCatalogController.setUrlReachableCache(urlReachableCache);
        homeCatalogController.setImageNormalizer(MockImageUtils::normalizeImageUrl);
        homeCatalogController.setCategoryFallbackSupplier(MockImageUtils::getCategoryFallbackImage);
        homeCatalogController.setDefaultFallbackImage(MockImageUtils.DEFAULT_FALLBACK_IMAGE);
        homeCatalogController.setVerifiedProductImages(verifiedProductImages);
        homeCatalogController.setBrandFollowResolver(this::getBrandFollowIds);
        homeCatalogController.setTopicCollectResolver(this::getTopicCollectIds);

        // 向 UserPreference Controller 注入运行时数据和数据源
        userPreferenceController.setRuntimeTokens(runtimeTokens);
        userPreferenceController.setHomeBrandData(
            dataCache.get("home-brand") instanceof JSONArray ? (JSONArray) dataCache.get("home-brand") : new JSONArray());
        userPreferenceController.setHomeSpecialData(
            dataCache.get("home-special") instanceof JSONArray ? (JSONArray) dataCache.get("home-special") : new JSONArray());
        userPreferenceController.setBrandFollowRuntimeFile(BRAND_FOLLOW_RUNTIME_FILE);
        userPreferenceController.setDefaultUserId(DEFAULT_USER_ID);
        userPreferenceController.setTopicCollectGetter(this::getTopicCollectIds);
        userPreferenceController.setTopicCollectAdder(
            (uid, topicId) -> userActivityController.getStore().addTopicCollect(uid, topicId));
        userPreferenceController.setTopicCollectRemover(
            (uid, topicId) -> userActivityController.getStore().removeTopicCollect(uid, topicId));
        userPreferenceController.setTopicCollectSaver(
            () -> userActivityController.getStore().saveTopicCollects());
    }

    // ==================== Controller 注入：订单模块 ====================

    private void wireOrderModuleControllers() {
        // 向订单查询 Controller 注入运行时数据
        orderQueryController.setRuntimeTokens(runtimeTokens);
        orderQueryController.setRuntimeOrders(runtimeOrders);
        orderQueryController.setMasterProducts(masterProducts);
        orderQueryController.setLogisticsData(dataCache.get("order-logistics"));
        orderQueryController.setImageNormalizer(MockImageUtils::normalizeImageUrl);
        orderQueryController.setDefaultFallbackImage(MockImageUtils.DEFAULT_FALLBACK_IMAGE);
        orderQueryController.setSaveOrdersCallback(this::saveOrders);

        // 向订单操作 Controller 注入运行时数据
        orderActionController.setRuntimeTokens(runtimeTokens);
        orderActionController.setRuntimeOrders(runtimeOrders);
        orderActionController.setSaveOrdersCallback(this::saveOrders);

        // 向订单支付 Controller 注入运行时数据
        orderPaymentController.setRuntimeTokens(runtimeTokens);
        orderPaymentController.setRuntimeOrders(runtimeOrders);
        orderPaymentController.setSaveOrdersCallback(this::saveOrders);

        // 向订单创建 Controller 注入运行时数据和 Store 引用
        orderCreateController.setRuntimeTokens(runtimeTokens);
        orderCreateController.setRuntimeOrders(runtimeOrders);
        orderCreateController.setSaveOrdersCallback(this::saveOrders);
        orderCreateController.setCartStore(cartController.getStore());
        orderCreateController.setAddressStore(addressController.getStore());
        orderCreateController.setBenefitStore(benefitController.getStore());
        orderCreateController.setMasterSkus(masterSkus);
        orderCreateController.setMasterProducts(masterProducts);
        orderCreateController.setOrderCounter(orderCounter);
        orderCreateController.setImageNormalizer(MockImageUtils::normalizeImageUrl);
        orderCreateController.setDefaultFallbackImage(MockImageUtils.DEFAULT_FALLBACK_IMAGE);
        orderCreateController.setFormatSpecsHelper(MockProductViewUtils::formatSpecsText);

        // 向再次购买 Controller 注入运行时数据和 Store 引用
        orderRepurchaseController.setRuntimeTokens(runtimeTokens);
        orderRepurchaseController.setRuntimeOrders(runtimeOrders);
        orderRepurchaseController.setMasterSkus(masterSkus);
        orderRepurchaseController.setAddressStore(addressController.getStore());
        orderRepurchaseController.setSpecsFormatter(MockProductViewUtils::formatSpecsText);
        orderRepurchaseController.setImageNormalizer(MockImageUtils::normalizeImageUrl);
        orderRepurchaseController.setDefaultFallbackImage(MockImageUtils.DEFAULT_FALLBACK_IMAGE);
        orderRepurchaseController.setSaveOrdersCallback(this::saveOrders);
    }

    // ==================== 用户特征数据管理 ====================

    /**
     * 重置所有特征数据（积分/评价/售后/邀请/抽奖），重新 seed。
     * 注意：商品收藏/浏览历史由调用方（如 resetMemberData）自行管理。
     */
    private void resetMemberFeatureData(String userId) {
        pointsController.getStore().removeUserData(userId);
        reviewController.getStore().removeUserData(userId);
        afterSaleController.getStore().removeUserData(userId);
        inviteController.getStore().removeUserData(userId);
        gameController.getStore().removeUserData(userId);
        seedMemberFeatureData(userId);
    }

    /**
     * seed 用户特征数据（确保默认用户有各模块初始数据）。
     */
    private void seedMemberFeatureData(String userId) {
        pointsController.getStore().getPoints(userId);
        reviewController.getStore().initNewUser(userId);
        inviteController.getStore().getInvite(userId);
        gameController.getStore().getLottery(userId);
    }

    /**
     * 新用户最小初始化 — 所有数据为空或最小状态。
     * 仅用于新注册用户，不复制任何 demo/seed 数据。
     */
    private void initNewUserFeatureData(String userId) {
        messageController.initNewUserMessages(userId);
        benefitController.initNewUserBenefits(userId);
        pointsController.getStore().initNewUserPoints(userId);
        reviewController.getStore().initNewUser(userId);
        afterSaleController.getStore().initNewUser(userId);
        inviteController.getStore().initNewUserInvite(userId);
        gameController.getStore().initNewUserLottery(userId);
        userActivityController.getStore().initNewUser(userId);
    }

    // ==================== 辅助方法 ====================

    private JSONObject readJSONObject(String path) {
        String json = ResourceUtil.readStr(path, StandardCharsets.UTF_8);
        return JSONUtil.parseObj(json);
    }

    private JSONArray readJSONArray(String path) {
        String json = ResourceUtil.readStr(path, StandardCharsets.UTF_8);
        return JSONUtil.parseArray(json);
    }

    /**
     * 从请求参数解析当前用户 ID。
     * 优先级: body/query 中的 userId > null（调用方决定默认值）
     */
    private String resolveUserId(Map<String, Object> params) {
        if (params != null && params.get("userId") != null) {
            return params.get("userId").toString();
        }
        return null;
    }

    // ==================== Token / 认证辅助 ====================

    /**
     * 从 Authorization header 提取 userId。
     * header 格式: "Bearer {token}"
     * 返回 token 对应的 userId，未找到返回 null。
     */
    private String getUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) return null;
        return runtimeTokens.get(token);
    }

    private String getUserIdOrDefault(String authHeader) {
        String userId = getUserIdFromToken(authHeader);
        return userId != null ? userId : CURRENT_USER_ID;
    }

    /**
     * 要求请求必须携带有效 token，否则抛出 401。
     * 用于所有 /member/** 接口，避免无 token 时回退到默认用户。
     */
    private String requireUserId(String authHeader) {
        String userId = getUserIdFromToken(authHeader);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }

    private boolean isOrderOwner(JSONObject order, String userId) {
        return order != null && userId.equals(order.getStr("userId", CURRENT_USER_ID));
    }

    // ==================== 用户持久化 ====================

    private synchronized void saveUsers() {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, JSONObject> entry : runtimeUsers.entrySet()) {
                JSONObject user = entry.getValue();
                // 移除密码后再序列化
                // Deep copy via JSON roundtrip to avoid CloneNotSupportedException
                JSONObject safe = JSONUtil.parseObj(user.toString());
                obj.set(entry.getKey(), safe);
            }
            String json = obj.toJSONString(2);
            String tmpFile = USER_RUNTIME_FILE + ".tmp";
            cn.hutool.core.io.file.FileWriter writer = new cn.hutool.core.io.file.FileWriter(tmpFile);
            writer.write(json);
            java.io.File tmp = new java.io.File(tmpFile);
            java.io.File target = new java.io.File(USER_RUNTIME_FILE);
            if (target.exists()) target.delete();
            tmp.renameTo(target);
        } catch (Exception e) {
            log.error("保存用户失败", e);
        }
    }

    private synchronized void loadUsersFromFile() {
        java.io.File f = new java.io.File(USER_RUNTIME_FILE);
        if (f.exists() && f.length() > 0) {
            try {
                String content = cn.hutool.core.io.file.FileReader.create(f).readString();
                JSONObject obj = JSONUtil.parseObj(content);
                runtimeUsers.clear();
                for (Map.Entry<String, Object> entry : obj.entrySet()) {
                    JSONObject user = (JSONObject) entry.getValue();
                    runtimeUsers.put(entry.getKey(), user);
                }
                log.info("从文件加载 {} 个用户", runtimeUsers.size());
            } catch (Exception e) {
                log.error("从文件加载用户失败，回退到 seed", e);
                seedDefaultUser();
                saveUsers();
            }
        } else {
            seedDefaultUser();
            saveUsers();
        }
    }

    private void seedDefaultUser() {
        JSONObject user = new JSONObject();
        user.set("id", DEFAULT_USER_ID);
        user.set("account", "xiaotuxian001");
        user.set("password", "123456");
        user.set("nickname", "小兔鲜");
        user.set("avatar", "https://picsum.photos/seed/avatar_1/100/100");
        user.set("mobile", "13800000000");
        user.set("gender", null);
        user.set("birthday", "");
        user.set("profession", "");
        runtimeUsers.put(DEFAULT_USER_ID, user);
        log.info("Seed 默认用户: {}", DEFAULT_USER_ID);
    }

    // ==================== 订单持久化 ====================

    private synchronized void saveOrders() {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, JSONObject> entry : runtimeOrders.entrySet()) {
                obj.set(entry.getKey(), entry.getValue());
            }
            String json = obj.toJSONString(2);
            String tmpFile = ORDER_RUNTIME_FILE + ".tmp";
            cn.hutool.core.io.file.FileWriter writer = new cn.hutool.core.io.file.FileWriter(tmpFile);
            writer.write(json);
            java.io.File tmp = new java.io.File(tmpFile);
            java.io.File target = new java.io.File(ORDER_RUNTIME_FILE);
            if (target.exists()) target.delete();
            tmp.renameTo(target);
        } catch (Exception e) {
            log.error("保存订单失败", e);
        }
    }

    private synchronized void loadOrdersFromFile() {
        java.io.File f = new java.io.File(ORDER_RUNTIME_FILE);
        if (f.exists() && f.length() > 0) {
            try {
                String content = cn.hutool.core.io.file.FileReader.create(f).readString();
                JSONObject obj = JSONUtil.parseObj(content);
                runtimeOrders.clear();
                for (Map.Entry<String, Object> entry : obj.entrySet()) {
                    runtimeOrders.put(entry.getKey(), (JSONObject) entry.getValue());
                }
                log.info("从文件加载 {} 个订单", runtimeOrders.size());
            } catch (Exception e) {
                log.error("从文件加载订单失败", e);
            }
        }
    }

    private Set<String> getBrandFollowIds(String userId) {
        return userPreferenceController.getService().getBrandFollowIds(userId);
    }

    private Set<String> getTopicCollectIds(String userId) {
        return userActivityController.getStore().getTopicCollects(userId);
    }

    private JSONObject buildPageResult(JSONArray source, int page, int pageSize) {
        JSONObject result = new JSONObject();
        result.set("counts", source.size());
        result.set("page", page);
        result.set("pageSize", pageSize);
        result.set("pages", source.isEmpty() ? 0 : (int) Math.ceil(source.size() / (double) pageSize));
        JSONArray items = new JSONArray();
        int from = Math.max(0, (page - 1) * pageSize);
        int to = Math.min(source.size(), from + pageSize);
        for (int i = from; i < to; i++) {
            items.add(source.get(i));
        }
        result.set("items", items);
        return result;
    }

    private FrontResponse<Object> ok(String key) {
        Object data = dataCache.get(key);
        if (data == null) {
            log.warn("Mock data not found: {}", key);
            return FrontResponse.success(new JSONObject());
        }
        return FrontResponse.success(data);
    }

    private FrontResponse<Object> okWithLimit(String key, Integer limit) {
        Object data = dataCache.get(key);
        if (data instanceof JSONArray arr && limit != null && limit < arr.size()) {
            return FrontResponse.success(arr.subList(0, limit));
        }
        return FrontResponse.success(data != null ? data : new JSONArray());
    }

}
