package com.xtx.mock.service.preference;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * UserPreferenceMockService — 用户偏好（品牌关注/专题收藏）业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>品牌关注管理：runtimeBrandFollows 内存态 Map + 文件持久化</li>
 *   <li>专题收藏管理：通过函数引用委托到 RuntimeUserActivityStore</li>
 *   <li>品牌/专题数据 enrichment + 分页响应构建</li>
 * </ul>
 * <p>
 * 数据源由 MockController 在 {@code @PostConstruct} 中通过 setter 注入。
 */
@Slf4j
public class UserPreferenceMockService {

    // ==================== 品牌关注数据 ====================

    /** 运行时品牌关注 (文件持久化), keyed by userId → Set of brandId */
    private final Map<String, Set<String>> runtimeBrandFollows = new ConcurrentHashMap<>();

    /** 品牌关注持久化文件路径（由 MockController 注入） */
    private String brandFollowRuntimeFile;

    /** 默认用户 ID（用于 seed） */
    private String defaultUserId;

    // ==================== Enrichment 数据 ====================

    /** 品牌主数据（来自 home-brand.json） */
    private JSONArray homeBrandData;

    /** 专题主数据（来自 home-special.json） */
    private JSONArray homeSpecialData;

    // ==================== 专题收藏函数引用（由 MockController 注入） ====================

    private Function<String, Set<String>> topicCollectGetter;
    private BiConsumer<String, String> topicCollectAdder;
    private BiConsumer<String, String> topicCollectRemover;
    private Runnable topicCollectSaver;

    // ==================== Setter 注入 ====================

    public void setBrandFollowRuntimeFile(String v) { this.brandFollowRuntimeFile = v; }
    public void setDefaultUserId(String v) { this.defaultUserId = v; }
    public void setHomeBrandData(JSONArray v) { this.homeBrandData = v; }
    public void setHomeSpecialData(JSONArray v) { this.homeSpecialData = v; }
    public void setTopicCollectGetter(Function<String, Set<String>> v) { this.topicCollectGetter = v; }
    public void setTopicCollectAdder(BiConsumer<String, String> v) { this.topicCollectAdder = v; }
    public void setTopicCollectRemover(BiConsumer<String, String> v) { this.topicCollectRemover = v; }
    public void setTopicCollectSaver(Runnable v) { this.topicCollectSaver = v; }

    // ========================================================================
    //  品牌关注
    // ========================================================================

    /**
     * 获取指定用户的品牌关注 ID 集合。首次访问创建空集合。
     */
    public Set<String> getBrandFollowIds(String uid) {
        return runtimeBrandFollows.computeIfAbsent(uid, k -> new LinkedHashSet<>());
    }

    /**
     * 添加品牌关注。
     */
    public boolean addBrandFollow(String uid, String brandId) {
        Set<String> ids = getBrandFollowIds(uid);
        boolean added = ids.add(brandId);
        if (added) {
            saveBrandFollows();
        }
        return added;
    }

    /**
     * 取消品牌关注。
     */
    public boolean removeBrandFollow(String uid, String brandId) {
        Set<String> ids = runtimeBrandFollows.get(uid);
        if (ids == null) return false;
        boolean removed = ids.remove(brandId);
        if (removed) {
            saveBrandFollows();
        }
        return removed;
    }

    /**
     * 构建品牌关注分页列表响应。
     * 从 followed brand IDs → 匹配 brand 主数据 → enrichment → 分页。
     */
    public JSONObject buildBrandFollowList(String uid, int page, int pageSize) {
        Set<String> followedIds = getBrandFollowIds(uid);
        JSONArray allItems = new JSONArray();
        for (String id : followedIds) {
            JSONObject brand = findBrandById(id);
            if (brand != null) {
                JSONObject item = new JSONObject();
                item.set("id", brand.getStr("id"));
                item.set("name", brand.getStr("name"));
                String logo = brand.getStr("logo");
                String picture = brand.getStr("picture");
                item.set("logo", logo);
                item.set("picture", picture != null ? picture : logo);
                item.set("desc", brand.getStr("desc"));
                item.set("place", brand.getStr("place"));
                item.set("serviceTags", new JSONArray());
                allItems.add(item);
            }
        }
        return buildPageResult(allItems, page, pageSize);
    }

    /**
     * 检查品牌是否存在。
     */
    public boolean brandExists(String brandId) {
        return findBrandById(brandId) != null;
    }

    private JSONObject findBrandById(String id) {
        if (homeBrandData == null) return null;
        for (Object o : homeBrandData) {
            JSONObject b = (JSONObject) o;
            if (id.equals(b.getStr("id"))) return b;
        }
        return null;
    }

    // ========================================================================
    //  专题收藏
    // ========================================================================

    /**
     * 获取指定用户的专题收藏 ID 集合（委托到 RuntimeUserActivityStore）。
     */
    public Set<String> getTopicCollectIds(String uid) {
        if (topicCollectGetter != null) {
            return topicCollectGetter.apply(uid);
        }
        return new LinkedHashSet<>();
    }

