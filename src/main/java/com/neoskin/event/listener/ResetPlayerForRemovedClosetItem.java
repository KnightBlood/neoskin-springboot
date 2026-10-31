package com.neoskin.event.listener;

import java.sql.SQLException;

import javax.sql.DataSource;

import com.neoskin.db.PlayerRepository;
import com.neoskin.db.TextureRepository;

/**
 * {@code closet.removed} 监听者：用户把材质移出衣柜后，其玩家的对应外观归零
 * （源 {@code App\Listeners\ResetPlayerForRemovedClosetItem}）。
 */
public final class ResetPlayerForRemovedClosetItem {

    public void handle(DataSource dataSource, TextureRepository.Row texture, int uid)
            throws SQLException {
        String column = "cape".equals(texture.type()) ? "tid_cape" : "tid_skin";
        new PlayerRepository(dataSource).resetTextureForUser(uid, texture.tid(), column);
    }
}
