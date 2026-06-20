package com.xtx.mock.support;

import com.xtx.common.core.result.FrontResponse;
import java.util.Map;

/**
 * MockUserResolver — 统一用户身份解析。
 *
 * 职责：
 * 1. 从 Authorization header 解析 Bearer token；
 * 2. 根据 runtimeTokens 映射获取 userId；
 * 3. 无 token / token 无效 / token 格式错误 → 返回 40100；
 * 4. 不 fallback 到 DEFAULT_USER_ID。
 *
 * 设计原则：
 * - 纯函数，不持有状态；
 * - runtimeTokens 由调用方传入（来源可以是 Controller 的字段或全局 StoreManager）；
 * - 拆分过程中不修改现有 MockController 的调用方式。
 *
 * 使用方式：
 * <pre>
 *   String userId = MockUserResolver.resolve(authHeader, runtimeTokens);
 *   if (userId == null) {
 *       return FrontResponse.fail(40100, "请先登录");
 *   }
 * </pre>
 */
public class MockUserResolver {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int UNAUTHORIZED_CODE = 40100;
    private static final String UNAUTHORIZED_MSG = "请先登录";

    private MockUserResolver() {
        // utility class
    }

    /**
     * Resolves userId from the Authorization header.
     *
     * @param authHeader   the raw Authorization header value (may be null)
     * @param tokenStore   the token → userId map (typically runtimeTokens)
     * @return userId if valid, null if unauthorized
     */
    public static String resolve(String authHeader, Map<String, String> tokenStore) {
        if (authHeader == null || authHeader.isEmpty()) {
            return null;
        }
        String token;
        if (authHeader.startsWith(BEARER_PREFIX)) {
            token = authHeader.substring(BEARER_PREFIX.length()).trim();
        } else {
            // Allow raw token without "Bearer " prefix for backward compatibility
            token = authHeader.trim();
        }
        if (token.isEmpty()) {
            return null;
        }
        return tokenStore.get(token);
    }

    /**
     * Resolves userId and returns a 40100 FrontResponse if unauthorized.
     *
     * @param authHeader   the raw Authorization header value
     * @param tokenStore   the token → userId map
     * @return a pair: first element is userId (or null), second is error response (or null)
     */
    public static ResolveResult resolveOrFail(String authHeader, Map<String, String> tokenStore) {
        String userId = resolve(authHeader, tokenStore);
        if (userId == null) {
            return new ResolveResult(null, FrontResponse.failure(UNAUTHORIZED_CODE, UNAUTHORIZED_MSG));
        }
        return new ResolveResult(userId, null);
    }

    /**
     * Result container for resolveOrFail.
     */
    public static class ResolveResult {
        private final String userId;
        private final FrontResponse<?> errorResponse;

        public ResolveResult(String userId, FrontResponse<?> errorResponse) {
            this.userId = userId;
            this.errorResponse = errorResponse;
        }

        public String getUserId() { return userId; }
        public FrontResponse<?> getErrorResponse() { return errorResponse; }
        public boolean isUnauthorized() { return userId == null; }
    }
}
