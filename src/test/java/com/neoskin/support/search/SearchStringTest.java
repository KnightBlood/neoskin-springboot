package com.neoskin.support.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link SearchString} 的语法/编译测试（口径对齐源 {@code Grammar.pp} 与
 * {@code BuildColumnsVisitor}）。
 */
class SearchStringTest {

    private static SearchString.Options options() {
        return new SearchString.Options()
                .column("uid")
                .searchable("email")
                .searchable("nickname")
                .column("score")
                .date("last_sign_at")
                .booleanColumn("verified")
                .keyword("sort", "order_by")
                .keyword("fields", "select")
                .keyword("limit", "limit")
                .keyword("from", "offset");
    }

    private static SearchString.Result compile(String input) {
        return SearchString.compile(input, options());
    }

    @Test
    void emptyInputMeansNoFilter() {
        SearchString.Result result = compile("");
        assertNull(result.whereSql());
        assertTrue(result.params().isEmpty());
        assertTrue(result.orderBy().isEmpty());
        assertTrue(!result.invalid());
    }

    @Test
    void relationSyntaxFallsBackToAllResultsLikeSource() {
        // 源三个模型（User/Player/Report）都没有注册关系列：
        // 关系语法会校验失败 → fail=all-results（等价于无 WHERE）。
        assertNull(compile("player.name=steve").whereSql());
        assertNull(compile("players=(1,2)>2").whereSql());
    }

    @Test
    void plainKeywordSearchesSearchableColumns() {
        SearchString.Result result = compile("john");
        assertEquals("email LIKE ? OR nickname LIKE ?", result.whereSql());
        assertEquals(List.of("%john%", "%john%"), result.params());
    }

    @Test
    void negatedKeywordBecomesNotLikeAnd() {
        SearchString.Result result = compile("not john");
        assertEquals("email NOT LIKE ? AND nickname NOT LIKE ?", result.whereSql());
    }

    @Test
    void colonAndEqualsAreEquivalent() {
        assertEquals("email = ?", compile("email:foo").whereSql());
        assertEquals(List.of("foo"), compile("email=foo").params());
    }

    @Test
    void comparisonKeepsNumericValue() {
        SearchString.Result result = compile("score>10");
        assertEquals("score > ?", result.whereSql());
        assertEquals(List.of(10L), result.params());
    }

    @Test
    void booleanSoloAndNegation() {
        SearchString.Result result = compile("verified");
        assertEquals("verified = ?", result.whereSql());
        // 绑 Boolean 而非 0/1：PostgreSQL 的 BOOLEAN 列不能与整数比较
        assertEquals(List.of(true), result.params());

        SearchString.Result negated = compile("not verified");
        assertEquals("verified = ?", negated.whereSql());
        assertEquals(List.of(false), negated.params());
    }

    @Test
    void dateColumnSoloChecksNullability() {
        assertEquals("last_sign_at IS NOT NULL", compile("last_sign_at").whereSql());
        assertEquals("last_sign_at IS NULL", compile("not last_sign_at").whereSql());
    }

    @Test
    void inAndCommaLists() {
        SearchString.Result in = compile("uid in (1,2)");
        assertEquals("uid IN (?, ?)", in.whereSql());
        assertEquals(List.of(1L, 2L), in.params());

        SearchString.Result commas = compile("nickname=a,b");
        assertEquals("nickname IN (?, ?)", commas.whereSql());
        assertEquals(List.of("a", "b"), commas.params());

        SearchString.Result notIn = compile("not uid in (1,2)");
        assertEquals("uid NOT IN (?, ?)", notIn.whereSql());
    }

    @Test
    void nullValueBecomesIsNull() {
        assertEquals("nickname IS NULL", compile("nickname=NULL").whereSql());
        // 源语法没有 `!=` token：否定写法是 `not nickname=NULL`
        assertEquals("nickname IS NOT NULL", compile("not nickname=NULL").whereSql());
    }

    @Test
    void monthPrecisionExpandsToRange() {
        SearchString.Result result = compile("last_sign_at=2024-01");
        assertEquals("last_sign_at >= ? AND last_sign_at <= ?", result.whereSql());
        assertEquals(List.of("2024-01-01 00:00:00", "2024-01-31 23:59:59"), result.params());
    }

    @Test
    void dateComparisonsUseRangeBoundary() {
        assertEquals(List.of("2024-01-01 00:00:00"),
                compile("last_sign_at>=2024-01").params());
        assertEquals(List.of("2024-01-31 23:59:59"),
                compile("last_sign_at>2024-01").params());
    }

    @Test
    void dayPrecisionSecondPrecision() {
        assertEquals(List.of("2024-02-03 00:00:00", "2024-02-03 23:59:59"),
                compile("last_sign_at=2024-02-03").params());
        // 带时间的值在语法里必须加引号（空格是隐式 AND 分隔符）
        assertEquals("last_sign_at = ?",
                compile("last_sign_at=\"2024-02-03 10:11:12\"").whereSql());
        assertEquals(List.of("2024-02-03 10:11:12"),
                compile("last_sign_at=\"2024-02-03 10:11:12\"").params());
    }

    @Test
    void parenthesesAndBooleanOperators() {
        SearchString.Result result = compile("email=a or (nickname=b and score>1)");
        assertEquals("(email = ?) OR ((nickname = ?) AND (score > ?))", result.whereSql());
        assertEquals(List.of("a", "b", 1L), result.params());
    }

    @Test
    void notAppliesDeMorgan() {
        SearchString.Result result = compile("not (email=a or email=b)");
        assertEquals("(email != ?) AND (email != ?)", result.whereSql());
        assertEquals(List.of("a", "b"), result.params());
    }

    @Test
    void quotedStringKeepsSpaces() {
        SearchString.Result result = compile("nickname=\"John Doe\"");
        assertEquals("nickname = ?", result.whereSql());
        assertEquals(List.of("John Doe"), result.params());
    }

    @Test
    void sortKeywordBecomesOrderBy() {
        SearchString.Result result = compile("sort=-uid");
        assertNull(result.whereSql());
        assertEquals(List.of("uid DESC"), result.orderBy());

        assertEquals(List.of("email ASC"), compile("sort=email").orderBy());
    }

    @Test
    void unknownKeyFallsBackToAllResults() {
        SearchString.Result result = compile("foo:bar");
        assertTrue(result.invalid());
        assertNull(result.whereSql());
    }

    @Test
    void unbalancedParenthesesAreInvalid() {
        assertTrue(compile("(email=a").invalid());
    }

    @Test
    void sortInjectionIsIgnored() {
        SearchString.Result result = compile("sort=uid;drop");
        assertNull(result.whereSql());
        assertTrue(result.orderBy().isEmpty());
    }
}
