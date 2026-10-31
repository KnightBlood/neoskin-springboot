package com.neoskin.service;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import com.neoskin.db.ClosetRepository;
import com.neoskin.db.PlayerRepository;
import com.neoskin.db.TextureRepository;
import com.neoskin.db.UserRepository;

/**
 * 源项目 {@code texture.deleted} / {@code texture.privacy.updated} 三个监听器的移植：
 * {@code UpdateScoreForDeletedTexture}、{@code ResetPlayers}、{@code CleanUpCloset}。
 *
 * <p>两个事件的分派点：</p>
 * <ul>
 *   <li>删除材质（{@code SkinlibController@delete}、后台举报删除）→ {@link #onDeleted}；</li>
 *   <li>隐私切换（{@code SkinlibController@privacy}）→ {@link #onPrivacyUpdated}。</li>
 * </ul>
 *
 * <p>隐私切换只在「公开 → 私有」时有副作用（源监听器对 {@code exists && public}
 * 直接返回）；删除时 {@code exists=false}，三个监听器都会执行。</p>
 */
public final class TextureSideEffects {

    private TextureSideEffects() {
    }

    /** {@code texture.deleted}。 */
    public static void onDeleted(DataSource ds, Option option, TextureRepository.Row texture)
            throws SQLException {
        refundScore(ds, option, texture);
        resetPlayers(ds, texture, null);
        cleanUpCloset(ds, option, texture, false);
    }

    /** {@code texture.privacy.updated}（只会是「公开 → 私有」）。 */
    public static void onPrivacyUpdated(DataSource ds, Option option, TextureRepository.Row texture)
            throws SQLException {
        if (texture.isPublic()) {
            return;
        }
        resetPlayers(ds, texture, texture.uploader());
        cleanUpCloset(ds, option, texture, true);
    }

    /** {@code UpdateScoreForDeletedTexture}：退还存储分并扣回上传奖励。 */
    public static void refundScore(DataSource ds, Option option, TextureRepository.Row texture)
            throws SQLException {
        UserRepository users = new UserRepository(ds);
        UserRepository.UserRow uploader = users.findById(texture.uploader());
        if (uploader == null) {
            return;
        }

        long ret = 0;
        if (truthy(option.get("return_score"))) {
            ret += texture.size() * (texture.isPublic()
                    ? toInt(option.get("score_per_storage"))
                    : toInt(option.get("private_score_per_storage")));
        }
        if (texture.isPublic() && truthy(option.get("take_back_scores_after_deletion", true))) {
            ret -= toInt(option.get("score_award_per_texture", 0));
        }

        users.updateScore(uploader.uid(), (int) (uploader.score() + ret));
    }

    /** {@code ResetPlayers}。 */
    public static void resetPlayers(DataSource ds, TextureRepository.Row texture,
            Integer excludeUid) throws SQLException {
        String column = "cape".equals(texture.type()) ? "tid_cape" : "tid_skin";
        new PlayerRepository(ds).resetTexture(texture.tid(), column, excludeUid);
    }

    /** {@code CleanUpCloset}。 */
    public static void cleanUpCloset(DataSource ds, Option option,
            TextureRepository.Row texture, boolean textureStillExists) throws SQLException {
        ClosetRepository closet = new ClosetRepository(ds);
        List<Integer> likers = closet.likerUids(texture.tid(), texture.uploader());
        if (likers.isEmpty()) {
            return;
        }

        UserRepository users = new UserRepository(ds);
        boolean returnScore = truthy(option.get("return_score"));
        int refund = toInt(option.get("score_per_closet_item"));

        for (int uid : likers) {
            closet.detach(uid, texture.tid());
            if (returnScore) {
                UserRepository.UserRow user = users.findById(uid);
                if (user != null) {
                    users.updateScore(uid, user.score() + refund);
                }
            }
        }

        if (textureStillExists) {
            new TextureRepository(ds).updateLikes(texture.tid(),
                    texture.likes() - likers.size());
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** PHP 的 {@code (bool)} 语义（包含 {@code "0"}、{@code "false"}、空串）。 */
    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        String s = String.valueOf(value).trim().toLowerCase();
        return !(s.isEmpty() || s.equals("0") || s.equals("false") || s.equals("off")
                || s.equals("no") || s.equals("null"));
    }
}
