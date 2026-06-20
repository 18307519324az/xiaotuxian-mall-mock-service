package com.xtx.mock.service.auth;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xtx.mock.service.invite.InviteMockService;
import com.xtx.mock.service.points.PointsMockService;
import com.xtx.mock.store.RuntimePointsStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * AuthMockService — Auth Core 业务逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>用户注册（含邀请码绑定、积分发放）</li>
 *   <li>用户登录（凭据校验 + token 生成）</li>
 *   <li>用户资料读取/修改</li>
 *   <li>密码修改</li>
 *   <li>退出登录（token 移除）</li>
 *   <li>手机号绑定/修改</li>
 * </ul>
 * <p>
 * 数据源引用（由 MockController @PostConstruct 注入）：
 * <ul>
 *   <li>runtimeUsers — 用户 profiles 内存 Map</li>
 *   <li>runtimeTokens — token → userId 内存 Map</li>
 *   <li>saveUsersCallback — 持久化回调</li>
 *   <li>inviteService / pointsService / pointsStore — 邀请积分联动</li>
 * </ul>
 */
@Slf4j
public class AuthMockService {

    // ==================== 依赖（由外部通过 setter 注入） ====================

    private Map<String, JSONObject> runtimeUsers;
    private Map<String, String> runtimeTokens;
    private Runnable saveUsersCallback;
    private InviteMockService inviteService;
    private PointsMockService pointsService;
    private RuntimePointsStore pointsStore;
    private AtomicInteger featureCounter;
    private Consumer<String> initNewUserCallback;

    // ==================== Setter 注入 ====================

    public void setRuntimeUsers(Map<String, JSONObject> runtimeUsers) { this.runtimeUsers = runtimeUsers; }
    public void setRuntimeTokens(Map<String, String> runtimeTokens) { this.runtimeTokens = runtimeTokens; }
    public void setSaveUsersCallback(Runnable callback) { this.saveUsersCallback = callback; }
    public void setInviteService(InviteMockService inviteService) { this.inviteService = inviteService; }
    public void setPointsService(PointsMockService pointsService) { this.pointsService = pointsService; }
    public void setPointsStore(RuntimePointsStore pointsStore) { this.pointsStore = pointsStore; }
    public void setFeatureCounter(AtomicInteger featureCounter) { this.featureCounter = featureCounter; }
    public void setInitNewUserCallback(Consumer<String> callback) { this.initNewUserCallback = callback; }

    // ==================== Auth Core 业务方法 ====================

    /**
     * POST /member/register — 注册新用户。
     *
     * @return 成功时返回包含 id/account/nickname/avatar/mobile/token/inviteBound 的 JSONObject
     * @throws IllegalArgumentException 校验失败或业务规则冲突
     */
    public JSONObject register(Map<String, Object> params) {
        String account = params.get("account") != null ? params.get("account").toString().trim() : "";
        String password = params.get("password") != null ? params.get("password").toString() : "";
        String mobile = params.get("mobile") != null ? params.get("mobile").toString() : "";
        String nickname = params.get("nickname") != null ? params.get("nickname").toString() : account;
        String inviteCode = params.get("inviteCode") != null ? params.get("inviteCode").toString().trim() : "";

        if (account.isEmpty() || password.isEmpty()) {
            throw new IllegalArgumentException("账号和密码不能为空");
        }
        if (password.length() < 6) {
            throw new IllegalArgumentException("密码至少6个字符");
        }

        // 查重
        for (JSONObject user : runtimeUsers.values()) {
            if (user.getStr("account").equals(account)) {
                throw new IllegalArgumentException("用户名已存在");
            }
        }

        String userId = "user_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        JSONObject user = new JSONObject();
        user.set("id", userId);
        user.set("account", account);
        user.set("password", password);
        user.set("nickname", nickname);
        user.set("avatar", "https://picsum.photos/seed/" + userId + "/100/100");
        user.set("mobile", mobile.isEmpty() ? "" : mobile);
        user.set("gender", null);
        user.set("birthday", "");
        user.set("profession", "");
        runtimeUsers.put(userId, user);
        if (saveUsersCallback != null) saveUsersCallback.run();

        // 处理邀请码绑定
        boolean inviteBound = false;
        String inviterId = null;
        if (!inviteCode.isEmpty()) {
            InviteMockService.InviteBindResult bindResult = inviteService.bindOnRegister(
                    inviteCode, userId, nickname, account,
                    runtimeUsers, featureCounter);
            if (bindResult.isSuccess()) {
                inviterId = bindResult.getInviterId();
                inviteBound = true;
                // 发放奖励积分
                pointsService.award(inviterId, 120, "邀请好友注册奖励", "invite", 90, featureCounter);
                pointsService.award(userId, 20, "新人注册奖励", "invite", 30, featureCounter);
                // 重新计算积分汇总
                JSONObject invPts = pointsStore.getPointsRaw(inviterId);
                if (invPts != null) pointsService.computeSummary(invPts);
                JSONObject uPts = pointsStore.getPointsRaw(userId);
                if (uPts != null) pointsService.computeSummary(uPts);
                pointsStore.save();
                log.info("Invite binding: inviter={} invitee={} code={}", inviterId, userId, inviteCode);
            } else {
                String errCode = bindResult.getErrorCode();
                String errMsg = bindResult.getErrorMsg();
                if ("40000".equals(errCode)) {
                    throw new AuthServiceException(40000, errMsg);
                } else if ("40900".equals(errCode)) {
                    throw new AuthServiceException(40900, errMsg);
                } else {
                    throw new IllegalArgumentException(errMsg);
                }
            }
        }

        // 新用户初始化最小数据
        if (initNewUserCallback != null) initNewUserCallback.accept(userId);

        // 生成 token
        String token = "mock-token-" + java.util.UUID.randomUUID().toString();
        runtimeTokens.put(token, userId);

        JSONObject result = new JSONObject();
        result.set("id", userId);
        result.set("account", account);
        result.set("nickname", nickname);
        result.set("avatar", user.getStr("avatar"));
        result.set("mobile", user.getStr("mobile"));
        result.set("token", token);
        result.set("inviteBound", inviteBound);
        if (inviteBound && inviterId != null) {
            result.set("inviterId", inviterId);
        }
        return result;
    }

