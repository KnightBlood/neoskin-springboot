package com.neoskin.event.listener;

import java.sql.SQLException;
import java.util.function.Consumer;

import com.neoskin.db.UserRepository;

/**
 * {@code Illuminate\Auth\Events\Authenticated} 监听者：请求带 {@code lang} 时
 * 更新用户语言并切换应用语言（源 {@code App\Listeners\SetAppLocale}）。
 *
 * <p>Java 侧语言由 {@code DetectLanguagePrefer} 中间件按会话/请求协商；
 * 本类保留同样的语义，供事件驱动路径调用。</p>
 */
public final class SetAppLocale {

    public void handle(UserRepository users, UserRepository.UserRow user, String requestedLang,
            Consumer<String> applyLocale) throws SQLException {
        if (requestedLang != null && !requestedLang.isBlank()) {
            users.updateLocale(user.uid(), requestedLang);
            applyLocale.accept(requestedLang);

            return;
        }
        if (user.locale() != null && !user.locale().isBlank()) {
            applyLocale.accept(user.locale());
        }
    }
}