    /**
     * 添加专题收藏（委托到 RuntimeUserActivityStore）。
     */
    public boolean addTopicCollect(String uid, String topicId) {
        if (topicCollectAdder != null) {
            topicCollectAdder.accept(uid, topicId);
            if (topicCollectSaver != null) {
                topicCollectSaver.run();
            }
            return true;
        }
        return false;
    }

    /**
     * 取消专题收藏（委托到 RuntimeUserActivityStore）。
     */
    public boolean removeTopicCollect(String uid, String topicId) {
        if (topicCollectRemover != null) {
            topicCollectRemover.accept(uid, topicId);
            if (topicCollectSaver != null) {
                topicCollectSaver.run();
            }
            return true;
        }
        return false;
    }

    /**
     * 构建专题收藏分页列表响应。
     * 从 collected topic IDs → 匹配 special 主数据 → enrichment → 分页。
     */
    public JSONObject buildTopicCollectList(String uid, int page, int pageSize) {
        Set<String> collectedIds = getTopicCollectIds(uid);
        JSONArray allItems = new JSONArray();
        for (String id : collectedIds) {
            JSONObject topic = findTopicById(id);
            if (topic != null) {
                JSONObject item = new JSONObject();
                item.set("id", topic.getStr("id"));
                item.set("title", topic.getStr("title"));
                item.set("summary", topic.getStr("summary"));
                item.set("cover", topic.getStr("cover"));
                item.set("collectNum", topic.getInt("collectNum", 0));
                item.set("viewNum", topic.getInt("viewNum", 0));
                item.set("replyNum", topic.getInt("replyNum", 0));
                allItems.add(item);
            }
        }
        return buildPageResult(allItems, page, pageSize);
    }

    private JSONObject findTopicById(String id) {
        if (homeSpecialData == null) return null;
        for (Object o : homeSpecialData) {
            JSONObject t = (JSONObject) o;
            if (id.equals(t.getStr("id"))) return t;
        }
        return null;
    }

    // ========================================================================
    //  品牌关注持久化
    // ========================================================================

    /**
     * 从文件加载品牌关注数据。
     */
    public void loadBrandFollowsFromFile() {
        if (brandFollowRuntimeFile == null) return;
        File f = new File(brandFollowRuntimeFile);
        if (!f.exists() || f.length() <= 0) return;
        try {
            String content = cn.hutool.core.io.file.FileReader.create(f).readString();
            JSONObject obj = JSONUtil.parseObj(content);
            runtimeBrandFollows.clear();
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                JSONArray arr = (JSONArray) entry.getValue();
                Set<String> ids = new LinkedHashSet<>();
                for (int i = 0; i < arr.size(); i++) {
                    ids.add(arr.getStr(i));
                }
                runtimeBrandFollows.put(entry.getKey(), ids);
            }
            log.info("Loaded brand follows from file: {} users", runtimeBrandFollows.size());
        } catch (Exception e) {
            log.error("Failed to load brand follows from file", e);
            runtimeBrandFollows.clear();
        }
    }

    /**
     * 将品牌关注数据持久化到文件。
     */
    public synchronized void saveBrandFollows() {
        if (brandFollowRuntimeFile == null) return;
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, Set<String>> entry : runtimeBrandFollows.entrySet()) {
                JSONArray arr = new JSONArray();
                for (String id : entry.getValue()) {
                    arr.add(id);
                }
                obj.set(entry.getKey(), arr);
            }
            String json = obj.toJSONString(2);
            String tmpFile = brandFollowRuntimeFile + ".tmp";
            cn.hutool.core.io.file.FileWriter writer = new cn.hutool.core.io.file.FileWriter(tmpFile);
            writer.write(json);
            File tmp = new File(tmpFile);
            File target = new File(brandFollowRuntimeFile);
            if (target.exists()) target.delete();
            tmp.renameTo(target);
        } catch (Exception e) {
            log.error("保存品牌关注失败", e);
        }
    }

    /**
     * Seed 默认用户的品牌关注数据。
     */
    public void seedDefaultBrandFollows() {
        if (defaultUserId == null) return;
        if (runtimeBrandFollows.containsKey(defaultUserId)) return;
        Set<String> ids = new LinkedHashSet<>();
        ids.add("1");
        runtimeBrandFollows.put(defaultUserId, ids);
        log.info("Seeded default brand follows for user: {}", defaultUserId);
    }

    /**
     * 获取完整的品牌关注 Map。
     */
    public Map<String, Set<String>> getBrandFollowsMap() {
        return runtimeBrandFollows;
    }

    /**
     * 检查品牌关注 Map 是否为空。
     */
    public boolean isBrandFollowsEmpty() {
        return runtimeBrandFollows.isEmpty();
    }

    /**
     * 移除指定用户的所有品牌关注。
     */
    public void removeUserBrandFollows(String userId) {
        runtimeBrandFollows.remove(userId);
    }

    // ========================================================================
    //  工具方法
    // ========================================================================

    /**
     * 构建分页结果对象。
     */
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
}
