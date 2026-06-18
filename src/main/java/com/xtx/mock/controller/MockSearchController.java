package com.xtx.mock.controller;

import cn.hutool.json.JSONObject;
import com.xtx.common.core.result.FrontResponse;
import com.xtx.mock.service.goods.GoodsMockService;
import com.xtx.mock.service.search.SearchMockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MockSearchController — 搜索模块控制器。
 * <p>
 * 职责：GET /search 搜索商品接口。
 * <p>
 * 数据源由 {@link com.xtx.mock.controller.MockController} 在 {@code @PostConstruct} 中通过 setter 注入。
 * 商品卡片组装通过 {@link #getService()} 引用 {@link GoodsMockService}。
 */
@Slf4j
@RestController
public class MockSearchController {

    private final SearchMockService searchService;

    public MockSearchController() {
        this.searchService = new SearchMockService();
        log.info("MockSearchController initialized");
    }

    /** 返回底层 Service 引用，供其他模块只读调用。 */
    public SearchMockService getService() {
        return searchService;
    }

    // ==================== 注入 ====================

    public void setMasterProducts(JSONObject v) { searchService.setMasterProducts(v); }
    public void setGoodsService(GoodsMockService v) { searchService.setGoodsService(v); }

    // ========================================================================
    //  GET /search
    // ========================================================================

    @GetMapping("/search")
    public FrontResponse<Object> search(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false, defaultValue = "1") int page,
                                         @RequestParam(required = false, defaultValue = "20") int pageSize) {
        return FrontResponse.success(searchService.search(keyword, page, pageSize));
    }
}
