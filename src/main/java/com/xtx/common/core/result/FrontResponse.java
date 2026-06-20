package com.xtx.common.core.result;

import com.xtx.common.core.util.TraceIdUtil;

public class FrontResponse<T> {

    private String code;
    private String msg;
    private String traceId;
    private T result;

    public FrontResponse() {
    }

    public FrontResponse(String code, String msg, String traceId, T result) {
        this.code = code;
        this.msg = msg;
        this.traceId = traceId;
        this.result = result;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public T getResult() {
        return result;
    }

    public void setResult(T result) {
        this.result = result;
    }

    public static <T> FrontResponse<T> success() {
        FrontResponse<T> response = new FrontResponse<>();
        response.setCode(String.valueOf(ResultCode.SUCCESS.getCode()));
        response.setMsg("success");
        response.setTraceId(TraceIdUtil.get());
        return response;
    }

    public static <T> FrontResponse<T> success(T result) {
        FrontResponse<T> response = success();
        response.setResult(result);
        return response;
    }

    public static <T> FrontResponse<T> failure(String msg) {
        FrontResponse<T> response = new FrontResponse<>();
        response.setCode(String.valueOf(ResultCode.INTERNAL_ERROR.getCode()));
        response.setMsg(msg);
        response.setTraceId(TraceIdUtil.get());
        return response;
    }

    public static <T> FrontResponse<T> failure(int code, String msg) {
        FrontResponse<T> response = new FrontResponse<>();
        response.setCode(String.valueOf(code));
        response.setMsg(msg);
        response.setTraceId(TraceIdUtil.get());
        return response;
    }

    public static <T> FrontResponse<T> fail(int code, String msg) {
        return failure(code, msg);
    }

    public static <T> FrontResponse<T> failure(ResultCode resultCode) {
        return failure(resultCode.getCode(), resultCode.getMessage());
    }
}
