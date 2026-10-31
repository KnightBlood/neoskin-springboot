package com.neoskin.web.middleware;

import java.util.Map;

import com.neoskin.db.UserRepository;
import com.neoskin.web.error.WebException;

import io.vertx.ext.web.RoutingContext;

/**
 * {@code App\Http\Middleware\CheckRole} 的移植（{@code role:*} 别名）。
 *
 * <pre>
 * protected $roles = [
 *     'banned' => User::BANNED,
 *     'normal' => User::NORMAL,
 *     'admin'  => User::ADMIN,
 *     'super-admin' => User::SUPER_ADMIN,
 * ];
 *
 * public function handle(Request $request, Closure $next, $role)
 * {
 *     $permission = $request->user()->permission;
 *     abort_if($permission &lt; $this-&gt;roles[$role], 403);
 *
 *     return $next($request);
 * }
 * </pre>
 *
 * <h2>两条 PHP 语义要留意</h2>
 * <ul>
 *   <li>{@code abort_if} 抛 {@code HttpException(403)}，<b>message 为空</b>：</li>
 *     <ul>
 *       <li>JSON 分支（非 debug）：{@code {"message": ""}}</li>
 *       <li>HTML 分支：渲染 {@code errors/403} 视图，
 *           {@code exception.message ?: trans('errors.http.msg-403')}</li>
 *     </ul>
 *   </li>
 *   <li>未登录到达此中间件时，PHP 对 {@code null->permission} 报 warning 且
 *       求值为 {@code null}，{@code null &lt; 阈值} 恒真 → 仍然 403。所以
 *       {@code user == null} 直接按 403 处理（Must 在 {@link Authenticate}
 *       之后执行，正常路径到不了这里匿名）。</li>
 * </ul>
 *
 * <p>未带参数裸写 {@code role} 的路由不存在；真发生了，PHP 侧是
 * {@code ArgumentCountError} → 500，这里照做。</p>
 */
public final class CheckRole implements ParameterizedHandler, io.vertx.core.Handler<RoutingContext> {

    /** {@code $roles}（源 {@code App\Models\User} 的四个常量）。 */
    private static final Map<String, Integer> ROLES = Map.of(
            "banned", UserRepository.BANNED,
            "normal", UserRepository.NORMAL,
            "admin", UserRepository.ADMIN,
            "super-admin", UserRepository.SUPER_ADMIN);

    /** 注册表用：裸调用等价 PHP 少参数 → 直接按 500 走异常出口。 */
    @Override
    public void handle(RoutingContext ctx) {
        ctx.fail(new IllegalStateException(
                "Too few arguments to function CheckRole::handle(): 2 passed, 3 expected"));
    }

    @Override
    public void handle(RoutingContext ctx, String params) {
        String role = params;

        UserRepository.UserRow user = Authenticate.currentUser(ctx);

        Integer threshold = role == null || role.isEmpty() ? null : ROLES.get(role);

        // PHP: $permission < $this->roles[$role]
        // （未登录 → 求值 null < 阈值 → true；角色名未知 → 阈值 null）
        int permission = user == null ? -1 : user.permission();
        boolean deny = threshold == null ? permission < 0 : permission < threshold;

        if (deny) {
            // abort_if(..., 403)：message 为空
            ctx.fail(WebException.http(403, null));
            return;
        }

        ctx.next();
    }
}
