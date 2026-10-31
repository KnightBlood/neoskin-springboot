package com.neoskin.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.neoskin.config.NeoSkinSettings;
import com.neoskin.db.SchemaInstaller;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@code closet.removed} 内置监听者：衣柜移除后玩家外观归零
 * （源 {@code ResetPlayerForRemovedClosetItem}）。
 */
class ClosetRemovedListenerTest {

    @TempDir
    Path dir;

    private HikariDataSource dataSource;

    @AfterEach
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
        EventRuntime.set(null);
    }

    @Test
    void removingClosetItemResetsPlayersUsingIt() throws Exception {
        dataSource = dataSource(dir.resolve("t.sqlite"));
        NeoSkinSettings settings = new NeoSkinSettings();
        settings.getDatabase().setConnection("sqlite");
        new SchemaInstaller(dataSource, settings).install();

        int tid = insertTexture();
        int uid = insertUser();
        insertPlayer(uid, tid);

        var texture = new com.neoskin.db.TextureRepository(dataSource).findByTid(tid);
        EventRuntime.fire("closet.removed", dataSource, texture, uid);

        Integer tidSkin = queryInt("SELECT tid_skin FROM players WHERE uid = " + uid);
        assertEquals(0, tidSkin, "移除衣柜项后玩家外观应归零");
    }

    private HikariDataSource dataSource(Path file) throws Exception {
        Files.createFile(file);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);

        return new HikariDataSource(config);
    }

    private int insertUser() throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("INSERT INTO users (email, nickname, score, avatar,"
                     + " password, ip, last_sign_at, register_at)"
                     + " VALUES ('a@b.c','n',0,0,'x','127.0.0.1',"
                     + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            ps.executeUpdate();
        }

        return queryInt("SELECT uid FROM users WHERE email = 'a@b.c'");
    }

    private int insertTexture() throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("INSERT INTO textures"
                     + " (name, type, hash, size, uploader, public, upload_at, likes)"
                     + " VALUES ('skin','steve','h',1,1,1,CURRENT_TIMESTAMP,0)")) {
            ps.executeUpdate();
        }

        return queryInt("SELECT tid FROM textures WHERE hash = 'h'");
    }

    private void insertPlayer(int uid, int tid) throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("INSERT INTO players"
                     + " (uid, name, tid_skin, tid_cape, last_modified)"
                     + " VALUES (?, 'p', ?, -1, CURRENT_TIMESTAMP)")) {
            ps.setInt(1, uid);
            ps.setInt(2, tid);
            ps.executeUpdate();
        }
    }

    private Integer queryInt(String sql) throws Exception {
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : null;
        }
    }
}
