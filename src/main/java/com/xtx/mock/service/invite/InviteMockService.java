package com.xtx.mock.service.invite;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.xtx.mock.store.RuntimeInviteStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * InviteMockService — 邀请模块业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>邀请码查找与校验</li>
 *   <li>自邀/重复绑定检查</li>
 *   <li>邀请绑定执行（写入绑定记录、发积分、更新统计）</li>
 *   <li>邀请记录可读名称补全 (enrichment)</li>
 * </ul>
 * <p>
 * 积分操作（读取/写入 runtimePoints）由调用方传入相关引用，
 * 因为积分模块不在 v1.8.5 提取范围内。
 */
@Slf4j
public class InviteMockService {

    private final RuntimeInviteStore store;

    public InviteMockService(RuntimeInviteStore store) {
        this.store = store;
    }

    /**
     * 补充邀请记录中的可读名称：如果 friendName 是技术 ID，从 runtimeUsers 查找真实昵称。
     */
    public void enrichInviteRecords(JSONObject inviteData, Map<String, JSONObject> runtimeUsers) {
        if (inviteData == null) return;
        JSONArray recs = inviteData.getJSONArray("records");
        if (recs == null) return;
        for (int i = 0; i < recs.size(); i++) {
            JSONObject rec = recs.getJSONObject(i);
            String fn = rec.getStr("friendName", "");
            if (fn.matches("^(user_|inv_|invite_b_|invitee_|invite_test_|pts_invitee_|dup_|mock-token|test_|persist_test_|\\d{10,}).*")) {
                String inviteeId = rec.getStr("inviteeId", "");
                if (!inviteeId.isEmpty()) {
                    JSONObject u = runtimeUsers.get(inviteeId);
                    if (u != null) {
                        String readable = u.getStr("nickname");
                        if (readable == null || readable.isEmpty()) readable = u.getStr("account");
                        if (readable != null && !readable.isEmpty()) rec.set("friendName", readable);
                        else rec.set("friendName", "好友用户");
                    } else {
                        rec.set("friendName", "好友用户");
                    }
                } else {
                    rec.set("friendName", "好友用户");
                }
            }
        }
    }

