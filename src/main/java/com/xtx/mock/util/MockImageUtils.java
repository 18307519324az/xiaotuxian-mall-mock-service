package com.xtx.mock.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MockImageUtils — 图片工具类（无状态、纯函数）。
 * <p>
 * 职责：
 * <ul>
 *   <li>图片 URL 标准化（normalizeImageUrl）</li>
 *   <li>空图片兜底（DEFAULT_FALLBACK_IMAGE）</li>
 *   <li>类目 fallback 图片（CATEGORY_FALLBACK_IMAGES）</li>
 * </ul>
 * <p>
 * 本类所有方法均为纯函数，不依赖 Spring 容器，不读写 runtime 数据。
 * 从 {@code com.xtx.mock.controller.MockController} 提取而来。
 */
public final class MockImageUtils {

    private MockImageUtils() {
    }

    // ==================== 分类图片兜底 ====================

    private static final Map<String, String> CATEGORY_FALLBACK_IMAGES = new LinkedHashMap<>();

    static {
        CATEGORY_FALLBACK_IMAGES.put("居家",
                "http://yjy-xiaotuxian-dev.oss-cn-beijing.aliyuncs.com/picture/2021-05-06/201516e3-25d0-48f5-bcee-7f0cafb14176.png");
        CATEGORY_FALLBACK_IMAGES.put("美食",
                "http://yjy-xiaotuxian-dev.oss-cn-beijing.aliyuncs.com/picture/2021-04-22/7f6a7b20-7902-4b43-b9c5-f33151ef1334.jpg");
        CATEGORY_FALLBACK_IMAGES.put("服饰",
                "http://yjy-xiaotuxian-dev.oss-cn-beijing.aliyuncs.com/picture/2021-04-22/7f6a7b20-7902-4b43-b9c5-f33151ef1334.jpg");
        CATEGORY_FALLBACK_IMAGES.put("母婴",
                "http://yjy-xiaotuxian-dev.oss-cn-beijing.aliyuncs.com/picture/2021-04-22/7f6a7b20-7902-4b43-b9c5-f33151ef1334.jpg");
        CATEGORY_FALLBACK_IMAGES.put("个护",
                "http://yjy-xiaotuxian-dev.oss-cn-beijing.aliyuncs.com/picture/2021-04-22/7f6a7b20-7902-4b43-b9c5-f33151ef1334.jpg");
        CATEGORY_FALLBACK_IMAGES.put("严选",
                "http://yjy-xiaotuxian-dev.oss-cn-beijing.aliyuncs.com/picture/2021-04-22/7f6a7b20-7902-4b43-b9c5-f33151ef1334.jpg");
        CATEGORY_FALLBACK_IMAGES.put("数码",
                "http://yjy-xiaotuxian-dev.oss-cn-beijing.aliyuncs.com/picture/2021-04-22/7f6a7b20-7902-4b43-b9c5-f33151ef1334.jpg");
        CATEGORY_FALLBACK_IMAGES.put("运动",
                "http://yjy-xiaotuxian-dev.oss-cn-beijing.aliyuncs.com/picture/2021-04-22/7f6a7b20-7902-4b43-b9c5-f33151ef1334.jpg");
    }

    /**
     * 缺省图片 URL — 当所有兜底手段都无效时使用。
     */
    public static final String DEFAULT_FALLBACK_IMAGE =
            "http://yjy-xiaotuxian-dev.oss-cn-beijing.aliyuncs.com/picture/2021-04-22/7f6a7b20-7902-4b43-b9c5-f33151ef1334.jpg";

    /**
     * 规范化图片 URL，无效时返回 fallbackUrl。
     * <p>
     * 规则：
     * <ol>
     *   <li>null/blank → return fallbackUrl</li>
     *   <li>不以 http:// 或 https:// 开头 → return fallbackUrl</li>
     *   <li>包含 "placeholder" → return fallbackUrl</li>
     *   <li>以 ".svg" 结尾 → return fallbackUrl</li>
     *   <li>以 "data:" 开头 → return fallbackUrl</li>
     *   <li>其他 → return url 原样</li>
     * </ol>
     * <p>
     * 注意：不在此处做 CDN 级别的黑名单替换。不可达 CDN URL（如 yalixuan-item.nosdn.127.net）
     * 虽然无法加载，但前端 v-img-error 指令可以正常兜底，且避免商品图大面积使用同一绿色默认图。
     * 应用层应使用 resolveProductPicture 做更智能的图片解析。
     *
     * @param url         待校验的图片 URL
     * @param fallbackUrl 无效时返回的兜底 URL
     * @return 有效的图片 URL 或 fallbackUrl
     */
    public static String normalizeImageUrl(String url, String fallbackUrl) {
        if (url == null || url.isBlank()) return fallbackUrl;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return fallbackUrl;
        if (url.contains("placeholder")) return fallbackUrl;
        if (url.endsWith(".svg")) return fallbackUrl;
        if (url.startsWith("data:")) return fallbackUrl;
        return url;
    }

    /**
     * 按分类名称获取固定分类占位图。
     *
     * @param name 分类名称（如 "居家"、"美食"）
     * @return 该分类对应的占位图 URL，未知分类返回 DEFAULT_FALLBACK_IMAGE
     */
    public static String getCategoryFallbackImage(String name) {
        if (name == null) return DEFAULT_FALLBACK_IMAGE;
        return CATEGORY_FALLBACK_IMAGES.getOrDefault(name, DEFAULT_FALLBACK_IMAGE);
    }
}
