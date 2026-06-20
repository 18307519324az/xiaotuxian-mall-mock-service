package com.xtx.mock.service.catalog;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xtx.mock.service.goods.GoodsMockService;
import com.xtx.mock.util.MockStockUtils;
import lombok.extern.slf4j.Slf4j;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * HomeCatalogMockService — 首页/分类/品牌/专题模块业务逻辑。
 * <p>
 * 职责：首页新鲜好物、人气推荐、首页楼层、品牌列表/详情、专题列表/详情、
 * 分类树、分类筛选、分类商品分页等只读接口的业务组装。
 * <p>
 * 数据源通过 setter 注入（由 MockController 在 @PostConstruct 中配置）：
 * <ul>
 *   <li>masterCategories — 分类主数据（只读 JSONArray）</li>
 *   <li>masterProducts — 商品 SPU 主数据（只读 JSONObject）</li>
 *   <li>goodsService — GoodsMockService 引用（用于 toGoodsCard）</li>
 *   <li>masterFloors — 楼层大图（只读 JSONObject）</li>
 *   <li>homeBrandData — 品牌数据（只读 JSONArray）</li>
 *   <li>homeSpecialData — 专题数据（只读 JSONArray）</li>
 *   <li>homeBannerData — 轮播图数据</li>
 *   <li>urlReachableCache — URL 可达性缓存</li>
 *   <li>imageNormalizer — 图片 URL 规范化函数</li>
 *   <li>categoryFallbackSupplier — 分类回退图函数</li>
 *   <li>defaultFallbackImage — 默认兜底图 URL</li>
 *   <li>verifiedProductImages — 已验证真实图的商品 ID 集合</li>
 * </ul>
 */
@Slf4j
public class HomeCatalogMockService {

    // ==================== 注入的数据源 ====================

    private JSONArray masterCategories;
    private JSONObject masterProducts;
    private GoodsMockService goodsService;
    private JSONObject masterFloors;
    private JSONArray homeBrandData;
    private JSONArray homeSpecialData;
    private Object homeBannerData;
    private ConcurrentHashMap<String, Boolean> urlReachableCache;
    private BiFunction<String, String, String> imageNormalizer;
    private Function<String, String> categoryFallbackSupplier;
    private String defaultFallbackImage;
    private Set<String> verifiedProductImages;

    public void setMasterCategories(JSONArray v) { this.masterCategories = v; }
    public void setMasterProducts(JSONObject v) { this.masterProducts = v; }
    public void setGoodsService(GoodsMockService v) { this.goodsService = v; }
    public void setMasterFloors(JSONObject v) { this.masterFloors = v; }
    public void setHomeBrandData(JSONArray v) { this.homeBrandData = v; }
    public void setHomeSpecialData(JSONArray v) { this.homeSpecialData = v; }
    public void setHomeBannerData(Object v) { this.homeBannerData = v; }
    public void setUrlReachableCache(ConcurrentHashMap<String, Boolean> v) { this.urlReachableCache = v; }
    public void setImageNormalizer(BiFunction<String, String, String> v) { this.imageNormalizer = v; }
    public void setCategoryFallbackSupplier(Function<String, String> v) { this.categoryFallbackSupplier = v; }
    public void setDefaultFallbackImage(String v) { this.defaultFallbackImage = v; }
    public void setVerifiedProductImages(Set<String> v) { this.verifiedProductImages = v; }

    // ========================================================================
    //  Home 首页端点
    // ========================================================================

    /**
     * GET /home/category/head — 分类树 + 顶级分类商品卡片
     */
    public JSONArray categoryHead() {
        JSONArray result = new JSONArray();
        for (int i = 0; i < masterCategories.size(); i++) {
            JSONObject cat = masterCategories.getJSONObject(i);
            String catName = cat.getStr("name", "");
            String catId = cat.getStr("id");

            JSONObject out = new JSONObject();
            out.set("id", catId);
            out.set("name", catName);
            String catPic = normalizeImage(cat.getStr("picture", ""),
                categoryFallbackSupplier.apply(catName));
            out.set("picture", catPic);
            out.set("pictureUrl", catPic);

            // children — 纯结构，不含商品数据
            JSONArray children = cat.getJSONArray("children");
            JSONArray outChildren = new JSONArray();
            if (children != null) {
                for (int j = 0; j < children.size(); j++) {
                    JSONObject sub = children.getJSONObject(j);
                    JSONObject outSub = new JSONObject();
                    outSub.set("id", sub.getStr("id"));
                    outSub.set("name", sub.getStr("name"));
                    String subPic = normalizeImage(sub.getStr("picture", ""),
                        categoryFallbackSupplier.apply(sub.getStr("parentName", catName)));
                    outSub.set("picture", subPic);
                    outSub.set("pictureUrl", subPic);
                    outSub.set("children", JSONNull.NULL);
                    outSub.set("goods", JSONNull.NULL);
                    outChildren.add(outSub);
                }
            }
            out.set("children", outChildren);

            // goods — 取属于该分类的商品（最多 8 个）
            out.set("goods", buildTopCategoryGoods(catId, 8));

            result.add(out);
        }
        return result;
    }

