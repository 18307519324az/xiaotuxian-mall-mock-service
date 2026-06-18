package com.xtx.mock.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeCustomerServiceStore;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * CustomerServiceMockService — 客服业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>聊天问答（关键词匹配 + 订单查询）</li>
 *   <li>工单 CRUD</li>
 *   <li>委托 RuntimeCustomerServiceStore 进行数据持久化</li>
 * </ul>
 * <p>
 * 不依赖 Spring，由 {@code com.xtx.mock.controller.MockCustomerServiceController} 直接实例化。
 */
@Slf4j
public class CustomerServiceMockService {

    private final RuntimeCustomerServiceStore store;

    /**
     * @param store 客服运行时存储
     */
    public CustomerServiceMockService(RuntimeCustomerServiceStore store) {
        this.store = store;
    }

    // ==================== Chat ====================

    /**
     * 处理客服问答。
     *
     * @param message       用户消息
     * @param uid           当前用户 ID
     * @param runtimeOrders 运行时订单数据 (from MockController), keyed by order ID
     * @return 包含 reply / suggestions / type 的响应
     */
    public JSONObject chat(String message, String uid, Map<String, JSONObject> runtimeOrders) {
        // 保存用户消息
        JSONObject userMsg = new JSONObject();
        userMsg.set("role", "user");
        userMsg.set("content", message);
        userMsg.set("time", LocalDateTime.now().toString());
        store.addChatMessage(uid, userMsg);

        // 匹配关键词
        JSONObject match = matchCustomerServiceKeyword(message, uid, runtimeOrders);

        // 保存助手回复
        JSONObject reply = new JSONObject();
        reply.set("role", "assistant");
        reply.set("content", match.getStr("reply"));
        reply.set("type", match.getStr("type"));
        reply.set("suggestions", match.getJSONArray("suggestions"));
        reply.set("time", LocalDateTime.now().toString());
        store.addChatMessage(uid, reply);

        store.saveChats();

        JSONObject result = new JSONObject();
        result.set("reply", match.getStr("reply"));
        result.set("suggestions", match.getJSONArray("suggestions"));
        result.set("type", match.getStr("type"));
        return result;
    }

    /**
     * 获取聊天历史。
     */
    public JSONArray getHistory(String uid) {
        return store.getChats(uid);
    }

    // ==================== Ticket ====================

    /**
     * 创建客服工单。
     *
     * @param uid     当前用户 ID
     * @param type    工单类型
     * @param content 工单内容
     * @param orderId 关联订单 ID（可为空）
     * @return 创建的工单对象
     */
    public JSONObject createTicket(String uid, String type, String content, String orderId) {
        int num = store.incrementAndGetTicketId();
        String ticketId = "ticket_" + num;

        JSONObject ticket = new JSONObject();
        ticket.set("id", ticketId);
        ticket.set("userId", uid);
        ticket.set("type", type);
        ticket.set("content", content);
        ticket.set("orderId", orderId);
        ticket.set("status", "处理中");
        ticket.set("createTime", LocalDateTime.now().toString());

        store.addTicket(uid, ticket);
        store.saveTickets();
        return ticket;
    }

    /**
     * 获取工单列表。
     */
    public JSONArray getTickets(String uid) {
        return store.getTickets(uid);
    }

    // ==================== Keyword Matching ====================

