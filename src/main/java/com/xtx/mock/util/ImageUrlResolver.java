package com.xtx.mock.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 图片 URL 解析器
 * <p>
 * 按照业务类型生成确定性图片 URL，确保：
 * 1. 同一业务类型的图片使用相同的 seed 前缀
 * 2. 图片 URL 可预测、可维护
 * 3. 不同业务类型之间不会混淆
 * </p>
 *
 * 业务类型对照：
 * <ul>
 *   <li>HOME_BANNER —— 首页轮播图（1240×500）</li>
 *   <li>BRAND_LOGO —— 品牌 Logo（120×120）</li>
 *   <li>BRAND_PICTURE —— 品牌展示大图（240×305）</li>
 *   <li>CATEGORY_ICON —— 分类图标（120×120）</li>
 *   <li>CATEGORY_PICTURE —— 分类展示图（240×240）</li>
 *   <li>GOODS_MAIN —— 商品主图（800×800）</li>
 *   <li>GOODS_DETAIL —— 商品详情图（800×800）</li>
 *   <li>GOODS_LIST —— 商品列表缩略图（240×240）</li>
 *   <li>SKU_PICTURE —— SKU 规格图片（400×400）</li>
 *   <li>SPECIAL_COVER —— 专题封面（404×288）</li>
 *   <li>GOODS_COVER —— 商品板块封面（240×610）</li>
 * </ul>
 */
public class ImageUrlResolver {

    private ImageUrlResolver() {
    }

    /** 业务类型 → 图片尺寸映射 */
    private static final Map<String, String> TYPE_SIZE = Map.ofEntries(
            Map.entry("HOME_BANNER", "1240/500"),
            Map.entry("BRAND_LOGO", "120/120"),
            Map.entry("BRAND_PICTURE", "240/305"),
            Map.entry("CATEGORY_ICON", "120/120"),
            Map.entry("CATEGORY_PICTURE", "240/240"),
            Map.entry("GOODS_MAIN", "800/800"),
            Map.entry("GOODS_DETAIL", "800/800"),
            Map.entry("GOODS_LIST", "240/240"),
            Map.entry("SKU_PICTURE", "400/400"),
            Map.entry("SPECIAL_COVER", "404/288"),
            Map.entry("GOODS_COVER", "240/610"),
            Map.entry("HOME_NEW", "306/306"),
            Map.entry("HOME_HOT", "306/306"),
            Map.entry("HOME_CATEGORY_GOODS", "220/220"),
            Map.entry("AVATAR", "100/100"),
            Map.entry("EVALUATE_PICTURE", "400/400")
    );

    /** 每个业务类型的计数器，保证同一类型内不重复 */
    private static final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * 生成指定业务类型、指定索引的图片 URL
     *
     * @param type  业务类型（如 HOME_BANNER、BRAND_PICTURE 等）
     * @param index 图片序号（从 1 开始）
     * @return 确定性图片 URL
     */
    public static String url(String type, int index) {
        String size = TYPE_SIZE.getOrDefault(type, "400/400");
        return "https://picsum.photos/seed/" + type.toLowerCase() + "_" + index + "/" + size;
    }

    /**
     * 生成指定业务类型、指定自定义 seed 的图片 URL
     *
     * @param type 业务类型
     * @param seed 自定义种子（如商品 ID）
     * @return 确定性图片 URL
     */
    public static String url(String type, String seed) {
        String size = TYPE_SIZE.getOrDefault(type, "400/400");
        return "https://picsum.photos/seed/" + type.toLowerCase() + "_" + seed + "/" + size;
    }

    /**
     * 生成指定业务类型的下一张图片 URL（自动递增序号）
     *
     * @param type 业务类型
     * @return 确定性图片 URL
     */
    public static String nextUrl(String type) {
        AtomicInteger counter = counters.computeIfAbsent(type, k -> new AtomicInteger(1));
        return url(type, counter.getAndIncrement());
    }

    /**
     * 批量生成指定业务类型的多个图片 URL
     *
     * @param type  业务类型
     * @param count 生成数量
     * @return 图片 URL 数组
     */
    public static String[] batchUrl(String type, int count) {
        String[] urls = new String[count];
        for (int i = 0; i < count; i++) {
            urls[i] = url(type, i + 1);
        }
        return urls;
    }
}
