package com.neoskin.event.listener;

import java.sql.SQLException;

import javax.sql.DataSource;

import com.neoskin.db.TextureRepository;
import com.neoskin.service.Option;
import com.neoskin.service.TextureSideEffects;

/**
 * {@code texture.deleted} / {@code texture.privacy.updated} 监听者：把该材质从
 * 其他用户衣柜移除并退分（源 {@code App\Listeners\CleanUpCloset}）。
 */
public final class CleanUpCloset {

    /** 删除场景：材质已不存在（不再调整 likes 计数）。 */
    public void handle(DataSource dataSource, Option option, TextureRepository.Row texture)
            throws SQLException {
        TextureSideEffects.cleanUpCloset(dataSource, option, texture, false);
    }

    /** 隐私切换场景：材质仍存在（likes 计数随之减少）。 */
    public void handlePrivacy(DataSource dataSource, Option option,
            TextureRepository.Row texture) throws SQLException {
        TextureSideEffects.cleanUpCloset(dataSource, option, texture, true);
    }
}
