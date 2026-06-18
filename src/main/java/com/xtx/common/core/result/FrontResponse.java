package com.xtx.common.core.result;

/**
 * 前端兼容响应结果封装。
 * 与前端约定的统一响应格式：{ msg: "成功", result: <data> }
 */
public class FrontResponse<T> {

    private String code;
    private String msg;
    private String traceId;
    private T result;

    public FrontResponse() {}

    public FrontResponse(String code, String msg, String traceId, T result) {
        this.code = code;
        this.msg = msg;
        this.traceId = traceId;
        this.result = result;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public T getResult() { return result; }
    public void setResult(T result) { this.result = result; }

    /** 操作成功，带返回结果。 */
    public static <T> FrontResponse<T> success(T result) {
        FrontResponse<T> resp = new FrontResponse<>();
        resp.setCode("10000");
        resp.setMsg("成功");
        resp.setResult(result);
        return resp;
    }

    /** 操作失败，带错误提示信息。 */
    public static <T> FrontResponse<T> failure(String msg) {
        FrontResponse<T> resp = new FrontResponse<>();
        resp.setCode("50000");
        resp.setMsg(msg);
        return resp;
    }

    /** 操作失败，带状态码和错误提示信息。 */
    public static <T> FrontResponse<T> failure(int code, String msg) {
        FrontResponse<T> resp = new FrontResponse<>();
        resp.setCode(String.valueOf(code));
        resp.setMsg(msg);
        return resp;
    }
}
