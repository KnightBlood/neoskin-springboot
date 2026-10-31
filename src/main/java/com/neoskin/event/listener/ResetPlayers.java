package com.neoskin.event.listener;

import java.sql.SQLException;

import javax.sql.DataSource;

import com.neoskin.db.TextureRepository;
import com.neoskin.service.TextureSideEffects;

/**
 * {@code texture.deleted} / {@code texture.privacy.updated} 监听者：把使用该材质的
 * 玩家外观归零（源 {@code App\Listeners\ResetPlayers}）。
 */
public final class ResetPlayers {

    /** 删除场景（{@code excludeUid = null}）。 */
    public void handle(DataSource dataSource, TextureRepository.Row texture) throws SQLException {
        TextureSideEffects.resetPlayers(dataSource, texture, null);
    }

    /** 隐私切换场景：上传者自己的玩家不受影响。 */
    public void handle(DataSource dataSource, TextureRepository.Row texture, Integer excludeUid)
            throws SQLException {
        TextureSideEffects.resetPlayers(dataSource, texture, excludeUid);
    }
}
