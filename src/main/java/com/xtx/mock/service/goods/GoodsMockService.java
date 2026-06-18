package com.xtx.mock.service.goods;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xtx.mock.store.RuntimeReviewStore;
import com.xtx.mock.util.MockImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * GoodsMockService — 商品模块业务逻辑。
 * <p>
 * 职责：商品详情组装、SKU/库存查询、相关/相似/热销推荐、商品卡片 VO。
 * <p>
 * 数据源通过 setter 注入（由 MockController 在 @PostConstruct 中配置）：
 * <ul>
 *   <li>masterProducts — 商品 SPU 主数据（只读）</li>
 *   <li>masterSkus — SKU 主数据（只读）</li>
 *   <li>reviewStore — 评价存储（可选，用于 commentCount）</li>
 * </ul>
 */
@Slf4j
public class GoodsMockService {

    // ==================== 注入的数据源 ====================

    private JSONObject masterProducts;
    private JSONObject masterSkus;
    private RuntimeReviewStore reviewStore;

    public void setMasterProducts(JSONObject m) { this.masterProducts = m; }
    public void setMasterSkus(JSONObject m) { this.masterSkus = m; }
    public void setReviewStore(RuntimeReviewStore s) { this.reviewStore = s; }

    // ==================== 图片常量 ====================

    private static final String PRODUCT_FALLBACK_IMAGE =
        "http://yjy-xiaotuxian-dev.oss-cn-beijing.aliyuncs.com/picture/2021-05-06/201516e3-25d0-48f5-bcee-7f0cafb14176.png";

    // ========================================================================
    //  GET /goods — 商品详情
    // ========================================================================

