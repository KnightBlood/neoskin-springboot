package com.neoskin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.AppPaths;
import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.ClosetRepository;
import com.neoskin.db.PlayerRepository;
import com.neoskin.db.SchemaInstaller;
import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@link TextureSideEffects} 的单元测试（对应源站三个监听器）。
 */
class TextureSideEffectsTest {

    @TempDir
    Path dir;

    private HikariDataSource ds;
    private Option option;
    private UserRepository users;
    private PlayerRepository players;
    private ClosetRepository closet;
    private TextureRepository textures;

    private int uploader;
    private int other;

    @BeforeEach
    void setUp() throws Exception {
        Path file = dir.resolve("t.sqlite");
        Files.createFile(file);
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(1);
        ds = new HikariDataSource(cfg);

        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getDatabase().setConnection("sqlite");
        settings.getDatabase().setName(file.toAbsolutePath().toString());
        settings.getStorage().setRoot(dir.resolve("storage").toString());
        SchemaInstaller schema = new SchemaInstaller(ds, settings);
        schema.install();
        AppPaths paths = new AppPaths(dir, settings);
        Files.createDirectories(paths.storage());

        option = new Option(ds, schema, paths);
        option.set("return_score", "true");
        option.set("take_back_scores_after_deletion", "true");
        option.set("score_per_storage", "10");
        option.set("private_score_per_storage", "20");
        option.set("score_award_per_texture", "5");
        option.set("score_per_closet_item", "3");

        users = new UserRepository(ds);
        players = new PlayerRepository(ds);
        closet = new ClosetRepository(ds);
        textures = new TextureRepository(ds);

        uploader = users.insert("up@a.com", "uploader", 1000, "x", "127.0.0.1", "Asia/Shanghai");
        other = users.insert("o@a.com", "other", 500, "x", "127.0.0.1", "Asia/Shanghai");
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    private TextureRepository.Row texture(boolean pub, int likes) throws Exception {
        long tid = textures.insert("t", "steve", "hash-" + likes + "-" + pub, 100, uploader,
                pub, "2026-01-01 00:00:00");
        textures.updateLikes(tid, likes);

        return textures.findByTid(tid);
    }

    @Test
    void onDeletedRefundsScoreResetsPlayersAndCleansCloset() throws Exception {
        TextureRepository.Row texture = texture(true, 2);
        int pid = players.insert(other, "otherplayer");
        players.updateTextures(pid, (int) texture.tid(), 0, "2026-01-01 00:00:00");
        closet.attach(other, texture.tid(), "saved");

        TextureSideEffects.onDeleted(ds, option, texture);

        // 100 * 10 - 5（score_award_per_texture）
        assertEquals(1000 + 100 * 10 - 5, users.findById(uploader).score());
        assertEquals(0, players.findByPid(pid).tidSkin());
        assertFalse(closet.exists(other, texture.tid()));
        assertEquals(500 + 3, users.findById(other).score());
    }

    @Test
    void onPrivacyUpdatedResetsOtherPlayersButKeepsUploader() throws Exception {
        TextureRepository.Row texture = texture(true, 3);
        int otherPlayer = players.insert(other, "otherplayer");
        int ownerPlayer = players.insert(uploader, "ownerplayer");
        players.updateTextures(otherPlayer, (int) texture.tid(), 0, "2026-01-01 00:00:00");
        players.updateTextures(ownerPlayer, (int) texture.tid(), 0, "2026-01-01 00:00:00");
        closet.attach(other, texture.tid(), "saved");

        TextureRepository.Row updated = new TextureRepository.Row(texture.tid(), texture.name(),
                texture.type(), texture.hash(), texture.size(), texture.uploader(), false,
                texture.uploadAt(), texture.likes());
        TextureSideEffects.onPrivacyUpdated(ds, option, updated);

        // 他人（非上传者）的玩家被重置，上传者自己的不受影响
        assertEquals(0, players.findByPid(otherPlayer).tidSkin());
        assertEquals(texture.tid(), players.findByPid(ownerPlayer).tidSkin());
        assertFalse(closet.exists(other, texture.tid()));
        assertEquals(3 - 1, textures.findByTid(texture.tid()).likes());
        assertEquals(500 + 3, users.findById(other).score());
        // 积分由 privacy() 自己处理，监听器不动上传者分数
        assertEquals(1000, users.findById(uploader).score());
    }

    @Test
    void onPrivacyUpdatedToPublicDoesNothing() throws Exception {
        TextureRepository.Row texture = texture(true, 1);
        int pid = players.insert(other, "otherplayer");
        players.updateTextures(pid, (int) texture.tid(), 0, "2026-01-01 00:00:00");
        closet.attach(other, texture.tid(), "saved");

        TextureSideEffects.onPrivacyUpdated(ds, option, texture);

        assertEquals(texture.tid(), players.findByPid(pid).tidSkin());
        assertTrue(closet.exists(other, texture.tid()));
        assertEquals(1, textures.findByTid(texture.tid()).likes());
    }

    @Test
    void capeTextureResetsCapeColumnOnly() throws Exception {
        long tid = textures.insert("cape", "cape", "hash-cape", 100, uploader, true,
                "2026-01-01 00:00:00");
        TextureRepository.Row texture = textures.findByTid(tid);
        int pid = players.insert(other, "otherplayer");
        players.updateTextures(pid, 0, (int) tid, "2026-01-01 00:00:00");

        TextureSideEffects.onDeleted(ds, option, texture);

        assertEquals(0, players.findByPid(pid).tidCape());
    }
}
