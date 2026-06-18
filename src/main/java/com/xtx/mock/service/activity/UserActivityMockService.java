package com.xtx.mock.service.activity;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeUserActivityStore;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * UserActivityMockService — 用户行为（收藏/浏览历史）业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>查询收藏列表（分页，按 userId 隔离）</li>
 *   <li>添加/取消收藏</li>
 *   <li>查询浏览历史列表（分页，按 userId 隔离）</li>
 *   <li>添加/清空浏览历史</li>
 *   <li>商品信息补全（名称、图片、价格等）</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockUserActivityController} 直接实例化。
 */
@Slf4j
public class UserActivityMockService {

    private final RuntimeUserActivityStore store;

    public UserActivityMockService(RuntimeUserActivityStore store) {
        this.store = store;
    }

    /**
     * 获取底层 Store 引用。
     */
    public RuntimeUserActivityStore getStore() {
        return store;
    }

    // ==================== 收藏查询 ====================

    /**
     * 查询收藏列表（分页）。
     *
     * @param uid              用户 ID
     * @param page             页码
     * @param pageSize         每页条数
     * @param collectType      收藏类型
     * @param masterProducts   商品主数据
     * @param curatedDemoProducts 精选演示商品数据
     * @return 分页结果
     */
    public JSONObject queryCollectList(String uid, int page, int pageSize, int collectType,
                                        JSONObject masterProducts, JSONObject curatedDemoProducts) {
        Set<String> ids = store.getCollectsRaw(uid);
        if (ids == null) ids = new java.util.HashSet<>();

        List<String> idList = new ArrayList<>(ids);
        // 按添加时间倒序
        java.util.Collections.reverse(idList);

        int total = idList.size();
        int from = (page - 1) * pageSize;
        int to = Math.min(from + pageSize, total);
        if (from >= total) {
            JSONObject empty = new JSONObject();
            empty.set("page", page);
            empty.set("pageSize", pageSize);
            empty.set("counts", total);
            empty.set("items", new JSONArray());
            return empty;
        }
        List<String> pageIds = idList.subList(from, to);

        JSONArray items = new JSONArray();
        for (String pid : pageIds) {
            JSONObject product = findProductById(pid, masterProducts, curatedDemoProducts);
            if (product == null) continue;
            JSONObject card = toGoodsCard(product);
            card.set("collectType", collectType);
            items.add(card);
        }

        JSONObject result = new JSONObject();
        result.set("page", page);
        result.set("pageSize", pageSize);
        result.set("counts", total);
        result.set("items", items);
        return result;
    }

    // ==================== 收藏操作 ====================

    /**
     * 添加收藏。
     */
    public boolean addCollect(String uid, String goodsId) {
        boolean added = store.addCollect(uid, goodsId);
        store.saveCollects();
        return added;
    }

    /**
     * 取消收藏（按单个 goodsId）。
     */
    public boolean removeCollectByGoodsId(String uid, String goodsId) {
        boolean removed = store.removeCollect(uid, goodsId);
        store.saveCollects();
        return removed;
    }

    /**
     * 取消收藏（按 JSON 数组 ids）。
     */
    public void removeCollectByIds(String uid, String idsJson) {
        Set<String> userCollects = store.getCollectsRaw(uid);
        if (userCollects == null) return;
        try {
            JSONArray idArr = cn.hutool.json.JSONUtil.parseArray(idsJson);
            for (int i = 0; i < idArr.size(); i++) {
                userCollects.remove(idArr.getStr(i));
            }
        } catch (Exception e) {
            userCollects.remove(idsJson);
        }
        store.saveCollects();
    }

    // ==================== 浏览历史查询 ====================

    /**
     * 查询浏览历史列表（分页）。
     *
     * @param uid              用户 ID
     * @param page             页码
     * @param pageSize         每页条数
     * @param masterProducts   商品主数据
     * @param curatedDemoProducts 精选演示商品数据
     * @return 分页结果
     */
    public JSONObject queryHistoryList(String uid, int page, int pageSize,
                                        JSONObject masterProducts, JSONObject curatedDemoProducts) {
        List<String> ids = store.getHistoryRaw(uid);
        if (ids == null) ids = new ArrayList<>();

        int total = ids.size();
        int from = (page - 1) * pageSize;
        int to = Math.min(from + pageSize, total);
        if (from >= total) {
            JSONObject empty = new JSONObject();
            empty.set("page", page);
            empty.set("pageSize", pageSize);
            empty.set("counts", total);
            empty.set("items", new JSONArray());
            return empty;
        }
        List<String> pageIds = ids.subList(from, to);

        JSONArray items = new JSONArray();
        for (String pid : pageIds) {
            JSONObject product = findProductById(pid, masterProducts, curatedDemoProducts);
            if (product == null) continue;
            JSONObject card = toGoodsCard(product);
            card.set("viewTime", LocalDateTime.now().toString());
            items.add(card);
        }

        JSONObject result = new JSONObject();
        result.set("page", page);
        result.set("pageSize", pageSize);
        result.set("counts", total);
        result.set("items", items);
        return result;
    }