    public JSONObject buildGoodsDetail(String id) {
        JSONObject p = getProductOr404(id);

        JSONObject result = new JSONObject();
        result.set("id", p.getStr("id"));
        result.set("name", p.getStr("name"));
        result.set("spuCode", p.getStr("spuCode"));
        result.set("desc", p.getStr("desc"));
        result.set("price", p.getStr("price"));
        result.set("oldPrice", p.getStr("oldPrice"));
        result.set("picture", p.getStr("picture", ""));
        result.set("discount", 1);
        result.set("inventory", p.getInt("inventory", 1000));
        result.set("salesCount", p.getInt("salesCount", 0));
        // commentCount = master 预置数 + runtime 用户提交评价数
        int masterCommentCount = p.getInt("commentCount", 0);
        JSONArray rtReviews = (reviewStore != null) ? reviewStore.getGoodsReviews(id) : null;
        int rtCount = rtReviews != null ? rtReviews.size() : 0;
        result.set("commentCount", masterCommentCount + rtCount);
        result.set("collectCount", p.getInt("collectCount", 0));
        result.set("isPreSale", p.getBool("isPreSale", false));
        result.set("isCollect", p.getBool("isCollect", false));
        result.set("publishTime", "2026-01-15 10:00:00");

        // 品牌
        JSONObject brand = p.getJSONObject("brand");
        if (brand == null) {
            brand = new JSONObject();
            brand.set("id", "brand_default");
            brand.set("name", "传智自有品牌");
            brand.set("nameEn", "chuanzhi");
            brand.set("logo", "");
            brand.set("picture", "");
        }
        result.set("brand", brand);

        // 分类
        JSONArray categories = p.getJSONArray("categories");
        result.set("categories", categories != null ? categories : new JSONArray());

        // mainPictures
        JSONArray mainPicsArr = p.getJSONArray("mainPictures");
        result.set("mainPictures", mainPicsArr != null ? mainPicsArr : new JSONArray());

        // pictures (对象数组)
        JSONArray pictures = new JSONArray();
        if (mainPicsArr != null) {
            for (int i = 0; i < mainPicsArr.size(); i++) {
                JSONObject picObj = new JSONObject();
                picObj.set("id", String.valueOf(i + 1));
                picObj.set("pictureUrl", mainPicsArr.getStr(i));
                picObj.set("isMain", i == 0);
                pictures.add(picObj);
            }
        }
        result.set("pictures", pictures);

        // 规格
        JSONArray specs = p.getJSONArray("specs");
        if (specs == null) specs = new JSONArray();
        for (int i = 0; i < specs.size(); i++) {
            JSONObject spec = specs.getJSONObject(i);
            spec.set("id", "spec_" + i);
            spec.set("sort", i + 1);
            JSONArray values = spec.getJSONArray("values");
            if (values != null) {
                for (int j = 0; j < values.size(); j++) {
                    JSONObject val = values.getJSONObject(j);
                    val.set("id", "specval_" + i + "_" + j);
                    val.set("picture", val.getStr("picture", ""));
                }
            }
        }
        result.set("specs", specs);

        // SKU 列表
        JSONArray skuIds = p.getJSONArray("skuIds");
        JSONArray skus = new JSONArray();
        if (skuIds != null && masterSkus != null) {
            for (int i = 0; i < skuIds.size(); i++) {
                String skuId = skuIds.getStr(i);
                JSONObject sku = masterSkus.getJSONObject(skuId);
                if (sku != null) {
                    JSONObject skuOut = new JSONObject();
                    skuOut.set("id", sku.getStr("id"));
                    skuOut.set("skuCode", sku.getStr("skuCode"));
                    skuOut.set("price", sku.getStr("price", "0"));
                    skuOut.set("oldPrice", sku.getStr("oldPrice", "0"));
                    skuOut.set("picture", sku.getStr("picture", ""));
                    skuOut.set("inventory", sku.getInt("inventory", 100));
                    skuOut.set("isEffective", sku.getBool("isEffective", true));
                    skuOut.set("specs", sku.getJSONArray("specs") != null ? sku.getJSONArray("specs") : new JSONArray());
                    skus.add(skuOut);
                }
            }
        }
        result.set("skus", skus);

        // 详情
        JSONObject details = p.getJSONObject("details");
        if (details == null) {
            details = new JSONObject();
            details.set("properties", new JSONArray());
            details.set("pictures", new JSONArray());
        }
        result.set("details", details);

        result.set("userAddresses", new JSONArray());
        result.set("mainVideos", new JSONArray());
        result.set("videoScale", 1);
        result.set("similarProducts", new JSONArray());
        result.set("hotByDay", new JSONArray());

        return result;
    }

    // ========================================================================
    //  GET /goods/sku/{skuId} — SKU 详情
    // ========================================================================

    public JSONObject querySku(String skuId) {
        JSONObject sku = getSkuOr404(skuId);
        String productId = resolveProductId(sku);
        JSONObject product = (masterProducts != null) ? masterProducts.getJSONObject(productId) : null;

        JSONArray specs = new JSONArray();
        if (product != null && product.containsKey("specs")) {
            specs = product.getJSONArray("specs");
        }

        JSONArray skus = new JSONArray();
        if (masterSkus != null) {
            for (Map.Entry<String, Object> entry : masterSkus.entrySet()) {
                JSONObject s = (JSONObject) entry.getValue();
                String sProductId = resolveProductId(s);
                if (productId.equals(sProductId)) {
                    JSONObject slim = new JSONObject();
                    slim.set("id", s.getStr("id"));
                    slim.set("skuId", s.getStr("id"));
                    slim.set("price", s.getStr("price", "0"));
                    slim.set("inventory", s.getInt("inventory", 0));
                    slim.set("oldPrice", s.getStr("oldPrice", s.getStr("price", "0")));
                    slim.set("specs", s.getJSONArray("specs") != null ? s.getJSONArray("specs") : new JSONArray());
                    skus.add(slim);
                }
            }
        }

        JSONObject result = new JSONObject();
        result.set("specs", specs);
        result.set("skus", skus);
        return result;
    }

