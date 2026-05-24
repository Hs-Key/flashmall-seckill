package com.flashmall.common.exception;

import com.flashmall.common.enums.ResultCode;
import lombok.Getter;

/**
 * 业务异常
 * 用于在业务逻辑中抛出可预期的错误（如库存不足、活动已结束等）
 * 由 GlobalExceptionHandler 统一捕获并返回给前端
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.FAIL.getCode();
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
