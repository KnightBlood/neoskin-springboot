package com.neoskin.event;

import java.util.List;
import java.util.Map;

import com.neoskin.db.PlayerRepository;
import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;
import com.neoskin.service.Plugin;

/**
 * 源项目 {@code app/Events/} 的 24 个事件（1 个抽象基类 + 23 个具体事件）。
 *
 * <p>与 Laravel 的差异：Laravel 用「传引用的数组」（{@code array &$menu}）让监听者
 * 原地修改；Java 没有引用传递，所以菜单/页头页脚/徽章事件持有<b>可变集合</b>
 * （{@link java.util.List}/{@link java.util.Map}），监听者直接改集合内容。</p>
 *
 * <p>事件只携带数据，不携带服务依赖；需要数据库的监听者由
 * {@link EventRuntime} 在派发时通过字符串事件参数传入。</p>
 */
public final class Events {

    private Events() {
    }

    // ------------------------------------------------------------ 菜单 / 路由

    /** {@code ConfigureAdminMenu}：{@code menu} 的键是分类名（admin）。 */
    public record ConfigureAdminMenu(Map<String, List<Map<String, Object>>> menu)
            implements Event {
    }

    /** {@code ConfigureExploreMenu}。 */
    public record ConfigureExploreMenu(Map<String, List<Map<String, Object>>> menu)
            implements Event {
    }

    /** {@code ConfigureUserMenu}。 */
    public record ConfigureUserMenu(Map<String, List<Map<String, Object>>> menu)
            implements Event {
    }

    /** {@code ConfigureRoutes}：{@code router} 是路由注册器（插件可加路由）。 */
    public record ConfigureRoutes(Object router) implements Event {
    }

    // ------------------------------------------------------------ 玩家

    /** {@code PlayerProfileUpdated}。 */
    public record PlayerProfileUpdated(PlayerRepository.PlayerRow player) implements Event {
    }

    /** {@code PlayerRetrieved}。 */
    public record PlayerRetrieved(PlayerRepository.PlayerRow player) implements Event {
    }

    /** {@code PlayerWasAdded}。 */
    public record PlayerWasAdded(PlayerRepository.PlayerRow player) implements Event {
    }

    /** {@code PlayerWasDeleted}。 */
    public record PlayerWasDeleted(String playerName) implements Event {
    }

    /** {@code PlayerWillBeAdded}。 */
    public record PlayerWillBeAdded(String playerName) implements Event {
    }

    /** {@code PlayerWillBeDeleted}。 */
    public record PlayerWillBeDeleted(PlayerRepository.PlayerRow player) implements Event {
    }

    // ------------------------------------------------------------ 插件生命周期

    /** {@code PluginBootFailed}。 */
    public record PluginBootFailed(Plugin plugin) implements Event {
    }

    /** {@code PluginWasDeleted}。 */
    public record PluginWasDeleted(Plugin plugin) implements Event {
    }

    /** {@code PluginWasDisabled}。 */
    public record PluginWasDisabled(Plugin plugin) implements Event {
    }

    /** {@code PluginWasEnabled}。 */
    public record PluginWasEnabled(Plugin plugin) implements Event {
    }

    // ------------------------------------------------------------ 渲染

    /** {@code RenderingBadges}：{@code badges} 是可变列表（元素为 text/color）。 */
    public record RenderingBadges(List<Map<String, Object>> badges) implements Event {
    }

    /** {@code RenderingFooter}：监听者向页面底部追加内容。 */
    public record RenderingFooter(List<String> contents) implements Event {

        /** {@code addContent()}。 */
        public void addContent(String content) {
            contents.add(content);
        }
    }

    /** {@code RenderingHeader}：监听者向页面头部追加内容。 */
    public record RenderingHeader(List<String> contents) implements Event {

        /** {@code addContent()}。 */
        public void addContent(String content) {
            contents.add(content);
        }
    }

    // ------------------------------------------------------------ 材质

    /** {@code TextureDeleting}。 */
    public record TextureDeleting(TextureRepository.Row texture) implements Event {
    }

    // ------------------------------------------------------------ 用户

    /** {@code UserAuthenticated}（{@code Illuminate\Auth\Events\Authenticated} 的桥）。 */
    public record UserAuthenticated(UserRepository.UserRow user) implements Event {
    }

    /** {@code UserLoggedIn}。 */
    public record UserLoggedIn(UserRepository.UserRow user) implements Event {
    }

    /** {@code UserProfileUpdated}。 */
    public record UserProfileUpdated(String type, UserRepository.UserRow user) implements Event {
    }

    /** {@code UserRegistered}。 */
    public record UserRegistered(UserRepository.UserRow user) implements Event {
    }

    /** {@code UserTryToLogin}。 */
    public record UserTryToLogin(String identification, String authType) implements Event {
    }
}