    // ==================== 浏览历史操作 ====================

    /**
     * 添加浏览历史（去重，最新在前，限 100 条）。
     */
    public void addHistory(String uid, String goodsId) {
        store.addHistory(uid, goodsId);
        store.saveHistory();
    }

    /**
     * 清空浏览历史。
     */
    public void clearHistory(String uid) {
        store.clearHistory(uid);
        store.saveHistory();
    }

    // ==================== 种子数据 ====================

    /**
     * Seed 默认收藏：为演示用户预置商品。
     */
    public void seedDefaultCollects(String userId, JSONObject curatedDemoProducts, JSONObject masterProducts) {
        if (store.getCollectsRaw(userId) != null && !store.getCollectsRaw(userId).isEmpty()) return;
        Set<String> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (String key : curatedDemoProducts.keySet()) {
            if (ids.size() >= 6) break;
            JSONObject curated = curatedDemoProducts.getJSONObject(key);
            String targetId = curated != null ? curated.getStr("targetGoodsId") : null;
            if (targetId != null && !targetId.isBlank() && masterProducts.containsKey(targetId)) {
                ids.add(targetId);
            } else {
                ids.add(key);
            }
        }
        if (ids.isEmpty()) {
            for (String key : masterProducts.keySet()) {
                if (ids.size() >= 6) break;
                Object val = masterProducts.get(key);
                if (!(val instanceof JSONObject)) continue;
                JSONObject product = (JSONObject) val;
                String name = product.getStr("name");
                String picture = product.getStr("picture");
                double price = 0;
                try { price = Double.parseDouble(product.getStr("price", "0")); } catch (Exception ignored) {}
                if (name == null || name.isBlank() || picture == null || picture.isBlank() || price <= 0) continue;
                ids.add(key);
            }
        }
        // Add to store
        for (String id : ids) {
            store.getCollects(userId).add(id);
        }
        log.info("Seeded default collects: {} items for user {}", ids.size(), userId);
    }

    /**
     * Seed 默认浏览历史：为演示用户预置商品。
     */
    public void seedDefaultHistory(String userId, JSONObject curatedDemoProducts, JSONObject masterProducts) {
        if (store.getHistoryRaw(userId) != null && !store.getHistoryRaw(userId).isEmpty()) return;
        List<String> items = new ArrayList<>();
        for (String key : curatedDemoProducts.keySet()) {
            if (items.size() >= 6) break;
            JSONObject curated = curatedDemoProducts.getJSONObject(key);
            String targetId = curated != null ? curated.getStr("targetGoodsId") : null;
            if (targetId != null && !targetId.isBlank() && masterProducts.containsKey(targetId)) {
                items.add(targetId);
            } else {
                items.add(key);
            }
        }
        if (items.isEmpty()) {
            for (String key : masterProducts.keySet()) {
                if (items.size() >= 6) break;
                Object val = masterProducts.get(key);
                if (!(val instanceof JSONObject)) continue;
                JSONObject product = (JSONObject) val;
                String name = product.getStr("name");
                String picture = product.getStr("picture");
                double price = 0;
                try { price = Double.parseDouble(product.getStr("price", "0")); } catch (Exception ignored) {}
                if (name == null || name.isBlank() || picture == null || picture.isBlank() || price <= 0) continue;
                items.add(key);
            }
        }
        store.getHistory(userId).addAll(items);
        log.info("Seeded default history: {} items for user {}", items.size(), userId);
    }

    /**
     * Seed 默认专题收藏：为演示用户预置 1 个专题。
     */
    public void seedDefaultTopicCollects(String userId) {
        if (store.getTopicCollectsRaw(userId) != null && !store.getTopicCollectsRaw(userId).isEmpty()) return;
        store.getTopicCollects(userId).add("1482381924796334084");
        log.info("Seeded default topic collect for user {}", userId);
    }

