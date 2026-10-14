package org.involens_be.dto.response;


import com.google.gson.annotations.Expose;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.util.Map;

@NoArgsConstructor
@Builder
@AllArgsConstructor
public class BaseResponse<T> {

    @Expose
    public int status;

    @Expose
    public Error error;

    @Expose
    public T data;

    public static <T> BaseResponse<T> ok(T data, String message) {
        Error err = Error.builder()
                .code("00")
                .message(message == null ? "Success" : message)
                .build();
        return BaseResponse.<T>builder().status(0).error(err).data(data).build();
    }

    public static <T> BaseResponse<T> ok(T data, String code, String message) {
        Error err = Error.builder().code(code).message(message).build();
        return BaseResponse.<T>builder().status(1).error(err).data(data).build();
    }

    public static <T> BaseResponse<T> error(T data, String code, String message) {
        Error err = Error.builder()
                .code(code)
                .message(message == null ? "Error" : message)
                .build();
        return BaseResponse.<T>builder().status(1).error(err).data(data).build();
    }

    public static <T> BaseResponse<T> success(T data) {
        return BaseResponse.<T>builder().status(0).data(data).build();
    }

    public static <T> BaseResponse<T> success(T data, String filename) {
        BaseResponse<T> response = BaseResponse.<T>builder()
                .status(0)
                .data(data)
                .build();
        return response;
    }

    @Builder
    public static class Error {

        @Expose
        public String code;

        @Expose
        public String message;
    }
}