    /**
     * 客服关键词匹配 — 返回回复内容。
     */
    private JSONObject matchCustomerServiceKeyword(String message, String uid,
                                                    Map<String, JSONObject> runtimeOrders) {
        String lower = message != null ? message.toLowerCase() : "";

        // 订单查询
        if (lower.contains("订单") || lower.contains("我的订单") || lower.contains("什么时候发货") ||
            lower.contains("订单状态") || lower.contains("待发货") || lower.contains("待收货") ||
            lower.contains("待评价") || lower.contains("待付款")) {
            JSONArray userOrders = new JSONArray();
            for (Map.Entry<String, JSONObject> entry : runtimeOrders.entrySet()) {
                JSONObject order = entry.getValue();
                if (!isOrderOwner(order, uid)) continue;
                userOrders.add(order);
            }
            userOrders.sort((a, b) -> ((JSONObject) b).getStr("createTime", "")
                .compareTo(((JSONObject) a).getStr("createTime", "")));

            StringBuilder reply = new StringBuilder();
            int count = Math.min(3, userOrders.size());
            if (count > 0) {
                reply.append("您最近有 ").append(userOrders.size()).append(" 个订单：\n\n");
                for (int i = 0; i < count; i++) {
                    JSONObject o = (JSONObject) userOrders.get(i);
                    String stateStr = orderStateText(o.getInt("orderState", 0));
                    String price = o.getStr("totalPayPrice", o.getStr("payMoney", "0"));
                    reply.append(i + 1).append(". 订单 ").append(o.getStr("id"))
                          .append("，状态：").append(stateStr)
                          .append("，实付 ¥").append(price).append("\n");
                }
                JSONObject result = new JSONObject();
                result.set("reply", reply.toString());
                JSONArray suggestions = new JSONArray();
                suggestions.add("查看订单");
                suggestions.add("申请售后");
                suggestions.add("评价商品");
                result.set("suggestions", suggestions);
                result.set("type", "order");
                return result;
            } else {
                JSONObject result = new JSONObject();
                result.set("reply", "您目前还没有订单，可以去首页看看有什么喜欢的商品～");
                JSONArray suggestions = new JSONArray();
                suggestions.add("去首页逛逛");
                result.set("suggestions", suggestions);
                result.set("type", "faq");
                return result;
            }
        }

        // 发货/物流/快递
        if (lower.contains("发货") || lower.contains("物流") || lower.contains("快递") ||
            lower.contains("配送") || lower.contains("运输")) {
            JSONObject result = new JSONObject();
            result.set("reply", "订单支付成功后，我们会在 24-48 小时内安排发货。您可以在“我的订单-待发货”中查看订单状态，发货后会有物流单号更新。");
            JSONArray suggestions = new JSONArray();
            suggestions.add("查看订单");
            suggestions.add("申请售后");
            result.set("suggestions", suggestions);
            result.set("type", "faq");
            return result;
        }

        // 退款/退货/换货/售后
        if (lower.contains("退款") || lower.contains("退货") || lower.contains("换货") ||
            lower.contains("售后") || lower.contains("维修") || lower.contains("坏了") ||
            lower.contains("不满意") || lower.contains("退换")) {
            JSONObject result = new JSONObject();
            result.set("reply", "如需申请售后，请在“我的订单”中找到对应订单，点击“申请售后”按钮。我们支持 7 天无理由退货，质量问题 30 天内可换货。售后申请提交后，我们会在 1-3 个工作日内处理。");
            JSONArray suggestions = new JSONArray();
            suggestions.add("申请售后");
            suggestions.add("查看订单");
            result.set("suggestions", suggestions);
            result.set("type", "faq");
            return result;
        }

        // 优惠券/礼品卡/抵扣
        if (lower.contains("优惠券") || lower.contains("礼品卡") || lower.contains("抵扣") ||
            lower.contains("优惠") || lower.contains("满减") || lower.contains("折扣")) {
            JSONObject result = new JSONObject();
            result.set("reply", "您可以在结算页选择使用优惠券或礼品卡抵扣订单金额。优惠券可通过积分兑换或参与活动获取。礼品卡绑定后可在结算时选择使用。");
            JSONArray suggestions = new JSONArray();
            suggestions.add("查看优惠券");
            suggestions.add("绑定礼品卡");
            result.set("suggestions", suggestions);
            result.set("type", "faq");
            return result;
        }

        // 积分/抽奖
        if (lower.contains("积分") || lower.contains("抽奖") || lower.contains("兑换") ||
            lower.contains("签到") || lower.contains("等级")) {
            JSONObject result = new JSONObject();
            result.set("reply", "您可以在会员中心查看当前积分和可抽奖次数。每 30 积分可抽奖一次，积分可通过购物、评价商品等方式获得。");
            JSONArray suggestions = new JSONArray();
            suggestions.add("去抽奖");
            suggestions.add("查看积分");
            result.set("suggestions", suggestions);
            result.set("type", "faq");
            return result;
        }

        // 地址/收货地址
        if (lower.contains("地址") || lower.contains("收货地址") || lower.contains("修改地址") ||
            lower.contains("配送地址") || lower.contains("收件人")) {
            JSONObject result = new JSONObject();
            result.set("reply", "您可以在“地址管理”中新增、修改或删除收货地址。下单时可以选择已保存的地址作为收货地址。");
            JSONArray suggestions = new JSONArray();
            suggestions.add("管理地址");
            result.set("suggestions", suggestions);
            result.set("type", "faq");
            return result;
        }

        // 支付/付款
        if (lower.contains("支付") || lower.contains("付款") || lower.contains("没付款") ||
            lower.contains("未支付") || lower.contains("怎么付") || lower.contains("支付方式")) {
            JSONObject result = new JSONObject();
            result.set("reply", "我们支持在线支付（微信、支付宝）和货到付款。未支付的订单请在“待付款”中尽快完成支付，超时订单将自动取消。");
            JSONArray suggestions = new JSONArray();
            suggestions.add("查看待付款订单");
            suggestions.add("帮助中心");
            result.set("suggestions", suggestions);
            result.set("type", "faq");
            return result;
        }

        // 账号/密码
        if (lower.contains("账号") || lower.contains("密码") || lower.contains("登录") ||
            lower.contains("注册") || lower.contains("忘记") || lower.contains("手机号") ||
            lower.contains("修改密码")) {
            JSONObject result = new JSONObject();
            result.set("reply", "您可以在“安全设置”中修改密码。如忘记密码，可在登录页点击“忘记密码”通过手机号重置。账号相关问题可联系客服处理。");
            JSONArray suggestions = new JSONArray();
            suggestions.add("安全设置");
            suggestions.add("帮助中心");
            result.set("suggestions", suggestions);
            result.set("type", "faq");
            return result;
        }

        // 默认：无法回答 → 提示提交工单
        JSONObject result = new JSONObject();
        result.set("reply", "这个问题我暂时还无法准确回答，您可以提交客服工单，我们会尽快处理。");
        JSONArray suggestions = new JSONArray();
        suggestions.add("提交工单");
        result.set("suggestions", suggestions);
        result.set("type", "ticket_fallback");
        return result;
    }

    /**
     * 判断订单是否属于指定用户。
     */
    private boolean isOrderOwner(JSONObject order, String userId) {
        if (order == null) return false;
        String ownerId = order.getStr("userId", "");
        return userId.equals(ownerId);
    }

    /**
     * 订单状态文字。
     */
    private static String orderStateText(int state) {
        switch (state) {
            case 1: return "待付款";
            case 2: return "待发货";
            case 3: return "待收货";
            case 4: return "待评价";
            case 5: return "已完成";
            case 6: return "已取消";
            default: return "未知";
        }
    }
}
