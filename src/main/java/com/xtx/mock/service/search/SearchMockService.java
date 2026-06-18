package com.xtx.mock.service.search;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.service.goods.GoodsMockService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SearchMockService — 搜索模块业务逻辑。
 * <p>
 * 职责：GET /search 关键词匹配、排序、分页。
 * <p>
 * 数据源通过 setter 注入（由 MockController 在 @PostConstruct 中配置）：
 * <ul>
 *   <li>masterProducts — 商品 SPU 主数据（只读 JSONObject）</li>
 *   <li>goodsService — GoodsMockService 引用（用于 toGoodsCard）</li>
 * </ul>
 */
@Slf4j
public class SearchMockService {

    // ==================== 注入的数据源 ====================

    private JSONObject masterProducts;
    private GoodsMockService goodsService;

    public void setMasterProducts(JSONObject m) { this.masterProducts = m; }
    public void setGoodsService(GoodsMockService gs) { this.goodsService = gs; }

    // ========================================================================
    //  GET /search
    // ========================================================================

    /**
     * 搜索商品 — 按 keyword 匹配商品名称/描述/品牌/类目。
     * 空 keyword 返回热门商品（按销量排序）。
     *
     * @param keyword  搜索关键词（可选，空则返回热门商品）
     * @param page     页码（从 1 开始，默认 1）
     * @param pageSize 每页条数（默认 20）
     * @return 分页结果 JSONObject: {items, counts, page, pageSize, pages}
     */
    public JSONObject search(String keyword, int page, int pageSize) {
        // 收集匹配结果并打分
        List<JSONObject> scored = new ArrayList<>();
        String kw = (keyword != null ? keyword.trim() : "");
        boolean hasKeyword = !kw.isEmpty();

        for (Map.Entry<String, Object> entry : masterProducts.entrySet()) {
            if (!(entry.getValue() instanceof JSONObject)) continue;
            JSONObject p = (JSONObject) entry.getValue();
            String name = p.getStr("name");
            String picture = p.getStr("picture");
            double price = 0;
            try { price = Double.parseDouble(p.getStr("price", "0")); } catch (Exception ignored) {}
            if (name == null || name.isBlank() || picture == null || picture.isBlank() || price <= 0) continue;

            int score = 0;
            if (hasKeyword) {
                String lowerKw = kw.toLowerCase();
                // 商品名匹配（最高优先级）
                if (name.toLowerCase().contains(lowerKw)) {
                    score += 10;
                    // 完全匹配加分
                    if (name.toLowerCase().equals(lowerKw)) score += 5;
                }
                // 描述/标签匹配
                String desc = p.getStr("desc", "");
                if (desc.toLowerCase().contains(lowerKw)) score += 3;
                String tag = p.getStr("tag", "");
                if (tag.toLowerCase().contains(lowerKw)) score += 2;
                // 品牌名匹配
                String brandName = p.getStr("brandName", "");
                if (brandName.toLowerCase().contains(lowerKw)) score += 2;
                // 类目名匹配
                JSONArray cats = p.getJSONArray("categories");
                if (cats != null) {
                    for (Object c : cats) {
                        if (c instanceof JSONObject) {
                            String catName = ((JSONObject) c).getStr("name", "");
                            if (catName.toLowerCase().contains(lowerKw)) {
                                score += 1;
                                break;
                            }
                        }
                    }
                }
                if (score == 0) continue; // 未匹配跳过
            } else {
                // 空 keyword：所有有效商品加入，按销量排序
                score = 1;
            }
            scored.add(p);
            p.set("_searchScore", score);
        }

        // 按得分降序，同分按销量降序
        scored.sort((a, b) -> {
            int sa = a.getInt("_searchScore", 0);
            int sb = b.getInt("_searchScore", 0);
            if (sb != sa) return Integer.compare(sb, sa);
            return Integer.compare(b.getInt("salesCount", 0), a.getInt("salesCount", 0));
        });

        // 清理临时得分字段
        for (JSONObject p : scored) p.remove("_searchScore");

        // 分页
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 20;
        int total = scored.size();
        int from = (page - 1) * pageSize;
        int to = Math.min(from + pageSize, total);
        List<JSONObject> pageItems = (from >= total) ? new ArrayList<>() : scored.subList(from, to);

        JSONArray items = new JSONArray();
        for (JSONObject p : pageItems) {
            items.add(goodsService.toGoodsCard(p));
        }

        int pages = (int) Math.ceil((double) total / pageSize);

        JSONObject result = new JSONObject();
        result.set("items", items);
        result.set("counts", total);
        result.set("page", page);
        result.set("pageSize", pageSize);
        result.set("pages", pages);
        return result;
    }
}
