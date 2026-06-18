package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.common.core.result.FrontResponse;
import com.xtx.mock.service.goods.GoodsMockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * MockGoodsController — 商品模块控制器。
 * <p>
 * 职责：商品详情、SKU/库存、相关/相似/热销。
 * <p>
 * 所有数据来自 masterProducts / masterSkus（只读），不写 runtime。
 * 运行时数据依赖由 {@link com.xtx.mock.controller.MockController} 在 {@code @PostConstruct} 中通过 setter 注入。
 */
@Slf4j
@RestController
public class MockGoodsController {

    private final GoodsMockService goodsService;

    public MockGoodsController() {
        this.goodsService = new GoodsMockService();
        log.info("MockGoodsController initialized");
    }

    /** 返回底层 Service 引用，供其他模块只读调用商品逻辑。 */
    public GoodsMockService getService() {
        return goodsService;
    }

    // ==================== 注入 ====================

    public void setMasterProducts(JSONObject m) { goodsService.setMasterProducts(m); }
    public void setMasterSkus(JSONObject m) { goodsService.setMasterSkus(m); }
    public void setReviewStore(Object reviewStore) {
        if (reviewStore instanceof com.xtx.mock.store.RuntimeReviewStore) {
            goodsService.setReviewStore((com.xtx.mock.store.RuntimeReviewStore) reviewStore);
        }
    }

    // ========================================================================
    //  GET /goods — 商品详情
    // ========================================================================

    /**
     * GET /goods?id={id}
     * 从 products.json + skus.json 运行时组装完整详情。
     */
    @GetMapping("/goods")
    public FrontResponse<Object> goodsDetail(@RequestParam String id) {
        try {
            JSONObject result = goodsService.buildGoodsDetail(id);
            return FrontResponse.success(result);
        } catch (NoSuchElementException e) {
            return FrontResponse.failure(40000, e.getMessage());
        }
    }

    // ========================================================================
    //  GET /goods/relevant — 相关推荐
    // ========================================================================

    /**
     * GET /goods/relevant?id={id}&limit={limit}
     * 有 id 时从同分类商品中取（排除自身），无 id 时返回全站热销推荐。
     */
    @GetMapping("/goods/relevant")
    public FrontResponse<Object> goodsRelevant(@RequestParam(required = false) String id,
                                                @RequestParam(required = false) Integer limit) {
        return FrontResponse.success(goodsService.queryRelevant(id, limit));
    }

    // ========================================================================
    //  GET /goods/similar/{goodsId} — 找相似
    // ========================================================================

    /**
     * GET /goods/similar/{goodsId}
     * 找相似 — 返回同分类下最多 8 个商品（排除自身）。
     */
    @GetMapping("/goods/similar/{goodsId}")
    public FrontResponse<Object> goodsSimilar(@PathVariable String goodsId) {
        return FrontResponse.success(goodsService.querySimilar(goodsId));
    }

    // ========================================================================
    //  GET /goods/hot — 热销排行
    // ========================================================================

    /**
     * GET /goods/hot?id={id}&type={type}&limit={limit}
     * 按 type 排序取热销商品。
     */
    @GetMapping("/goods/hot")
    public FrontResponse<Object> goodsHot(@RequestParam(required = false) String id,
                                           @RequestParam(required = false) Integer type,
                                           @RequestParam(required = false) Integer limit) {
        return FrontResponse.success(goodsService.queryHot(id, type, limit));
    }

    // ========================================================================
    //  GET /goods/sku/{skuId} — SKU 详情
    // ========================================================================

    /**
     * GET /goods/sku/{skuId}
     * 从 skus.json 查询 SKU 及规格树。
     */
    @GetMapping("/goods/sku/{skuId}")
    public FrontResponse<Object> goodsSku(@PathVariable String skuId) {
        try {
            return FrontResponse.success(goodsService.querySku(skuId));
        } catch (NoSuchElementException e) {
            return FrontResponse.failure(40000, e.getMessage());
        }
    }

    // ========================================================================
    //  GET /goods/stock/{skuId} — 库存与价格
    // ========================================================================

    /**
     * GET /goods/stock/{skuId}
     * 从 skus.json 查询库存和价格。
     */
    @GetMapping("/goods/stock/{skuId}")
    public FrontResponse<Object> goodsStock(@PathVariable String skuId) {
        try {
            return FrontResponse.success(goodsService.queryStock(skuId));
        } catch (NoSuchElementException e) {
            return FrontResponse.failure(40000, e.getMessage());
        }
    }
}
