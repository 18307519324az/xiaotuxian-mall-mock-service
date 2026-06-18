package com.xtx.mock.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.common.core.result.FrontResponse;
import com.xtx.mock.service.catalog.HomeCatalogMockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MockHomeCatalogController — 首页/分类/品牌/专题模块控制器。
 * <p>
 * 职责：Home、Catalog、Brand、Topic、Special 相关只读接口。
 * <p>
 * 数据源由 {@link com.xtx.mock.controller.MockController} 在 {@code @PostConstruct} 中通过 setter 注入。
 * 商品卡片组装通过 {@link #getService()} 引用 {@link com.xtx.mock.service.goods.GoodsMockService}。
 */
@Slf4j
@RestController
public class MockHomeCatalogController {

    private final HomeCatalogMockService catalogService;

    public MockHomeCatalogController() {
        this.catalogService = new HomeCatalogMockService();
        log.info("MockHomeCatalogController initialized");
    }

    /** 返回底层 Service 引用，供其他模块只读调用。 */
    public HomeCatalogMockService getService() {
        return catalogService;
    }

    // ==================== 注入 ====================

    public void setMasterCategories(JSONArray v) { catalogService.setMasterCategories(v); }
    public void setMasterProducts(JSONObject v) { catalogService.setMasterProducts(v); }
    public void setGoodsService(Object goodsService) {
        if (goodsService instanceof com.xtx.mock.service.goods.GoodsMockService) {
            catalogService.setGoodsService((com.xtx.mock.service.goods.GoodsMockService) goodsService);
        }
    }
    public void setMasterFloors(JSONObject v) { catalogService.setMasterFloors(v); }
    public void setHomeBrandData(JSONArray v) { catalogService.setHomeBrandData(v); }
    public void setHomeSpecialData(JSONArray v) { catalogService.setHomeSpecialData(v); }
    public void setHomeBannerData(Object v) { catalogService.setHomeBannerData(v); }
    public void setUrlReachableCache(ConcurrentHashMap<String, Boolean> v) { catalogService.setUrlReachableCache(v); }
    public void setImageNormalizer(java.util.function.BiFunction<String, String, String> v) { catalogService.setImageNormalizer(v); }
    public void setCategoryFallbackSupplier(java.util.function.Function<String, String> v) { catalogService.setCategoryFallbackSupplier(v); }
    public void setDefaultFallbackImage(String v) { catalogService.setDefaultFallbackImage(v); }
    public void setVerifiedProductImages(Set<String> v) { catalogService.setVerifiedProductImages(v); }

    // ========================================================================
    //  Home 首页
    // ========================================================================

    @GetMapping("/home/category/head")
    public FrontResponse<Object> categoryHead() {
        return FrontResponse.success(catalogService.categoryHead());
    }

    @GetMapping("/home/brand")
    public FrontResponse<Object> homeBrand(@RequestParam(required = false) Integer limit) {
        return FrontResponse.success(catalogService.homeBrandList(limit));
    }

    @GetMapping("/brand")
    public FrontResponse<Object> brandList(@RequestParam(required = false) Integer limit) {
        return FrontResponse.success(catalogService.homeBrandList(limit));
    }

    @GetMapping("/home/banner")
    public FrontResponse<Object> homeBanner() {
        return FrontResponse.success(catalogService.homeBanner());
    }

    @GetMapping("/home/new")
    public FrontResponse<Object> homeNew() {
        return FrontResponse.success(catalogService.homeNew());
    }

    @GetMapping("/home/hot")
    public FrontResponse<Object> homeHot() {
        return FrontResponse.success(catalogService.homeHot());
    }

    @GetMapping("/home/goods")
    public FrontResponse<Object> homeGoods() {
        return FrontResponse.success(catalogService.homeGoods());
    }

    @GetMapping("/home/special")
    public FrontResponse<Object> homeSpecial() {
        return FrontResponse.success(catalogService.homeSpecial());
    }

    @GetMapping("/topic")
    public FrontResponse<Object> topicList() {
        return FrontResponse.success(catalogService.homeSpecial());
    }

    // ========================================================================
    //  Brand 品牌
    // ========================================================================

    @GetMapping("/brand/{id}")
    public FrontResponse<Object> brandDetail(@PathVariable String id,
                                              @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = resolveUserId(authHeader);
        JSONObject detail = catalogService.buildBrandDetail(id, getBrandFollowIds(uid));
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "品牌不存在: " + id);
        }
        return FrontResponse.success(detail);
    }

    @GetMapping("/brand/{id}/goods")
    public FrontResponse<Object> brandGoods(@PathVariable String id) {
        if (!catalogService.brandExists(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "品牌不存在: " + id);
        }
        return FrontResponse.success(catalogService.buildBrandGoods(id));
    }

    // ========================================================================
    //  Topic / Special 专题
    // ========================================================================

    @GetMapping("/special/{id}")
    public FrontResponse<Object> specialDetail(@PathVariable String id,
                                                @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String uid = resolveUserId(authHeader);
        JSONObject detail = catalogService.buildSpecialDetail(id, getTopicCollectIds(uid));
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "专题不存在: " + id);
        }
        return FrontResponse.success(detail);
    }

    @GetMapping("/topic/{id}")
    public FrontResponse<Object> topicDetail(@PathVariable String id,
                                              @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return specialDetail(id, authHeader);
    }

    @GetMapping("/topic/{id}/goods")
    public FrontResponse<Object> topicGoods(@PathVariable String id) {
        if (catalogService.findSpecialBase(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "专题不存在: " + id);
        }
        return FrontResponse.success(catalogService.buildSpecialGoods(id));
    }

    // ========================================================================
    //  Category 分类
    // ========================================================================

    @GetMapping("/category")
    public FrontResponse<Object> categoryTop(@RequestParam String id) {
        JSONObject result = catalogService.categoryTop(id);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分类不存在: " + id);
        }
        return FrontResponse.success(result);
    }

    @GetMapping("/category/sub/filter")
    public FrontResponse<Object> categorySubFilter(@RequestParam String id) {
        return FrontResponse.success(catalogService.categorySubFilter(id));
    }

    @PostMapping("/category/goods/temporary")
    public FrontResponse<Object> categoryGoods(@RequestBody Map<String, Object> params) {
        String categoryId = params.get("categoryId") != null ? String.valueOf(params.get("categoryId")) : "";
        int page = params.get("page") != null ? Integer.parseInt(String.valueOf(params.get("page"))) : 1;
        int pageSize = params.get("pageSize") != null ? Integer.parseInt(String.valueOf(params.get("pageSize"))) : 20;
        boolean inventoryOnly = Boolean.parseBoolean(String.valueOf(params.getOrDefault("inventory", false)));
        boolean discountOnly = Boolean.parseBoolean(String.valueOf(params.getOrDefault("onlyDiscount", false)));
        String sortField = params.get("sortField") != null ? String.valueOf(params.get("sortField")) : "sort";
        String sortMethod = params.get("sortMethod") != null ? String.valueOf(params.get("sortMethod")) : "desc";
        return FrontResponse.success(catalogService.categoryGoods(categoryId, page, pageSize, inventoryOnly, discountOnly, sortField, sortMethod));
    }

    // ==================== 工具方法（需在注入时由 MockController 覆盖） ====================

    /** 从 Authorization header 提取用户 ID，默认返回空字符串 */
    private String resolveUserId(String authHeader) {
        return authHeader != null && authHeader.startsWith("Bearer ")
            ? authHeader.substring(7)
            : "";
    }

    /** 获取用户已关注的品牌 ID 集合 — 由 MockController 在注入时覆盖 */
    private java.util.function.Function<String, Set<String>> brandFollowResolver = uid -> java.util.Collections.emptySet();
    public void setBrandFollowResolver(java.util.function.Function<String, Set<String>> r) { this.brandFollowResolver = r; }
    private Set<String> getBrandFollowIds(String uid) {
        return brandFollowResolver.apply(uid);
    }

    /** 获取用户已收藏的专题 ID 集合 — 由 MockController 在注入时覆盖 */
    private java.util.function.Function<String, Set<String>> topicCollectResolver = uid -> java.util.Collections.emptySet();
    public void setTopicCollectResolver(java.util.function.Function<String, Set<String>> r) { this.topicCollectResolver = r; }
    private Set<String> getTopicCollectIds(String uid) {
        return topicCollectResolver.apply(uid);
    }
}