    /**
     * 根据邀请码查找邀请人。
     *
     * @param code 邀请码
     * @return inviterId，未找到返回 null
     */
    public String findInviterByCode(String code) {
        for (Map.Entry<String, JSONObject> entry : store.getInviteMap().entrySet()) {
            if (code.equals(entry.getValue().getStr("inviteCode"))) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 检查 invitee 是否已被任何用户绑定过。
     */
    public boolean isInviteeAlreadyBound(String inviteeId) {
        for (Map.Entry<String, JSONArray> entry : store.getBindingsMap().entrySet()) {
            for (int i = 0; i < entry.getValue().size(); i++) {
                JSONObject bind = entry.getValue().getJSONObject(i);
                if (inviteeId.equals(bind.getStr("inviteeId", ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 检查 inviter 是否已邀请过该 invitee。
     */
    public boolean isAlreadyInvitedBy(String inviterId, String inviteeId) {
        JSONArray bindings = store.getBindingsMap().get(inviterId);
        if (bindings != null) {
            for (int i = 0; i < bindings.size(); i++) {
                JSONObject bind = bindings.getJSONObject(i);
                if (inviteeId.equals(bind.getStr("inviteeId", ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 执行邀请绑定（不含积分操作）。
     * 用于 POST /member/invite/bind 端点。
     *
     * @return JSONObject with {inviterInvite, inviterPoints, inviteeReadableName, bindNow}
     */
    public JSONObject executeBind(String uid, String inviterId,
                                   Map<String, JSONObject> runtimeUsers,
                                   AtomicInteger featureCounter) {
        String bindNow = java.time.LocalDateTime.now().toString().replace('T', ' ').substring(0, 19);
        String inviteeReadableName = resolveReadableName(uid, runtimeUsers);

        // 1. 写入绑定记录
        JSONArray inviterBindings = store.getBindingsMap().computeIfAbsent(inviterId, k -> new JSONArray());
        inviterBindings.add(new JSONObject()
                .set("inviteeId", uid)
                .set("createdAt", bindNow));

        // 2. 更新邀请人统计数据
        JSONObject inviterInvite = store.getInvite(inviterId);
        inviterInvite.set("invitedCount", inviterInvite.getInt("invitedCount", 0) + 1);
        inviterInvite.set("rewardPoints", inviterInvite.getInt("rewardPoints", 0) + 120);
        String recordId = "invite_" + featureCounter.incrementAndGet();
        inviterInvite.getJSONArray("records").add(0, new JSONObject()
                .set("id", recordId)
                .set("friendName", inviteeReadableName)
                .set("status", "已注册")
                .set("rewardPoints", 120)
                .set("createdAt", bindNow));

        log.info("Invite binding: inviter={} invitee={} code={}", inviterId, uid,
                inviterInvite.getStr("inviteCode", ""));

        return new JSONObject()
                .set("inviterInvite", inviterInvite)
                .set("bindNow", bindNow);
    }

    /**
     * 注册桥接方法：处理注册时的邀请码绑定完整流程。
     * <p>
     * 包含：查找邀请人 → 自邀检查 → 重复绑定检查 → 写绑定记录 → 更新邀请统计。
     * 积分发放由调用方（MockController.register）通过 PointsMockService.award() 完成。
     *
     * @param inviteCode      注册时传入的邀请码
     * @param userId          新注册用户 ID
     * @param nickname        新注册用户昵称
     * @param account         新注册用户账号
     * @param runtimeUsers    MockController 的 runtimeUsers 引用
     * @param featureCounter  MockController 的 featureCounter 引用
     * @return InviteBindResult 包含是否绑定成功、inviterId 等，失败时包含错误响应
     */
    public InviteBindResult bindOnRegister(String inviteCode, String userId, String nickname, String account,
                                            Map<String, JSONObject> runtimeUsers,
                                            AtomicInteger featureCounter) {
        // 1. 查找邀请码
        String inviterId = findInviterByCode(inviteCode);
        if (inviterId == null) {
            // fallback: 从 userId hash 生成 code
            String generatedCode = store.buildCode(userId);
            if (inviteCode.equals(generatedCode)) {
                // 自邀将在下一步检查
                for (Map.Entry<String, JSONObject> entry : store.getInviteMap().entrySet()) {
                    if (inviteCode.equals(entry.getValue().getStr("inviteCode"))) {
                        inviterId = entry.getKey();
                        break;
                    }
                }
            }
        }

        if (inviterId == null) {
            return InviteBindResult.failure(40000, "邀请码无效");
        }

        // 2. 自邀检查
        if (inviterId.equals(userId)) {
            return InviteBindResult.failure(40000, "不能绑定自己的邀请码");
        }

        // 3. 重复绑定检查
        if (isInviteeAlreadyBound(userId)) {
            return InviteBindResult.failure(40900, "该用户已绑定邀请关系，不能重复领取奖励");
        }

        // 4. 执行绑定
        String bindNow = java.time.LocalDateTime.now().toString().replace('T', ' ').substring(0, 19);
        JSONArray inviterBinds = store.getBindingsMap().computeIfAbsent(inviterId, k -> new JSONArray());
        inviterBinds.add(new JSONObject().set("inviteeId", userId).set("createdAt", bindNow));
        store.getBindingsMap().computeIfAbsent(userId, k -> new JSONArray())
                .add(new JSONObject().set("inviterId", inviterId).set("createdAt", bindNow));

        // 5. 积分发放由调用方通过 PointsMockService.award() 完成

        // 6. 更新邀请统计
        JSONObject inviterInvite = store.getInvite(inviterId);
        if (inviterInvite != null) {
            inviterInvite.set("invitedCount", inviterInvite.getInt("invitedCount", 0) + 1);
            inviterInvite.set("rewardPoints", inviterInvite.getInt("rewardPoints", 0) + 120);
            inviterInvite.getJSONArray("records").add(0, new JSONObject()
                    .set("id", "invite_" + featureCounter.incrementAndGet())
                    .set("friendName", nickname != null ? nickname : account)
                    .set("status", "已注册")
                    .set("rewardPoints", 120)
                    .set("createdAt", bindNow));
        }

        // 6. 积分汇总由调用方在注册方法中触发
        // (MockController.register 会在 bindOnRegister 返回后调用 computePointsSummary + savePoints)

        log.info("Invite binding (register): inviter={} invitee={} code={}", inviterId, userId, inviteCode);
        return InviteBindResult.success(inviterId);
    }

    /**
     * 获取用户可读名称。
     */
    private String resolveReadableName(String uid, Map<String, JSONObject> runtimeUsers) {
        JSONObject userObj = runtimeUsers.get(uid);
        if (userObj != null) {
            String nickname = userObj.getStr("nickname");
            if (nickname != null && !nickname.isEmpty()) return nickname;
            String account = userObj.getStr("account");
            if (account != null && !account.isEmpty()) return account;
        }
        return "好友用户";
    }

    /**
     * 绑定结果封装。
     */
    public static class InviteBindResult {
        private final boolean success;
        private final String errorCode;
        private final String errorMsg;
        private final String inviterId;

        private InviteBindResult(boolean success, String errorCode, String errorMsg, String inviterId) {
            this.success = success;
            this.errorCode = errorCode;
            this.errorMsg = errorMsg;
            this.inviterId = inviterId;
        }

        public static InviteBindResult success(String inviterId) {
            return new InviteBindResult(true, null, null, inviterId);
        }

        public static InviteBindResult failure(int code, String msg) {
            return new InviteBindResult(false, String.valueOf(code), msg, null);
        }

        public boolean isSuccess() { return success; }
        public String getErrorCode() { return errorCode; }
        public String getErrorMsg() { return errorMsg; }
        public String getInviterId() { return inviterId; }
    }
}