    // ==================== 私有工具方法 ====================

    /**
     * 根据 ID 查找商品（兼容 curated 商品和 masterProducts）。
     */
    private JSONObject findProductById(String id, JSONObject masterProducts, JSONObject curatedDemoProducts) {
        Object curated = curatedDemoProducts.get(id);
        if (curated instanceof JSONObject) {
            String targetId = ((JSONObject) curated).getStr("targetGoodsId");
            if (targetId != null && !targetId.isBlank() && masterProducts.containsKey(targetId)) {
                return masterProducts.getJSONObject(targetId);
            }
            return (JSONObject) curated;
        }
        if (masterProducts.containsKey(id)) {
            return masterProducts.getJSONObject(id);
        }
        return null;
    }

    /**
     * 截取字符串，最多保留 maxLen 个字符（中文字符算 1 个）。
     */
    private String safeText(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen);
    }

    /**
     * 将商品数据转换为卡片格式（与 MockController.toGoodsCard 保持相同结构）。
     */
    private JSONObject toGoodsCard(JSONObject product) {
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
        card.set("evaluateNum", product.getInt("commentCount", 0));
        try {
            long idNum = Long.parseLong(product.getStr("id", "0"));
            long daysOffset = idNum % 365;
            card.set("publishTime", "2026-" + String.format("%02d", 1 + (int)(daysOffset / 30))
                + "-" + String.format("%02d", 1 + (int)(daysOffset % 30)) + " 10:00:00");
        } catch (NumberFormatException e) {
            card.set("publishTime", "2026-01-15 10:00:00");
        }
        card.set("inventory", product.getInt("inventory", 0));
        card.set("oldPrice", product.getStr("oldPrice", product.getStr("price", "0")));
        // Brand mapping
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

    /**
     * 解析商品图片 URL。
     */
    private String resolveProductPicture(JSONObject product) {
        String productId = product.getStr("id");
        if (productId == null) return "https://yanxuan-item.nosdn.127.net/1de2e6e2b2e3e2c7b5c8a8a8a8a8a8a8.png";
        String pic = product.getStr("picture");
        if (pic != null && !pic.isBlank()) return pic;
        JSONArray mainPics = product.getJSONArray("mainPictures");
        if (mainPics != null && !mainPics.isEmpty()) {
            String mp = mainPics.getStr(0);
            if (mp != null && !mp.isBlank()) return mp;
        }
        return "https://yanxuan-item.nosdn.127.net/1de2e6e2b2e3e2c7b5c8a8a8a8a8a8a8.png";
    }

    /**
     * 根据商品分类解析品牌归属。
     */
    private String[] resolveBrandByCategory(JSONArray categories, String topCategoryId) {
        if (topCategoryId != null && !topCategoryId.isBlank()) {
            switch (topCategoryId) {
                case "1015000": return new String[]{"1", "硕华品质"};
                case "1015001": return new String[]{"2", "CZ永在"};
                case "1015002": return new String[]{"3", "咏汉定制"};
                case "1015003": return new String[]{"4", "ICCUG"};
                case "1015004": return new String[]{"5", "永久"};
                case "1015005": return new String[]{"6", "传智自有品牌"};
                case "1015006": return new String[]{"7", "默认品牌"};
                case "1015007": return new String[]{"8", "萌宠优选"};
                case "1015008": return new String[]{"9", "数码先锋"};
            }
        }
        if (categories != null && !categories.isEmpty()) {
            for (int i = 0; i < categories.size(); i++) {
                String catId = categories.getJSONObject(i).getStr("id");
                if (catId != null) {
                    switch (catId) {
                        case "1015000": return new String[]{"1", "硕华品质"};
                        case "1015001": return new String[]{"2", "CZ永在"};
                        case "1015002": return new String[]{"3", "咏汉定制"};
                        case "1015003": return new String[]{"4", "ICCUG"};
                        case "1015004": return new String[]{"5", "永久"};
                        case "1015005": return new String[]{"6", "传智自有品牌"};
                        case "1015006": return new String[]{"7", "默认品牌"};
                        case "1015007": return new String[]{"8", "萌宠优选"};
                        case "1015008": return new String[]{"9", "数码先锋"};
                    }
                }
            }
        }
        return new String[]{"7", "默认品牌"};
    }
}
