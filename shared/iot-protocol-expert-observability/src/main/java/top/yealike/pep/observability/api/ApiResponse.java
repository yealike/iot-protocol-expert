package top.yealike.pep.observability.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.Serializable;

/**
 * 三个 HTTP 服务共用的返回信封。
 *
 * <p>成功时 code 为 {@link #SUCCESS_CODE}，失败时 code 为稳定的业务错误码；HTTP 状态码仍然表达传输层结果。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 42L;
    public static final String SUCCESS_CODE = "OK";
    public static final String SUCCESS_MESSAGE = "Success";

    private String code;
    private String msg;
    private T data;

    public ApiResponse() {}

    public ApiResponse(String code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static ApiResponse<Void> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> failure(String code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }

    public static <T> ApiResponse<T> of(String code, String msg, T data) {
        return new ApiResponse<>(code, msg, data);
    }

    @JsonIgnore
    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
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

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