    // ========================================================================
    //  GET /goods/stock/{skuId} — 库存与价格
    // ========================================================================

    public JSONObject queryStock(String skuId) {
        JSONObject sku = getSkuOr404(skuId);
        JSONObject result = new JSONObject();
        result.set("id", sku.getStr("id"));
        result.set("skuCode", sku.getStr("skuCode"));
        result.set("price", sku.getStr("price", "0"));
        result.set("oldPrice", sku.getStr("oldPrice", "0"));
        result.set("inventory", sku.getInt("inventory", 100));
        result.set("stock", sku.getInt("stock", 100));
        result.set("picture", sku.getStr("picture", ""));
        result.set("isEffective", sku.getBool("isEffective", true));
        result.set("specsText", sku.getStr("attrsText", ""));
        return result;
    }

    // ========================================================================
    //  GET /goods/relevant — 相关推荐
    // ========================================================================

    public JSONArray queryRelevant(String id, Integer limit) {
        int count = limit != null ? limit : 16;

        if (id == null || id.isEmpty()) {
            return queryRelevantNoId(count);
        }

        JSONObject product = getProductOr404(id);
        String topCatId = product.getStr("topCategoryId", "");
        String catId = product.getStr("categoryId", "");

        List<JSONObject> sameCat = new ArrayList<>();
        if (masterProducts != null) {
            for (Map.Entry<String, Object> entry : masterProducts.entrySet()) {
                JSONObject p = (JSONObject) entry.getValue();
                if (!p.getStr("id").equals(id) &&
                    (p.getStr("topCategoryId").equals(topCatId) || p.getStr("categoryId").equals(catId))) {
                    sameCat.add(p);
                }
            }
        }

        sameCat.sort((a, b) -> Integer.compare(b.getInt("salesCount", 0), a.getInt("salesCount", 0)));

        JSONArray result = new JSONArray();
        for (int i = 0; i < Math.min(count, sameCat.size()); i++) {
            result.add(toGoodsCard(sameCat.get(i)));
        }
        return result;
    }

    private JSONArray queryRelevantNoId(int count) {
        JSONArray result = new JSONArray();
        // 无 curatedDemoProducts，直接用 masterProducts 按销量排序
        if (masterProducts != null) {
            List<JSONObject> allProducts = new ArrayList<>();
            for (Map.Entry<String, Object> entry : masterProducts.entrySet()) {
                if (!(entry.getValue() instanceof JSONObject)) continue;
                JSONObject p = (JSONObject) entry.getValue();
                String name = p.getStr("name");
                String picture = p.getStr("picture");
                double price = 0;
                try { price = Double.parseDouble(p.getStr("price", "0")); } catch (Exception ignored) {}
                if (name == null || name.isBlank() || picture == null || picture.isBlank() || price <= 0) continue;
                allProducts.add(p);
            }
            allProducts.sort((a, b) -> Integer.compare(b.getInt("salesCount", 0), a.getInt("salesCount", 0)));
            for (int i = 0; i < Math.min(count, allProducts.size()); i++) {
                result.add(toGoodsCard(allProducts.get(i)));
            }
        }
        return result;
    }

    // ========================================================================
    //  GET /goods/similar/{goodsId} — 找相似
    // ========================================================================

