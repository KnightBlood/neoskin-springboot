package com.neoskin.support;

/**
 * PHP 的 {@code ErrorException}：error handler 把 warning/notice 转成异常。
 *
 * <p>Laravel 用全局 {@code HandleExceptions} 中间件 {@code set_error_handler}，把
 * {@code unserialize()} / {@code openssl_decrypt()} 的 warning 变成这个异常——
 * 所以「解析失败」在应用层不是返回值，而是异常，消息里带着
 * {@code unserialize(): Error at offset N of M bytes}。</p>
 */
public class ErrorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ErrorException(String message) {
        super(message);
    }
}
