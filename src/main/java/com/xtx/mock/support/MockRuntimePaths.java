package com.xtx.mock.support;

/**
 * MockRuntimePaths — 统一管理运行时持久化文件路径。
 *
 * 职责：
 * 1. 集中定义所有 data/*-runtime.json 文件路径；
 * 2. 避免各 Store 分散硬编码路径；
 * 3. 修改根路径或文件名时只需改此处。
 *
 * 所有路径相对于 PROJECT_ROOT（System.getProperty("user.dir")），
 * 即 xtx-mock-service 模块根目录。
 */
public class MockRuntimePaths {

    private static final String DATA_DIR = "/data/";

    // ==================== 已持久化的运行时文件 ====================

    /** 用户档案 */
    public static final String USER_PROFILES = DATA_DIR + "user-profiles-runtime.json";

    /** 订单数据 */
    public static final String USER_ORDERS = DATA_DIR + "user-orders-runtime.json";

    /** 购物车数据 */
    public static final String USER_CARTS = DATA_DIR + "user-carts-runtime.json";

    /** 商品收藏 */
    public static final String USER_COLLECTS = DATA_DIR + "user-collects-runtime.json";

    /** 浏览历史 */
    public static final String USER_HISTORY = DATA_DIR + "user-history-runtime.json";

    /** 品牌关注 */
    public static final String USER_BRAND_FOLLOWS = DATA_DIR + "user-brand-follows-runtime.json";

    /** 专题收藏 */
    public static final String USER_TOPIC_COLLECTS = DATA_DIR + "user-topic-collects-runtime.json";

    /** 商品评价 */
    public static final String USER_GOODS_REVIEWS = DATA_DIR + "user-goods-reviews-runtime.json";

    /** 积分（含签到记录、邀请绑定） */
    public static final String USER_POINTS = DATA_DIR + "user-points-runtime.json";

    /** 客服聊天 */
    public static final String USER_CUSTOMER_SERVICE_CHATS = DATA_DIR + "user-customer-service-chats-runtime.json";

    /** 客服工单 */
    public static final String USER_CUSTOMER_SERVICE_TICKETS = DATA_DIR + "user-customer-service-tickets-runtime.json";

    // ==================== 尚未持久化的运行时文件（预留） ====================

    /** 优惠券 */
    public static final String USER_COUPONS = DATA_DIR + "user-coupons-runtime.json";

    /** 礼品卡 */
    public static final String USER_GIFT_CARDS = DATA_DIR + "user-gift-cards-runtime.json";

    /** 站内消息 */
    public static final String USER_MESSAGES = DATA_DIR + "user-messages-runtime.json";

    /** 评价/晒单 */
    public static final String USER_REVIEWS = DATA_DIR + "user-reviews-runtime.json";

    /** 售后申请 */
    public static final String USER_AFTER_SALES = DATA_DIR + "user-after-sales-runtime.json";

    /** 邀请信息 */
    public static final String USER_INVITE = DATA_DIR + "user-invite-runtime.json";

    /** 签到记录 */
    public static final String USER_SIGNIN_RECORDS = DATA_DIR + "user-signin-records-runtime.json";

    /** 邀请绑定记录 */
    public static final String USER_INVITE_BINDINGS = DATA_DIR + "user-invite-bindings-runtime.json";

    /** 抽奖数据 */
    public static final String USER_LOTTERY = DATA_DIR + "user-lottery-runtime.json";

    // ==================== 地址持久化 ====================

    /** 用户地址 */
    public static final String USER_ADDRESSES = DATA_DIR + "user-addresses-runtime.json";

    /** 库存变更日志 */
    public static final String STOCK_CHANGE_LOG = DATA_DIR + "stock-change-log-runtime.json";

    private MockRuntimePaths() {
        // utility class
    }

    /**
     * Returns the absolute path for a relative data path.
     * Resolves against the system property "user.dir".
     */
    public static String resolve(String relativePath) {
        String root = System.getProperty("user.dir", ".");
        return root + relativePath;
    }

    /**
     * Returns the absolute path for USER_CUSTOMER_SERVICE_CHATS.
     */
    public static String customerServiceChats() {
        return resolve(USER_CUSTOMER_SERVICE_CHATS);
    }

    /**
     * Returns the absolute path for USER_CUSTOMER_SERVICE_TICKETS.
     */
    public static String customerServiceTickets() {
        return resolve(USER_CUSTOMER_SERVICE_TICKETS);
    }
}