    public JSONArray querySimilar(String goodsId) {
        if (masterProducts == null) return new JSONArray();
        JSONObject product = masterProducts.getJSONObject(goodsId);
        if (product == null) {
            return new JSONArray();
        }
        String topCatId = product.getStr("topCategoryId", "");
        String catId = product.getStr("categoryId", "");
        List<String> similarIds = new ArrayList<>();
        for (Map.Entry<String, Object> entry : masterProducts.entrySet()) {
            if (!(entry.getValue() instanceof JSONObject)) continue;
            JSONObject p = (JSONObject) entry.getValue();
            if (p.getStr("id").equals(goodsId)) continue;
            if (p.getStr("topCategoryId").equals(topCatId) || p.getStr("categoryId").equals(catId)) {
                similarIds.add(p.getStr("id"));
            }
        }
        similarIds.sort((a, b) -> {
            JSONObject pa = masterProducts.getJSONObject(a);
            JSONObject pb = masterProducts.getJSONObject(b);
            return Integer.compare(pb.getInt("salesCount", 0), pa.getInt("salesCount", 0));
        });
        List<String> top8 = similarIds.size() > 8 ? similarIds.subList(0, 8) : similarIds;
        return buildGoodsCardsByIds(top8);
    }

    // ========================================================================
    //  GET /goods/hot — 热销排行
    // ========================================================================

    public JSONArray queryHot(String id, Integer type, Integer limit) {
        List<JSONObject> sorted = new ArrayList<>();
        if (masterProducts != null) {
            for (Map.Entry<String, Object> entry : masterProducts.entrySet()) {
                JSONObject p = (JSONObject) entry.getValue();
                if (id != null && !id.isEmpty() && p.getStr("id").equals(id)) {
                    continue;
                }
                sorted.add(p);
            }
        }

        sorted.sort((a, b) -> {
            int salesA, salesB;
            if (type != null && type == 1) {
                salesA = a.getInt("dailySales", 0);
                salesB = b.getInt("dailySales", 0);
            } else if (type != null && type == 2) {
                salesA = a.getInt("weeklySales", 0);
                salesB = b.getInt("weeklySales", 0);
            } else {
                salesA = a.getInt("salesCount", 0);
                salesB = b.getInt("salesCount", 0);
            }
            return Integer.compare(salesB, salesA);
        });

        int count = limit != null ? limit : 4;
        JSONArray result = new JSONArray();
        for (int i = 0; i < Math.min(count, sorted.size()); i++) {
            JSONObject p = sorted.get(i);
            JSONObject item = toGoodsCard(p);
            item.set("tag", p.getStr("tag", ""));
            result.add(item);
        }
        return result;
    }

    // ========================================================================
    //  商品卡片 VO
    // ========================================================================

    /**
     * 统一商品卡片 VO（列表页使用），字段已裁剪长度防止页面重叠。
     */
    public JSONObject toGoodsCard(JSONObject product) {
        JSONObject card = new JSONObject();
        card.set("id", product.getStr("id"));
        card.set("name", safeText(product.getStr("name"), 40));
        String rawDesc = product.getStr("desc");
        if (rawDesc == null || rawDesc.isBlank()) {
            card.set("desc", "");
        } else {
            card.set("desc", safeText(rawDesc, 24));
        }
        String rawTag = product.getStr("tag");
        if (rawTag != null && !rawTag.isBlank()) {
            card.set("tag", safeText(rawTag, 24));
        } else {
            card.set("tag", card.getStr("desc", ""));
        }
        card.set("price", product.getStr("price"));
        card.set("picture", resolveProductPicture(product));
        card.set("orderNum", product.getInt("salesCount", 0));
        // evaluateNum
        int masterCommentCount = product.getInt("commentCount", 0);
        JSONArray rtReviews = (reviewStore != null) ? reviewStore.getGoodsReviews(product.getStr("id")) : null;
        int rtCount = rtReviews != null ? rtReviews.size() : 0;
        card.set("evaluateNum", masterCommentCount + rtCount);
        // publishTime
        try {
            long idNum = Long.parseLong(product.getStr("id", "0"));
            long daysOffset = idNum % 365;
            card.set("publishTime", "2026-" + String.format("%02d", 1 + (int)(daysOffset / 30)) + "-" + String.format("%02d", 1 + (int)(daysOffset % 30)) + " 10:00:00");
        } catch (NumberFormatException e) {
            card.set("publishTime", "2026-01-15 10:00:00");
        }
        card.set("inventory", product.getInt("inventory", 0));
        card.set("oldPrice", product.getStr("oldPrice", product.getStr("price", "0")));
        // 品牌映射
        JSONArray categories = product.getJSONArray("categories");
        String assignedBrandId = product.getStr("brandId");
        String assignedBrandName = product.getStr("brandName");
        if (assignedBrandId == null || assignedBrandId.isBlank()) {
            String[] brandMapping = resolveBrandByCategory(categories != null ? categories : new JSONArray(),
                product.getStr("topCategoryId", ""));
            assignedBrandId = brandMapping[0];
            assignedBrandName = brandMapping[1];
        }
        card.set("brandId", assignedBrandId);
        card.set("brandName", assignedBrandName);
        return card;
    }

