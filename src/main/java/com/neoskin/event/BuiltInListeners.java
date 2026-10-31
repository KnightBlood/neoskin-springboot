package com.neoskin.event;

import javax.sql.DataSource;

import com.neoskin.db.TextureRepository;
import com.neoskin.event.listener.ResetPlayerForRemovedClosetItem;

/**
 * 内置监听者的默认注册（不需要外部路径/配置的那些）。
 *
 * <p>与源 {@code EventServiceProvider} 的映射差异：</p>
 * <ul>
 *   <li>{@code closet.removed → ResetPlayerForRemovedClosetItem}：默认注册，
 *       因为这是纯数据副作用（衣柜移除后玩家外观归零）；</li>
 *   <li>{@code texture.deleted / texture.privacy.updated → 三个监听者}：
 *       等价实现已经以 {@code TextureSideEffects} 直接调用（§37），
 *       默认<b>不</b>注册以避免重复执行；插件可通过字符串事件观察；</li>
 *   <li>{@code PluginWasEnabled / versionChanged → CopyPluginAssets /
 *       CleanUpFrontEndLocaleFiles}：需要 {@code AppPaths}，由
 *       {@code WebConfig} 在容器启动时注册；</li>
 *   <li>{@code auth.registration.completed → SendEmailVerification}：
 *       注册流程已直接发信（§37），默认不注册以免双发；</li>
 *   <li>{@code PluginBootFailed → NotifyFailedPlugin}：不执行 PHP 插件，
 *       正常路径不会触发，保留类不默认注册。</li>
 * </ul>
 */
public final class BuiltInListeners {

    private BuiltInListeners() {
    }

    public static void register(EventDispatcher dispatcher) {
        ResetPlayerForRemovedClosetItem reset = new ResetPlayerForRemovedClosetItem();
        dispatcher.listen("closet.removed", payload -> {
            DataSource dataSource = (DataSource) payload[0];
            TextureRepository.Row texture = (TextureRepository.Row) payload[1];
            int uid = ((Number) payload[2]).intValue();
            try {
                reset.handle(dataSource, texture, uid);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("closet.removed listener failed", e);
            }
        });
    }
}
