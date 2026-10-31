package com.neoskin.event.listener;

import java.sql.SQLException;

import javax.sql.DataSource;

import com.neoskin.db.TextureRepository;
import com.neoskin.service.Option;
import com.neoskin.service.TextureSideEffects;

/**
 * {@code texture.deleted} 监听者：退还存储分、扣回上传奖励
 * （源 {@code App\Listeners\UpdateScoreForDeletedTexture}）。
 *
 * <p>数据访问层目前通过 {@link TextureSideEffects} 直接调用（与源码
 * {@code texture.deleted} 等价）；本类供事件驱动路径与插件观察使用。</p>
 */
public final class UpdateScoreForDeletedTexture {

    /** 事件参数：{@code (DataSource, Option, TextureRepository.Row)}。 */
    public void handle(DataSource dataSource, Option option, TextureRepository.Row texture)
            throws SQLException {
        TextureSideEffects.refundScore(dataSource, option, texture);
    }
}