    /**
     * GET /home/brand — 品牌列表
     */
    public JSONArray homeBrandList(Integer limit) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < homeBrandData.size(); i++) {
            JSONObject item = JSONUtil.parseObj(homeBrandData.getJSONObject(i).toString());
            item.set("desc", buildBrandDesc(item.getStr("id", "")));
            result.add(item);
        }
        if (limit != null && limit < result.size()) {
            return new JSONArray(result.subList(0, limit));
        }
        return result;
    }

    /**
     * GET /home/new — 新鲜好物（4 个，真实图优先排序）
     */
    public JSONArray homeNew() {
        List<JSONObject> sorted = sortProductsByVerifiedThenSort(masterProducts, verifiedProductImages);
        JSONArray result = new JSONArray();
        for (int i = 0; i < Math.min(4, sorted.size()); i++) {
            result.add(goodsService.toGoodsCard(sorted.get(i)));
        }
        return result;
    }

    /**
     * GET /home/hot — 人气推荐（4 个，salesCount DESC）
     */
    public JSONArray homeHot() {
        List<JSONObject> sorted = new ArrayList<>();
        for (Map.Entry<String, Object> entry : masterProducts.entrySet()) {
            sorted.add((JSONObject) entry.getValue());
        }
        sorted.sort((a, b) -> {
            int cmp = Integer.compare(b.getInt("salesCount", 0), a.getInt("salesCount", 0));
            if (cmp != 0) return cmp;
            return a.getStr("id", "").compareTo(b.getStr("id", ""));
        });
        JSONArray result = new JSONArray();
        String[] titles = {"特惠推荐", "爆款推荐", "一站买全", "领券中心"};
        String[] alts = {"它们最实惠", "它们最受欢迎", "使用场景下精心优选", "更多超值优惠券"};
        for (int i = 0; i < Math.min(4, sorted.size()); i++) {
            JSONObject p = sorted.get(i);
            JSONObject item = new JSONObject();
            item.set("id", p.getStr("id"));
            item.set("picture", p.getStr("picture"));
            item.set("title", i < titles.length ? titles[i] : "推荐");
            item.set("alt", i < alts.length ? alts[i] : "");
            item.set("price", p.getStr("price"));
            result.add(item);
        }
        return result;
    }

    /**
     * GET /home/goods — 首页楼层（前 4 分类，每类 8 商品）
     */
    public JSONArray homeGoods() {
        JSONArray result = new JSONArray();
        int catLimit = Math.min(4, masterCategories.size());
        for (int i = 0; i < catLimit; i++) {
            JSONObject cat = masterCategories.getJSONObject(i);
            JSONObject section = new JSONObject();
            String sectionCatName = cat.getStr("name", "");
            section.set("id", cat.getStr("id"));
            section.set("name", sectionCatName);

            String floorPic = masterFloors != null ? masterFloors.getStr(cat.getStr("id"), "") : "";
            if (!floorPic.isBlank()) {
                section.set("picture", normalizeImage(floorPic,
                    categoryFallbackSupplier.apply(sectionCatName)));
            } else {
                section.set("picture", normalizeImage(cat.getStr("picture", ""),
                    categoryFallbackSupplier.apply(sectionCatName)));
            }
            section.set("saleInfo", "全场优惠");

            // 子分类名称列表
            JSONArray children = cat.getJSONArray("children");
            JSONArray subNames = new JSONArray();
            if (children != null) {
                for (int j = 0; j < children.size(); j++) {
                    JSONObject sub = children.getJSONObject(j);
                    JSONObject subItem = new JSONObject();
                    subItem.set("id", sub.getStr("id"));
                    subItem.set("name", sub.getStr("name"));
                    subNames.add(subItem);
                }
            }
            section.set("children", subNames);

            section.set("goods", buildTopCategoryGoods(cat.getStr("id"), 8));
            result.add(section);
        }
        return result;
    }

    /**
     * GET /home/special — 专题列表
     */
    public JSONArray homeSpecial() {
        JSONArray result = new JSONArray();
        for (int i = 0; i < homeSpecialData.size(); i++) {
            String id = homeSpecialData.getJSONObject(i).getStr("id", "");
            JSONObject model = buildSpecialModel(id);
            if (model != null) {
                result.add(model);
            }
        }
        return result;
    }

    /**
     * GET /home/banner — 轮播图
     */
    public Object homeBanner() {
        return homeBannerData != null ? homeBannerData : new JSONObject();
    }

    // ========================================================================
    //  Brand 品牌
    // ========================================================================

    /**
     * 查找品牌基本信息
     */
    public JSONObject findBrandBase(String brandId) {
        for (int i = 0; i < homeBrandData.size(); i++) {
            JSONObject item = homeBrandData.getJSONObject(i);
            if (brandId.equals(item.getStr("id"))) {
                return JSONUtil.parseObj(item.toString());
            }
        }
        return null;
    }

    /**
     * GET /brand/{id} — 品牌详情
     */
    public JSONObject buildBrandDetail(String brandId, Set<String> followedBrandIds) {
        JSONObject brand = findBrandBase(brandId);
        if (brand == null) return null;
        brand.set("desc", buildBrandDesc(brandId));
        brand.set("story", buildBrandStory(brandId, brand.getStr("name", "品牌")));
        brand.set("serviceTags", buildBrandTags(brandId));
        brand.set("goods", buildBrandGoods(brandId));
        brand.set("followed", followedBrandIds != null && followedBrandIds.contains(brandId));
        brand.set("followCount", Math.max(36, brandId.hashCode() & 255));
        return brand;
    }

    /**
     * GET /brand/{id}/goods — 品牌商品
     */
    public JSONArray buildBrandGoods(String brandId) {
        JSONArray goods = new JSONArray();
        int limit = 8;
        for (Map.Entry<String, Object> entry : masterProducts.entrySet()) {
            if (goods.size() >= limit) break;
            JSONObject product = (JSONObject) entry.getValue();
            if (brandId.equals(product.getStr("brandId", ""))) {
                goods.add(goodsService.toGoodsCard(product));
            }
        }
        return goods;
    }

    /**
     * GET /brand/{id}/goods 用于 404 判断
     */
    public boolean brandExists(String brandId) {
        return findBrandBase(brandId) != null;
    }

    // ========================================================================
    //  Topic / Special 专题
    // ========================================================================

    /**
     * 查找专题基本信息
     */
    public JSONObject findSpecialBase(String specialId) {
        for (int i = 0; i < homeSpecialData.size(); i++) {
            JSONObject item = homeSpecialData.getJSONObject(i);
            if (specialId.equals(item.getStr("id"))) {
                return JSONUtil.parseObj(item.toString());
            }
        }
        return null;
    }

    /**
     * buildSpecialModel — 专题唯一数据源
     */
    public JSONObject buildSpecialModel(String specialId) {
        JSONObject model = findSpecialBase(specialId);
        if (model == null) return null;
        model.set("summary", buildSpecialSummary(specialId));
        model.set("goods", buildSpecialGoods(specialId));

        // 运行时计算最低价
        JSONArray goods = model.getJSONArray("goods");
        if (goods != null) {
            double computedLowest = Double.MAX_VALUE;
            for (int g = 0; g < goods.size(); g++) {
                JSONObject good = goods.getJSONObject(g);
                Object priceObj = good.get("price");
                if (priceObj != null) {
                    try {
                        double p = Double.parseDouble(priceObj.toString());
                        if (p < computedLowest) computedLowest = p;
                    } catch (Exception ignored) {}
                }
            }
            if (computedLowest < Double.MAX_VALUE) {
                model.set("lowestPrice", String.valueOf(computedLowest));
                model.set("lowestPriceValue", computedLowest);
            }
        }

        // 封面兜底
        String coverUrl = model.getStr("cover");
        JSONArray goodsList = model.getJSONArray("goods");
        String firstGoodsPic = getFirstValidGoodsPicture(goodsList);
        if (firstGoodsPic == null) firstGoodsPic = defaultFallbackImage;
        boolean coverNeedsReplacement = isFakeCoverTopic(specialId)
            || isInvalidTopicCover(coverUrl) || !isUrlReachable(coverUrl);
        if (coverNeedsReplacement) {
            model.set("cover", firstGoodsPic);
        }
        String resolved = model.getStr("cover");
        if (coverNeedsReplacement) {
            resolved = firstGoodsPic;
            model.set("coverSource", "goods-first");
        } else {
            model.set("coverSource", "topic-cover");
        }
        String cgId = findFirstValidGoodsId(goodsList);
        model.set("coverGoodsId", cgId != null ? cgId : "");
        model.set("cover", resolved);
        model.set("resolvedCover", resolved);
        return model;
    }

    /**
     * GET /special/{id} — 专题详情
     */
    public JSONObject buildSpecialDetail(String specialId, Set<String> collectedTopicIds) {
        JSONObject special = buildSpecialModel(specialId);
        if (special == null) return null;
        special.set("theme", buildSpecialTheme(specialId));
        special.set("detailBlocks", buildSpecialDetailBlocks(specialId));
        special.set("collected", collectedTopicIds != null && collectedTopicIds.contains(specialId));

        String bannerUrl = special.getStr("banner");
        JSONArray goodsList = special.getJSONArray("goods");
        String firstGoodsPic = getFirstValidGoodsPicture(goodsList);
        if (firstGoodsPic == null) firstGoodsPic = defaultFallbackImage;
        if (isInvalidTopicCover(bannerUrl) || !isUrlReachable(bannerUrl)) {
            special.set("banner", firstGoodsPic);
        }
        return special;
    }

    /**
     * GET /topic/{id}/goods — 专题商品
     */
    public JSONArray buildSpecialGoods(String specialId) {
        return switch (specialId) {
            case "1482381924796334084" -> goodsService.buildGoodsCardsByIds(
                List.of("1124015", "1085007", "1554013", "1540016", "1548001", "3406047", "1435017", "1512026"));
            case "1482381924729225219" -> goodsService.buildGoodsCardsByIds(
                List.of("1318002", "3407081", "3405016", "3847005", "3425016", "4000278", "3990698", "3986658"));
            case "1482381924796334083" -> goodsService.buildGoodsCardsByIds(
                List.of("1006029", "1129016", "1435017", "1451027", "1487013", "3436033", "3990408", "3998109"));
            case "v0.9.7-topic-kitchen" -> goodsService.buildGoodsCardsByIds(
                List.of("3419049", "3388018", "1545002", "1292003", "1189005", "1189003", "1189004", "1189007"));
            case "v0.9.7-topic-home-storage" -> goodsService.buildGoodsCardsByIds(
                List.of("1252031", "1225018", "1683030", "1183010", "1028004", "3829130", "1672002", "3828110"));
            case "v0.9.7-topic-baby" -> goodsService.buildGoodsCardsByIds(
                List.of("3992495", "4023641", "3995844", "3998535", "3989454", "4026178", "1519011", "4005108"));
            case "v0.9.7-topic-sport" -> goodsService.buildGoodsCardsByIds(
                List.of("3990777", "3994572", "3459033", "3987045", "3994931", "4014017", "4011360", "3520212"));
            case "v0.9.7-topic-digital" -> goodsService.buildGoodsCardsByIds(
                List.of("3842020", "1193027", "3994432", "1111002", "3529022", "3440042", "3487028", "1480015"));
            case "v0.9.7-topic-guofeng" -> goodsService.buildGoodsCardsByIds(
                List.of("4000283", "4000422", "4000102", "1281002", "1666066", "1555000", "3459033", "3986614"));
            default -> new JSONArray();
        };
    }

    // ========================================================================
    //  Category 分类
    // ========================================================================

    /**
     * GET /category?id={id} — 分类详情
     */
    public JSONObject categoryTop(String id) {
        JSONObject found = null;
        for (int i = 0; i < masterCategories.size(); i++) {
            JSONObject cat = masterCategories.getJSONObject(i);
            if (cat.getStr("id").equals(id)) {
                found = cat;
                break;
            }
        }
        if (found == null) return null;

        String catName = found.getStr("name", "");
        String catFallback = categoryFallbackSupplier.apply(catName);

        JSONObject result = new JSONObject();
        result.set("id", found.getStr("id"));
        result.set("name", catName);
        result.set("picture", normalizeImage(found.getStr("picture", ""), catFallback));

        JSONArray childrenOut = new JSONArray();
        JSONArray children = found.getJSONArray("children");
        if (children != null) {
            for (int j = 0; j < children.size(); j++) {
                JSONObject sub = children.getJSONObject(j);
                JSONObject subOut = new JSONObject();
                subOut.set("id", sub.getStr("id"));
                subOut.set("name", sub.getStr("name"));
                String subPic = normalizeImage(sub.getStr("picture", ""),
                    categoryFallbackSupplier.apply(sub.getStr("parentName", catName)));
                subOut.set("picture", subPic);
                subOut.set("parentId", sub.getStr("parentId"));
                subOut.set("parentName", sub.getStr("parentName"));

                JSONArray goodsIds = sub.getJSONArray("goodsIds");
                JSONArray goods = new JSONArray();
                if (goodsIds != null) {
                    for (int k = 0; k < goodsIds.size(); k++) {
                        String gid = goodsIds.getStr(k);
                        if (masterProducts.containsKey(gid)) {
                            goods.add(goodsService.toGoodsCard(masterProducts.getJSONObject(gid)));
                        }
                    }
                }
                subOut.set("goods", goods);
                subOut.set("categories", JSONNull.NULL);
                subOut.set("brands", JSONNull.NULL);
                subOut.set("saleProperties", JSONNull.NULL);
                childrenOut.add(subOut);
            }
        }
        result.set("children", childrenOut);
        return result;
    }

    /**
     * GET /category/sub/filter — 分类筛选（空结构）
     */
    public JSONObject categorySubFilter(String id) {
        JSONObject result = new JSONObject();
        result.set("id", id);
        result.set("name", "");
        result.set("parentId", "");
        result.set("parentName", "");
        result.set("goods", new JSONArray());
        result.set("categories", new JSONArray());
        result.set("brands", new JSONArray());
        result.set("saleProperties", new JSONArray());
        return result;
    }

    /**
     * POST /category/goods/temporary — 分类商品分页
     */
    public JSONObject categoryGoods(String categoryId, int page, int pageSize,
                                     boolean inventoryOnly, boolean discountOnly,
                                     String sortField, String sortMethod) {
        // 从 masterCategories 中查找分类的 goodsIds
        Set<String> matchedGoodsIds = new LinkedHashSet<>();
        if (!categoryId.isEmpty()) {
            for (int i = 0; i < masterCategories.size(); i++) {
                JSONObject cat = masterCategories.getJSONObject(i);
                if (categoryId.equals(cat.getStr("id"))) {
                    JSONArray children = cat.getJSONArray("children");
                    if (children != null) {
                        for (int j = 0; j < children.size(); j++) {
                            JSONArray gids = children.getJSONObject(j).getJSONArray("goodsIds");
                            if (gids != null) {
                                for (int k = 0; k < gids.size(); k++) {
                                    matchedGoodsIds.add(gids.getStr(k));
                                }
                            }
                        }
                    }
                    break;
                }
                JSONArray children = cat.getJSONArray("children");
                if (children != null) {
                    for (int j = 0; j < children.size(); j++) {
                        JSONObject sub = children.getJSONObject(j);
                        if (categoryId.equals(sub.getStr("id"))) {
                            JSONArray gids = sub.getJSONArray("goodsIds");
                            if (gids != null) {
                                for (int k = 0; k < gids.size(); k++) {
                                    matchedGoodsIds.add(gids.getStr(k));
                                }
                            }
                            break;
                        }
                    }
                }
            }
        }

        // 回退到按 topCategoryId 匹配
        if (matchedGoodsIds.isEmpty()) {
            for (Map.Entry<String, Object> entry : masterProducts.entrySet()) {
                JSONObject p = (JSONObject) entry.getValue();
                if (categoryId.equals(p.getStr("topCategoryId")) || categoryId.equals(p.getStr("categoryId"))) {
                    matchedGoodsIds.add(p.getStr("id"));
                }
            }
        }

        // 按 goodsIds 顺序取商品
        List<JSONObject> catProducts = new ArrayList<>();
        for (String gid : matchedGoodsIds) {
            if (masterProducts.containsKey(gid)) {
                catProducts.add(masterProducts.getJSONObject(gid));
            }
        }

        // 过滤
        catProducts.removeIf(product ->
            (inventoryOnly && product.getInt("inventory", 0) <= 0)
                || (discountOnly && Double.parseDouble(product.getStr("price", "0"))
                    >= Double.parseDouble(product.getStr("oldPrice", product.getStr("price", "0"))))
        );

        // 排序
        catProducts.sort((a, b) -> {
            int cmp;
            if ("price".equals(sortField)) {
                cmp = Double.compare(
                    Double.parseDouble(a.getStr("price", "0")),
                    Double.parseDouble(b.getStr("price", "0"))
                );
            } else if ("stock".equals(sortField)) {
                cmp = Integer.compare(
                    MockStockUtils.availableStock(a),
                    MockStockUtils.availableStock(b)
                );
            } else if ("orderNum".equals(sortField)) {
                cmp = Integer.compare(a.getInt("salesCount", 0), b.getInt("salesCount", 0));
            } else if ("evaluateNum".equals(sortField)) {
                cmp = Integer.compare(a.getInt("commentCount", 0), b.getInt("commentCount", 0));
            } else if ("publishTime".equals(sortField)) {
                cmp = Long.compare(
                    Long.parseLong(a.getStr("id", "0")),
                    Long.parseLong(b.getStr("id", "0"))
                );
            } else {
                cmp = Integer.compare(a.getInt("sort", 0), b.getInt("sort", 0));
            }
            if ("desc".equals(sortMethod)) cmp = -cmp;
            if (cmp != 0) return cmp;
            return a.getStr("id", "").compareTo(b.getStr("id", ""));
        });

        // 分页
        int total = catProducts.size();
        int fromIdx = (page - 1) * pageSize;
        int toIdx = Math.min(fromIdx + pageSize, total);
        JSONArray items = new JSONArray();
        if (fromIdx < total) {
            for (int i = fromIdx; i < toIdx; i++) {
                items.add(goodsService.toGoodsCard(catProducts.get(i)));
            }
        }

        JSONObject result = new JSONObject();
        result.set("counts", total);
        result.set("pageSize", pageSize);
        result.set("page", page);
        result.set("pages", (int) Math.ceil(total / (double) pageSize));
        result.set("items", items);
        return result;
    }

    /** 判断专题是否没有独立专题封面 */
    private boolean isFakeCoverTopic(String topicId) {
        return topicId != null && (topicId.startsWith("v0.9.7") || topicId.startsWith("v0.9.6"));
    }

    /** 判断专题封面是否为无效图 */
    private boolean isInvalidTopicCover(String url) {
        if (url == null || url.isBlank()) return true;
        String lower = url.toLowerCase();
        return lower.contains("none") || lower.contains("placeholder") ||
               lower.contains("default") || lower.contains("jnnww") ||
               lower.contains("jiangnan") || lower.contains("brand") ||
               lower.endsWith(".svg") || lower.startsWith("data:");
    }

    /** 检查远程图片 URL 是否可达 (HEAD 请求 + 缓存) */
    boolean isUrlReachable(String url) {
        if (url == null || url.isBlank()) return false;
        return urlReachableCache.computeIfAbsent(url, key -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URI(key).toURL().openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                int code = conn.getResponseCode();
                conn.disconnect();
                return code == 200;
            } catch (Exception e) {
                return false;
            }
        });
    }

    /** 从专题商品列表中获取第一张有效商品图片 */
    private String getFirstValidGoodsPicture(JSONArray goodsList) {
        if (goodsList != null) {
            for (Object gObj : goodsList) {
                JSONObject g = (JSONObject) gObj;
                String pic = g.getStr("picture");
                if (pic != null && !pic.isBlank() && !isInvalidTopicCover(pic)) return pic;
                pic = g.getStr("image");
                if (pic != null && !pic.isBlank() && !isInvalidTopicCover(pic)) return pic;
            }
        }
        return null;
    }

    /** 查找第一张有效商品图对应的商品 ID */
    private String findFirstValidGoodsId(JSONArray goodsList) {
        if (goodsList != null) {
            for (Object gObj : goodsList) {
                JSONObject g = (JSONObject) gObj;
                String pic = g.getStr("picture");
                if (pic != null && !pic.isBlank() && !isInvalidTopicCover(pic)) return g.getStr("id", "");
                pic = g.getStr("image");
                if (pic != null && !pic.isBlank() && !isInvalidTopicCover(pic)) return g.getStr("id", "");
            }
        }
        return "";
    }

    // ========================================================================
    //  Brand helpers
    // ========================================================================

    private String buildBrandDesc(String brandId) {
        return switch (brandId) {
            case "1" -> "咏汉定制专注国风服饰与鞋靴设计，演绎东方美学与现代时尚的融合。";
            case "2" -> "ICCUG 精选箱包饰品与轻奢配件，为品质出行增添格调。";
            case "3" -> "釉色美颜聚焦美容护肤与精致个护，呵护每一寸肌肤。";
            case "4" -> "绿荫倡导自然生活，精选户外园艺好物，让家更有生机。";
            case "5" -> "永久专注运动服饰与户外健身装备，陪伴每一次活力时刻。";
            case "6" -> "CZ永在提供男士洗护与清洁个护用品，精致从日常开始。";
            case "7" -> "DDAO 以精美配饰与腕表诠释个人风格，点亮日常搭配。";
            case "8" -> "硕华品质聚焦数码电子与智能设备，让科技服务生活。";
            case "9" -> "黛儿围绕居家生活与家纺收纳，打造温馨舒适的家居空间。";
            case "spider99999999999" -> "传智自有品牌提供日用良品、学习办公与食品饮料，品质生活触手可及。";
            default -> "品牌精选汇集优质商品，为您提供品质购物体验。";
        };
    }

    private String buildBrandStory(String brandId, String name) {
        return switch (brandId) {
            case "1" -> name + "主打国风服饰与日常鞋靴，当前品牌页只展示与服饰分类对应的真实商品。";
            case "2" -> name + "聚焦办公文具和数码配件，品牌精选商品按数码分类真实商品组装。";
            case "3" -> name + "围绕女性用品和轻护理场景组织商品，避免再出现与专题无关的混搭商品。";
            case "4" -> name + "聚焦户外和运动训练，品牌精选商品按运动分类真实商品展示。";
            case "5" -> name + "聚焦居家收纳和整理场景，列表中的商品均来自居家相关真实分类。";
            case "6" -> name + "围绕厨房食材和方便食品组织内容，品牌商品均为美食相关真实商品。";
            case "7" -> name + "聚焦手机配件和常用数码设备，品牌页只展示数码相关真实商品。";
            case "8" -> name + "聚焦数码电竞、影音娱乐和智能设备，页面展示的商品统一来自数码相关真实分类。";
            case "9" -> name + "围绕母婴和儿童成长用品组织内容，品牌精选商品统一来自母婴真实分类。";
            case "spider99999999999" -> name + "围绕居家日用和品质生活组织内容，品牌页展示自有严选范围内的真实商品。";
            default -> name + "已接入真实商品卡片。";
        };
    }

    private JSONArray buildBrandTags(String brandId) {
        JSONArray tags = new JSONArray();
        switch (brandId) {
            case "1" -> { tags.add("国风服饰"); tags.add("日常穿搭"); tags.add("女士鞋靴"); }
            case "2" -> { tags.add("办公文具"); tags.add("数码配件"); tags.add("桌面设备"); }
            case "3" -> { tags.add("女性用品"); tags.add("精致护理"); tags.add("舒适日用"); }
            case "4" -> { tags.add("户外出行"); tags.add("运动训练"); tags.add("护具装备"); }
            case "5" -> { tags.add("居家收纳"); tags.add("空间整理"); tags.add("日常清洁"); }
            case "6" -> { tags.add("厨房食材"); tags.add("米面粮油"); tags.add("调味速食"); }
            case "7" -> { tags.add("手机配件"); tags.add("3C数码"); tags.add("办公设备"); }
            case "8" -> { tags.add("数码电竞"); tags.add("影音娱乐"); tags.add("智能设备"); }
            case "9" -> { tags.add("母婴用品"); tags.add("童装童鞋"); tags.add("成长穿搭"); }
            case "spider99999999999" -> { tags.add("自有严选"); tags.add("居家日用"); tags.add("品质生活"); }
            default -> tags.add("品牌精选");
        }
        return tags;
    }

    // ========================================================================
    //  Topic / Special helpers
    // ========================================================================

    private String buildSpecialSummary(String specialId) {
        return switch (specialId) {
            case "1482381924796334084" -> "围绕纸品、个护和干湿巾整理的专题内容，精选个人护理与纸品类真实商品。";
            case "1482381924729225219" -> "围绕宠物食品、宠物用品和萌宠生活整理的专题内容，全部使用宠物类真实商品。";
            case "1482381924796334083" -> "围绕毛巾浴巾、浴室个护和清洁用品整理的专题内容，精选浴室个护类真实商品。";
            case "v0.9.7-topic-kitchen" -> "围绕厨房粮油、米面调味和方便速食整理的专题内容，全部来自美食类真实商品。";
            case "v0.9.7-topic-home-storage" -> "围绕居家收纳、整理焕新和家纺好物整理的专题内容，精选居家类真实商品。";
            case "v0.9.7-topic-baby" -> "围绕母婴用品、儿童鞋服和成长好物整理的专题内容，全部来自母婴类真实商品。";
            case "v0.9.7-topic-sport" -> "围绕轻运动穿搭、健身器材和运动护具整理的专题内容，精选运动类真实商品。";
            case "v0.9.7-topic-digital" -> "围绕数码配件、办公文具和3C好物整理的专题内容，精选数码类真实商品。";
            case "v0.9.7-topic-guofeng" -> "围绕国风服饰、新国潮穿搭和日常配饰整理的专题内容，精选服饰类真实商品。";
            default -> "专题内容已接入真实商品。";
        };
    }

    private String buildSpecialTheme(String specialId) {
        return switch (specialId) {
            case "1482381924796334084" -> "个护纸品精选";
            case "1482381924729225219" -> "宠物生活精选";
            case "1482381924796334083" -> "浴室个护精选";
            case "v0.9.7-topic-kitchen" -> "厨房粮油囤货";
            case "v0.9.7-topic-home-storage" -> "居家收纳焕新";
            case "v0.9.7-topic-baby" -> "母婴柔软好物";
            case "v0.9.7-topic-sport" -> "轻运动穿搭";
            case "v0.9.7-topic-digital" -> "数码办公精选";
            case "v0.9.7-topic-guofeng" -> "国风服饰穿搭";
            default -> "专题精选";
        };
    }

    private JSONArray buildSpecialDetailBlocks(String specialId) {
        JSONArray blocks = new JSONArray();
        switch (specialId) {
            case "1482381924796334084" -> {
                blocks.add("围绕纸品、个护和干湿巾场景整理专题内容，专题精选好物全部来自个护纸品分类的真实商品。");
                blocks.add("涵盖纸品、个人护理和清洁湿巾等品类，每个商品均为真实在售商品。");
            }
            case "1482381924729225219" -> {
                blocks.add("围绕宠物食品和宠物用品场景整理专题内容，专题商品全部来自宠物分类的真实商品。");
                blocks.add("涵盖猫粮犬粮、猫砂用品和宠物营养品等好物，关爱萌宠健康成长。");
            }
            case "1482381924796334083" -> {
                blocks.add("围绕毛巾浴巾、浴室个护和清洁用品整理专题内容，专题商品按浴室个护分类真实商品展示。");
                blocks.add("适合浴室日用、个护清洁和居家洗浴场景，每件商品均含真实图片和价格。");
            }
            case "v0.9.7-topic-kitchen" -> {
                blocks.add("围绕厨房粮油与方便速食场景整理专题内容，专题商品全部来自美食分类的真实商品。");
                blocks.add("涵盖米面粮油、调味品和方便速食，适合家庭厨房囤货和日常烹饪需求。");
            }
            case "v0.9.7-topic-home-storage" -> {
                blocks.add("围绕居家收纳焕新场景整理专题内容，专题商品来自居家分类的真实商品。");
                blocks.add("涵盖收纳用品、厨具家纺等品类，帮助打造整洁有序的居家环境。");
            }
            case "v0.9.7-topic-baby" -> {
                blocks.add("围绕母婴用品与儿童成长场景整理专题内容，专题商品全部来自母婴分类的真实商品。");
                blocks.add("涵盖儿童鞋服、配饰等品类，适合宝妈为宝宝挑选柔软舒适的成长好物。");
            }
            case "v0.9.7-topic-sport" -> {
                blocks.add("围绕轻运动穿搭与健身场景整理专题内容，专题商品来自运动分类的真实商品。");
                blocks.add("涵盖运动护具、健身器材和训练装备，轻松开启活力运动生活。");
            }
            case "v0.9.7-topic-digital" -> {
                blocks.add("围绕数码配件与办公场景整理专题内容，专题商品来自数码分类的真实商品。");
                blocks.add("涵盖充电设备、手机配件、办公文具和影音耳机等品类。");
            }
            case "v0.9.7-topic-guofeng" -> {
                blocks.add("围绕国风服饰与新国潮穿搭场景整理专题内容，专题商品来自服饰分类的真实商品。");
                blocks.add("涵盖国潮配饰、鞋靴、外套等品类，展现东方美学与现代时尚的融合。");
            }
        }
        return blocks;
    }

    // ========================================================================
    //  Utility
    // ========================================================================

    /** 规范化图片 URL */
    private String normalizeImage(String url, String fallbackUrl) {
        return imageNormalizer != null
            ? imageNormalizer.apply(url, fallbackUrl)
            : (url != null && !url.isBlank() ? url : fallbackUrl);
    }

    /** 从 masterProducts 按 topCategoryId 取前 limit 个商品（真实图优先排序） */
    private JSONArray buildTopCategoryGoods(String catId, int limit) {
        List<JSONObject> matched = new ArrayList<>();
        for (Map.Entry<String, Object> entry : masterProducts.entrySet()) {
            JSONObject p = (JSONObject) entry.getValue();
            if (catId.equals(p.getStr("topCategoryId"))) {
                matched.add(p);
            }
        }
        matched.sort((a, b) -> {
            int prefA = verifiedProductImages != null && verifiedProductImages.contains(a.getStr("id")) ? 0 : 1;
            int prefB = verifiedProductImages != null && verifiedProductImages.contains(b.getStr("id")) ? 0 : 1;
            if (prefA != prefB) return Integer.compare(prefA, prefB);
            return Integer.compare(b.getInt("sort", 0), a.getInt("sort", 0));
        });
        JSONArray goods = new JSONArray();
        for (int k = 0; k < Math.min(limit, matched.size()); k++) {
            goods.add(goodsService.toGoodsCard(matched.get(k)));
        }
        return goods;
    }

    /** 从 masterProducts 按 verifiedProductImages + sort DESC 排序 */
    private List<JSONObject> sortProductsByVerifiedThenSort(JSONObject products, Set<String> verified) {
        List<JSONObject> sorted = new ArrayList<>();
        for (Map.Entry<String, Object> entry : products.entrySet()) {
            sorted.add((JSONObject) entry.getValue());
        }
        sorted.sort((a, b) -> {
            int prefA = verified != null && verified.contains(a.getStr("id")) ? 0 : 1;
            int prefB = verified != null && verified.contains(b.getStr("id")) ? 0 : 1;
            if (prefA != prefB) return Integer.compare(prefA, prefB);
            int cmp = Integer.compare(b.getInt("sort", 0), a.getInt("sort", 0));
            if (cmp != 0) return cmp;
            return a.getStr("id", "").compareTo(b.getStr("id", ""));
        });
        return sorted;
    }
}