    // ========================================================================
    //  关键词 / ID 列表商品查询（Home/Special 共用）
    // ========================================================================

    public JSONArray buildGoodsCardsByCategoryKeywords(List<String> keywords, int limit) {
        JSONArray goods = new JSONArray();
        if (masterProducts == null) return goods;
        Set<String> added = new HashSet<>();
        for (Map.Entry<String, Object> entry : masterProducts.entrySet()) {
            if (!(entry.getValue() instanceof JSONObject)) continue;
            JSONObject product = (JSONObject) entry.getValue();
            String name = product.getStr("name");
            boolean matched = false;
            for (String kw : keywords) {
                if (name != null && name.contains(kw)) { matched = true; break; }
                JSONArray cats = product.getJSONArray("categories");
                if (cats != null) {
                    for (Object c : cats) {
                        String catName = ((JSONObject) c).getStr("name", "");
                        if (catName.contains(kw)) { matched = true; break; }
                    }
                }
                if (matched) break;
            }
            if (!matched) continue;
            String pid = product.getStr("id");
            if (pid != null && !added.contains(pid)) {
                goods.add(toGoodsCard(product));
                added.add(pid);
            }
        }
        return goods;
    }

    public JSONArray buildGoodsCardsByIds(List<String> ids) {
        JSONArray goods = new JSONArray();
        if (masterProducts == null) return goods;
        for (String id : ids) {
            JSONObject product = masterProducts.getJSONObject(id);
            if (product != null) {
                goods.add(toGoodsCard(product));
            }
        }
        return goods;
    }

    // ========================================================================
    //  智能商品图解析
    // ========================================================================

    public String resolveProductPicture(JSONObject product) {
        String productId = product.getStr("id");
        if (productId == null) return PRODUCT_FALLBACK_IMAGE;

        // 1. product.picture
        String pic = product.getStr("picture");
        if (pic != null && !pic.isBlank() && MockImageUtils.normalizeImageUrl(pic, null) != null) {
            return pic;
        }

        // 2. product.mainPictures[0]
        JSONArray mainPics = product.getJSONArray("mainPictures");
        if (mainPics != null && !mainPics.isEmpty()) {
            String mp = mainPics.getStr(0);
            if (mp != null && !mp.isBlank() && MockImageUtils.normalizeImageUrl(mp, null) != null) {
                return mp;
            }
        }

        // 3. product.pictures[0]
        JSONArray pics = product.getJSONArray("pictures");
        if (pics != null && !pics.isEmpty()) {
            String fp = pics.getStr(0);
            if (fp != null && !fp.isBlank() && MockImageUtils.normalizeImageUrl(fp, null) != null) {
                return fp;
            }
        }

        // 4. SKU 图片兜底
        if (masterSkus != null) {
            JSONArray skuIds = product.getJSONArray("skuIds");
            if (skuIds != null && !skuIds.isEmpty()) {
                for (int i = 0; i < skuIds.size(); i++) {
                    String skuId = skuIds.getStr(i);
                    JSONObject sku = masterSkus.getJSONObject(skuId);
                    if (sku != null) {
                        String skuPic = sku.getStr("picture");
                        if (skuPic != null && !skuPic.isBlank() && MockImageUtils.normalizeImageUrl(skuPic, null) != null) {
                            return skuPic;
                        }
                    }
                }
            }
        }

        return PRODUCT_FALLBACK_IMAGE;
    }