    /**
     * POST /login — 真实凭据校验。
     */
    public JSONObject login(Map<String, Object> params) {
        String account = params.get("account") != null ? params.get("account").toString().trim() : "";
        String password = params.get("password") != null ? params.get("password").toString() : "";

        if (account.isEmpty() || password.isEmpty()) {
            throw new IllegalArgumentException("账号和密码不能为空");
        }

        // 查找用户
        JSONObject foundUser = null;
        for (JSONObject user : runtimeUsers.values()) {
            if (user.getStr("account").equals(account)) {
                foundUser = user;
                break;
            }
        }
        if (foundUser == null) {
            throw new IllegalArgumentException("账号不存在");
        }

        String storedPwd = foundUser.getStr("password");
        if (!password.equals(storedPwd)) {
            throw new IllegalArgumentException("密码错误");
        }

        // 生成 token
        String token = "mock-token-" + java.util.UUID.randomUUID().toString();
        runtimeTokens.put(token, foundUser.getStr("id"));

        JSONObject result = new JSONObject();
        result.set("id", foundUser.getStr("id"));
        result.set("account", foundUser.getStr("account"));
        result.set("nickname", foundUser.getStr("nickname"));
        result.set("avatar", foundUser.getStr("avatar"));
        result.set("mobile", foundUser.getStr("mobile"));
        result.set("token", token);
        return result;
    }

    /**
     * GET /member/profile — 获取用户资料（不含密码）。
     */
    public JSONObject getProfile(String userId) {
        JSONObject user = runtimeUsers.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        JSONObject safe = JSONUtil.parseObj(user.toString());
        safe.remove("password");
        return safe;
    }

    /**
     * PUT /member/profile — 更新用户资料。
     */
    public JSONObject updateProfile(String userId, Map<String, Object> params) {
        JSONObject user = runtimeUsers.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (params.containsKey("nickname")) user.set("nickname", params.get("nickname"));
        if (params.containsKey("gender")) user.set("gender", params.get("gender"));
        if (params.containsKey("birthday")) user.set("birthday", params.get("birthday"));
        if (params.containsKey("profession")) user.set("profession", params.get("profession"));
        if (params.containsKey("avatar")) user.set("avatar", params.get("avatar"));
        if (saveUsersCallback != null) saveUsersCallback.run();

        JSONObject safe = JSONUtil.parseObj(user.toString());
        safe.remove("password");
        return safe;
    }

    /**
     * PUT /member/password — 修改密码。
     */
    public void changePassword(String userId, String oldPassword, String newPassword) {
        JSONObject user = runtimeUsers.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (!user.getStr("password").equals(oldPassword)) {
            throw new IllegalArgumentException("原密码错误");
        }
        if (newPassword.length() < 6) {
            throw new IllegalArgumentException("新密码至少6个字符");
        }
        user.set("password", newPassword);
        if (saveUsersCallback != null) saveUsersCallback.run();
    }

    /**
     * POST /member/logout — 退出登录，移除 token。
     */
    public void logout(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            if (!token.isEmpty()) {
                runtimeTokens.remove(token);
            }
        }
    }

    /**
     * PUT /member/mobile — 绑定/修改手机号。
     */
    public JSONObject bindMobile(String userId, String mobile) {
        JSONObject user = runtimeUsers.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (mobile.isEmpty()) {
            throw new IllegalArgumentException("手机号不能为空");
        }
        if (!mobile.matches("\\d{11}")) {
            throw new IllegalArgumentException("手机号格式不正确");
        }
        user.set("mobile", mobile);
        if (saveUsersCallback != null) saveUsersCallback.run();

        JSONObject safe = JSONUtil.parseObj(user.toString());
        safe.remove("password");
        return safe;
    }

    // ==================== 内部类型 ====================

    /**
     * Auth 业务异常，携带错误码。
     */
    public static class AuthServiceException extends RuntimeException {
        private final int code;
        public AuthServiceException(int code, String message) {
            super(message);
            this.code = code;
        }
        public int getCode() { return code; }
    }

    /**
     * 从 Authorization header 提取 userId。
     */
    public String getUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) return null;
        return runtimeTokens.get(token);
    }

    /**
     * 要求请求必须携带有效 token，否则抛出 401。
     */
    public String requireUserId(String authHeader) {
        String userId = getUserIdFromToken(authHeader);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }
}
