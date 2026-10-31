package com.neoskin.web.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.neoskin.db.UserRepository;
import com.neoskin.event.EventDispatcher;
import com.neoskin.event.EventRuntime;
import com.neoskin.event.Events;
import com.neoskin.service.cache.ArrayCache;
import com.neoskin.service.session.CacheBasedSessionHandler;
import com.neoskin.web.i18n.TranslationFileLoader;
import com.neoskin.web.i18n.Translator;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code FireUserAuthenticated} 中间件：认证通过后派发 {@code UserAuthenticated}
 * （源 {@code Middleware\FireUserAuthenticated}）。
 */
class FireUserAuthenticatedTest {

    @AfterEach
    void reset() {
        EventRuntime.set(null);
    }

    @Test
    void dispatchesUserAuthenticatedForLoggedInUser() {
        EventDispatcher dispatcher = new EventDispatcher();
        List<String> fired = new ArrayList<>();
        dispatcher.listen(Events.UserAuthenticated.class,
                event -> fired.add(event.user().email()));
        EventRuntime.set(dispatcher);

        Map<String, Handler<RoutingContext>> registry = MiddlewareChain.connectValueLayer(
                new HashMap<>(), null,
                new CacheBasedSessionHandler(new ArrayCache(), 120),
                null, "6.0.11", null,
                new Translator(new TranslationFileLoader(), "zh_CN", "en"),
                mock(UserRepository.class));
        Handler<RoutingContext> handler =
                registry.get("App\\Http\\Middleware\\FireUserAuthenticated");

        RoutingContext ctx = mock(RoutingContext.class);
        when(ctx.get(Authenticate.CURRENT_USER_KEY)).thenReturn(
                new UserRepository.UserRow(1, "a@b.c", "nick", "x", 0, 0, 0, true, false, ""));

        handler.handle(ctx);

        verify(ctx).next();
        assertEquals(List.of("a@b.c"), fired);
    }

    @Test
    void doesNothingWhenNoUserInContext() {
        EventDispatcher dispatcher = new EventDispatcher();
        List<Object> fired = new ArrayList<>();
        dispatcher.listen(Events.UserAuthenticated.class, event -> fired.add(event));
        EventRuntime.set(dispatcher);

        Map<String, Handler<RoutingContext>> registry = MiddlewareChain.connectValueLayer(
                new HashMap<>(), null,
                new CacheBasedSessionHandler(new ArrayCache(), 120),
                null, "6.0.11", null,
                new Translator(new TranslationFileLoader(), "zh_CN", "en"),
                mock(UserRepository.class));
        Handler<RoutingContext> handler =
                registry.get("App\\Http\\Middleware\\FireUserAuthenticated");

        RoutingContext ctx = mock(RoutingContext.class);
        handler.handle(ctx);

        verify(ctx).next();
        assertEquals(List.of(), fired);
    }
}