    // ========================================================================
    //  工具方法
    // ========================================================================

    private JSONObject getProductOr404(String id) {
        if (masterProducts != null && masterProducts.containsKey(id)) {
            return masterProducts.getJSONObject(id);
        }
        throw new NoSuchElementException("商品不存在: " + id);
    }

    private JSONObject getSkuOr404(String id) {
        if (masterSkus != null && masterSkus.containsKey(id)) {
            return masterSkus.getJSONObject(id);
        }
        throw new NoSuchElementException("SKU不存在: " + id);
    }

    private String resolveProductId(JSONObject sku) {
        String pid = sku.getStr("goodsId", "");
        if (pid.isEmpty()) pid = sku.getStr("productId", "");
        return pid;
    }

    private String safeText(String text, int maxChars) {
        if (text == null || text.isBlank()) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars);
    }

    private String[] resolveBrandByCategory(JSONArray categories, String topCategoryId) {
        Set<String> catNames = new LinkedHashSet<>();
        if (categories != null) {
            for (int i = 0; i < categories.size(); i++) {
                JSONObject cat = categories.getJSONObject(i);
                String name = cat.getStr("name", "");
                if (!name.isBlank()) catNames.add(name);
            }
        }

        String[][] rules = {
            {"1", "硕华品质", "数码","手机配件","3C数码","影音娱乐","电脑","智能","电子","办公设备"},
            {"2", "CZ永在", "个护","洗护","清洁","男士","浴室","纸品","干湿巾","家庭清洁"},
            {"3", "咏汉定制", "服饰","女式靴子","女式休闲鞋","女式运动鞋","T恤","polo","衬衫","外套","套装","裤子","裙装"},
            {"4", "ICCUG", "钱包","胸包","饰品","艺术藏品","箱包"},
            {"5", "永久", "运动","户外","健身","护具","垂钓","乐器","城市出行","运动护具","健身小器械","健身大器械","户外装备"},
            {"6", "传智自有品牌", "居家","办公文具","严选","家庭医疗","中医保健","车载用品","居家生活用品","滋补保健","个护电器","宠物","名酒馆","进口酒"},
            {"7", "DDAO", "手表","腕表","表带","配饰"},
            {"8", "黛儿", "收纳","家纺","床品","家具","沙发","锅具","浴室","毛巾","浴巾","餐厨","宠物食品","宠物用品","调味","米面","粮油","南北干货","方便食品","美食","厨房"}
        };

        for (String[] rule : rules) {
            for (String catName : catNames) {
                for (int k = 1; k < rule.length; k++) {
                    if (catName.contains(rule[k])) {
                        return new String[]{rule[0], rule[1]};
                    }
                }
            }
        }

        if ("1005000".equals(topCategoryId)) return new String[]{"8", "黛儿"};
        if ("1008000".equals(topCategoryId)) return new String[]{"2", "CZ永在"};
        if ("1010001".equals(topCategoryId)) return new String[]{"6", "传智自有品牌"};
        if ("1011000".equals(topCategoryId)) return new String[]{"3", "咏汉定制"};
        if ("1012001".equals(topCategoryId)) return new String[]{"5", "永久"};
        if ("1013001".equals(topCategoryId)) return new String[]{"2", "CZ永在"};
        if ("1014001".equals(topCategoryId)) return new String[]{"1", "硕华品质"};
        if ("1015000".equals(topCategoryId)) return new String[]{"6", "传智自有品牌"};
        if ("1016001".equals(topCategoryId)) return new String[]{"9", "母婴优选"};

        return new String[]{"6", "传智自有品牌"};
    }
}
