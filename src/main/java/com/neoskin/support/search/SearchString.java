package com.neoskin.support.search;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code App\Support\SearchString} 的移植：搜索串语法 → SQL 条件。
 *
 * <p>语法（源 {@code Compiler/Grammar.pp}）:</p>
 * <pre>
 *   expr      := or
 *   or        := and ("or" and)*
 *   and       := term (("and")? term)*          // 空白即隐式 AND
 *   term      := "(" expr ")" | "not" term | 关系 | 键 运算符 值 | 键 "in" "(" 列表 ")" | 键 "=" 列表 | 纯词
 *   运算符    := ":" | "=" | ">" | ">=" | "<" | "<="
 *   列表      := 标量 ("," 标量)*
 *   标量      := '单引号' | "双引号" | 数字 | 词 | "NULL"
 * </pre>
 *
 * <p>模型侧只声明「列规则」（是否可全文搜索 / 日期 / 布尔）与「关键字规则」
 * （{@code sort/fields/limit/from}）。解析或校验失败时按源配置
 * {@code search-string.fail = all-results} 处理（{@link #compile} 返回
 * {@code invalid} 的结果，调用方不加过滤）。</p>
 *
 * <p>与源的差异：关系（{@code players.xxx=1}）未实现（当前三个模型都没有
 * 关系列，源侧同样会校验失败 → 全部结果）；{@code fields/limit/from} 关键字中
 * 只有 {@code sort}/{@code fields} 生效（{@code paginate()} 会覆盖 limit/offset，
 * 与源行为一致）。</p>
 */
public final class SearchString {

    /** 搜索串非法（对应 {@code InvalidSearchStringException}）。 */
    public static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Invalid(String message) {
            super(message);
        }
    }

    /** 列规则（{@code Options\ColumnRule}）。 */
    public record ColumnSpec(String column, boolean searchable, boolean date,
            boolean booleanCast, Map<String, String> map, String keyPattern) {
    }

    /** 模型侧规则集合（{@code SearchStringOptions}）。 */
    public static final class Options {
        private final Map<String, ColumnSpec> columns = new LinkedHashMap<>();
        private final Map<String, String> keywords = new LinkedHashMap<>();
        private boolean caseInsensitive;

        public Options column(String column) {
            columns.put(column, new ColumnSpec(column, false, false, false, Map.of(), null));
            return this;
        }

        /**
         * 列名别名（源 {@code 'tid_skin' => '/^(?:tid_)?skin$/'}）：键按 PHP
         * {@code preg_match} 语义匹配（pattern 含 {@code /.../} 定界符）。
         */
        public Options columnRegex(String column, String phpPattern) {
            columns.put(column, new ColumnSpec(column, false, false, false, Map.of(),
                    phpPattern));
            return this;
        }

        public Options searchable(String column) {
            columns.put(column, new ColumnSpec(column, true, false, false, Map.of(), null));
            return this;
        }

        public Options date(String column) {
            // ColumnRule::boolean 对日期列默认也为 true
            columns.put(column, new ColumnSpec(column, false, true, true, Map.of(), null));
            return this;
        }

        public Options booleanColumn(String column) {
            columns.put(column, new ColumnSpec(column, false, false, true, Map.of(), null));
            return this;
        }

        public Options mapped(String column, Map<String, String> map) {
            columns.put(column, new ColumnSpec(column, false, false, false, map, null));
            return this;
        }

        public Options keyword(String key, String column) {
            keywords.put(key, column);
            return this;
        }

        public Options caseInsensitive() {
            this.caseInsensitive = true;
            return this;
        }

        /** 是否大小写不敏感（默认 false）。 */
        public boolean isCaseInsensitive() {
            return caseInsensitive;
        }

        public ColumnSpec columnRule(String key) {
            for (ColumnSpec spec : columns.values()) {
                if (matches(spec, key)) {
                    return spec;
                }
            }

            return null;
        }

        /** 源 {@code Rule::match()}：键为声明名，或声明时的 PHP 正则（preg_match 查找语义）。 */
        private static boolean matches(ColumnSpec spec, String key) {
            if (key == null) {
                return false;
            }
            if (spec.keyPattern() == null) {
                return key.equals(spec.column());
            }
            String pattern = spec.keyPattern();
            if (pattern.length() > 1 && pattern.startsWith("/")) {
                int last = pattern.lastIndexOf('/');
                if (last > 0) {
                    pattern = pattern.substring(1, last);
                }
            }
            try {
                return java.util.regex.Pattern.compile(pattern).matcher(key).find();
            } catch (java.util.regex.PatternSyntaxException e) {
                return false;
            }
        }

        public ColumnSpec keywordRule(String key) {
            String column = keywords.get(key);

            return column == null ? null
                    : new ColumnSpec(column, false, false, false, Map.of(), null);
        }

        public List<String> searchables() {
            List<String> out = new ArrayList<>();
            for (ColumnSpec spec : columns.values()) {
                if (spec.searchable()) {
                    out.add(spec.column());
                }
            }

            return out;
        }

        public Set<String> allColumns() {
            return columns.keySet();
        }
    }

    /**
     * 编译结果。
     *
     * @param whereSql  WHERE 片段（不含 {@code WHERE}；无过滤时为 {@code null}）
     * @param params    与 {@code ?} 一一对应的参数
     * @param orderBy   排序子句（不含 {@code ORDER BY}；可能为空）
     * @param select    仅保留的列（空表示 {@code *}）
     * @param invalid   解析/校验失败（调用方应忽略全部条件）
     */
    public record Result(String whereSql, List<Object> params, List<String> orderBy,
            List<String> select, boolean invalid) {
    }

    private SearchString() {
    }

    /** 解析并编译；失败时返回 {@code invalid = true}（{@code fail=all-results}）。 */
    public static Result compile(String input, Options options) {
        if (input == null || input.isBlank()) {
            return new Result(null, List.of(), List.of(), List.of(), false);
        }

        try {
            Node ast = new Parser(new Lexer(input).tokens()).parseExpr();
            ast = attachRules(ast, options);
            ast = removeNot(ast, false);
            ast = optimize(ast);

            List<String> orderBy = new ArrayList<>();
            boolean[] selects = new boolean[1];
            List<String> select = new ArrayList<>();
            collectKeywords(ast, options, orderBy, select, selects);

            Sql where = build(ast, options);
            return new Result(where.sql(), where.params(), orderBy, select, false);
        } catch (Invalid e) {
            return new Result(null, List.of(), List.of(), List.of(), true);
        }
    }

    // ================================================================ 词法

    private enum Type {
        TERM, STRING, INTEGER, DECIMAL, ASSIGNMENT, COMPARATOR, AND, OR, NOT, IN, NULL,
        LPAREN, RPAREN, DOT, COMMA, EOF
    }

    private record Token(Type type, String text) {
    }

    private static final String SPECIAL = " \t\r\n:><=\"'().,";

    /** 对应 {@code Grammar.pp} 的 token 规则（含关键字/数字的前瞻约束）。 */
    private static final class Lexer {
        private final List<Token> tokens = new ArrayList<>();

        Lexer(String input) {
            int i = 0;
            int n = input.length();
            while (i < n) {
                char c = input.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (c == '(') {
                    tokens.add(new Token(Type.LPAREN, "("));
                    i++;
                    continue;
                }
                if (c == ')') {
                    tokens.add(new Token(Type.RPAREN, ")"));
                    i++;
                    continue;
                }
                if (c == '.') {
                    tokens.add(new Token(Type.DOT, "."));
                    i++;
                    continue;
                }
                if (c == ',') {
                    tokens.add(new Token(Type.COMMA, ","));
                    i++;
                    continue;
                }
                if (c == ':' || c == '=') {
                    tokens.add(new Token(Type.ASSIGNMENT, String.valueOf(c)));
                    i++;
                    continue;
                }
                if (c == '>' || c == '<') {
                    if (i + 1 < n && input.charAt(i + 1) == '=') {
                        tokens.add(new Token(Type.COMPARATOR, input.substring(i, i + 2)));
                        i += 2;
                    } else {
                        tokens.add(new Token(Type.COMPARATOR, String.valueOf(c)));
                        i++;
                    }
                    continue;
                }
                if (c == '\'' || c == '"') {
                    int end = input.indexOf(c, i + 1);
                    if (end < 0) {
                        throw new Invalid("Unclosed string");
                    }
                    tokens.add(new Token(Type.STRING, input.substring(i + 1, end)));
                    i = end + 1;
                    continue;
                }

                int start = i;
                while (i < n && SPECIAL.indexOf(input.charAt(i)) < 0) {
                    i++;
                }
                String word = input.substring(start, i);
                char next = i < n ? input.charAt(i) : '\0';

                // 小数：\d+\.\d+ 且其后为边界（对应 T_DECIMAL 的前瞻约束）
                if (word.matches("\\d+") && next == '.') {
                    int j = i + 1;
                    int digits = 0;
                    while (j < n && Character.isDigit(input.charAt(j))) {
                        j++;
                        digits++;
                    }
                    char after = j < n ? input.charAt(j) : '\0';
                    boolean afterBoundary = after == '\0' || after == '('
                            || after == ')' || isSpace(after);
                    if (digits > 0 && afterBoundary) {
                        tokens.add(new Token(Type.DECIMAL,
                                input.substring(start, j)));
                        i = j;
                        continue;
                    }
                }

                tokens.add(classify(word, next));
            }
            tokens.add(new Token(Type.EOF, ""));
        }

        private static Token classify(String word, char next) {
            boolean boundary = next == '\0' || next == '(' || next == ')' || isSpace(next);
            if (boundary) {
                switch (word) {
                    case "and", "AND":
                        return new Token(Type.AND, word);
                    case "or", "OR":
                        return new Token(Type.OR, word);
                    case "not", "NOT":
                        return new Token(Type.NOT, word);
                    case "in", "IN":
                        return new Token(Type.IN, word);
                    case "NULL":
                        return new Token(Type.NULL, word);
                    default:
                        break;
                }
                if (word.matches("\\d+")) {
                    return new Token(Type.INTEGER, word);
                }
            }
            if (!word.isEmpty() && word.chars().allMatch(Character::isDigit)) {
                return new Token(Type.INTEGER, word);
            }

            return new Token(Type.TERM, word);
        }

        private static boolean isSpace(char c) {
            return c == ' ' || c == '\t' || c == '\r' || c == '\n';
        }

        List<Token> tokens() {
            return tokens;
        }
    }

    // ================================================================ 语法树

    private sealed interface Node permits And, Or, Not, Solo, Query, List_, Relationship {
    }

    private record And(List<Node> children) implements Node {
    }

    private record Or(List<Node> children) implements Node {
    }

    private record Not(Node child) implements Node {
    }

    private static final class Solo implements Node {
        final String content;
        ColumnSpec rule;
        boolean negated;

        Solo(String content) {
            this.content = content;
        }
    }

    private static final class Query implements Node {
        final String key;
        ColumnSpec rule;
        String operator;
        Object value;

        Query(String key, String operator, Object value) {
            this.key = key;
            this.operator = operator;
            this.value = value;
        }
    }

    private static final class List_ implements Node {
        final String key;
        ColumnSpec rule;
        List<Object> values;
        boolean negated;

        List_(String key, List<Object> values) {
            this.key = key;
            this.values = values;
        }
    }

    private static final class Relationship implements Node {
        final String key;
        final Node expression;

        Relationship(String key, Node expression) {
            this.key = key;
            this.expression = expression;
        }
    }

    // ================================================================ 语法分析

    private static final class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Node parseExpr() {
            Node node = parseOr();
            if (peek().type() != Type.EOF) {
                throw new Invalid("Unexpected token: " + peek().text());
            }

            return node;
        }

        private Node parseOr() {
            List<Node> children = new ArrayList<>();
            children.add(parseAnd());
            while (peek().type() == Type.OR) {
                pos++;
                children.add(parseAnd());
            }

            return children.size() == 1 ? children.get(0) : new Or(children);
        }

        private Node parseAnd() {
            List<Node> children = new ArrayList<>();
            children.add(parseTerminal());
            while (true) {
                if (peek().type() == Type.AND) {
                    pos++;
                    children.add(parseTerminal());
                } else if (startsTerminal(peek())) {
                    children.add(parseTerminal());
                } else {
                    break;
                }
            }

            return children.size() == 1 ? children.get(0) : new And(children);
        }

        private static boolean startsTerminal(Token token) {
            return switch (token.type()) {
                case LPAREN, NOT, TERM, STRING, INTEGER, DECIMAL -> true;
                default -> false;
            };
        }

        private Node parseTerminal() {
            if (peek().type() == Type.LPAREN) {
                pos++;
                Node inner = parseOr();
                expect(Type.RPAREN);
                return inner;
            }
            if (peek().type() == Type.NOT) {
                pos++;
                return new Not(parseTerminal());
            }
            if (peek().type() != Type.TERM) {
                // 只有标量能作为 Solo（数字/字符串）
                Object scalar = parseScalar();
                return new Solo(String.valueOf(scalar));
            }

            Token key = peek();

            // 点号连接 → 关系（不支持，交给校验阶段判为非法）
            if (tokens.get(pos + 1).type() == Type.DOT) {
                StringBuilder name = new StringBuilder(key.text());
                pos++;
                while (peek().type() == Type.DOT) {
                    pos++;
                    name.append('.').append(expect(Type.TERM).text());
                }
                Node expression = new Solo("");
                if (peek().type() == Type.ASSIGNMENT || peek().type() == Type.COMPARATOR) {
                    String op = peek().text();
                    pos++;
                    Object value = parseNullableScalar();
                    expression = new Query(name.toString(), op, value);
                }
                return new Relationship(name.toString(), expression);
            }

            // key = ( expr ) [op integer]
            if (tokens.get(pos + 1).type() == Type.ASSIGNMENT
                    && tokens.get(pos + 2).type() == Type.LPAREN) {
                pos += 2;
                pos++; // '('
                Node inner = parseOr();
                expect(Type.RPAREN);
                return new Relationship(key.text(), inner);
            }

            if (peek().type() == Type.TERM && tokens.get(pos + 1).type() == Type.IN) {
                pos += 2;
                expect(Type.LPAREN);
                List<Object> values = new ArrayList<>();
                values.add(parseScalar());
                while (peek().type() == Type.COMMA) {
                    pos++;
                    values.add(parseScalar());
                }
                expect(Type.RPAREN);
                return new List_(key.text(), values);
            }

            if (peek().type() == Type.TERM
                    && (tokens.get(pos + 1).type() == Type.ASSIGNMENT
                            || tokens.get(pos + 1).type() == Type.COMPARATOR)) {
                pos++;
                String operator = peek().text();
                pos++;
                if (":".equals(operator)) {
                    operator = "="; // HoaConverterVisitor: T_ASSIGNMENT 统一成 '='
                }
                Object value = parseNullableScalar();

                if (peek().type() == Type.COMMA) {
                    List<Object> values = new ArrayList<>();
                    values.add(value);
                    while (peek().type() == Type.COMMA) {
                        pos++;
                        values.add(parseScalar());
                    }
                    return new List_(key.text(), values);
                }

                return new Query(key.text(), operator, value);
            }

            pos++;
            return new Solo(key.text());
        }

        private Object parseNullableScalar() {
            if (peek().type() == Type.NULL) {
                pos++;
                return null;
            }

            return parseScalar();
        }

        private Object parseScalar() {
            Token token = peek();
            switch (token.type()) {
                case STRING, INTEGER, DECIMAL -> {
                    pos++;
                    return token.text();
                }
                case TERM -> {
                    pos++;
                    return token.text();
                }
                default -> throw new Invalid("Expected scalar, got " + token.type());
            }
        }

        private Token expect(Type type) {
            Token token = peek();
            if (token.type() != type) {
                throw new Invalid("Expected " + type + ", got " + token.type());
            }
            pos++;

            return token;
        }

        private Token peek() {
            return tokens.get(pos);
        }
    }

    // ================================================================ 规则附着/校验

    private static Node attachRules(Node node, Options options) {
        if (node instanceof And and) {
            return new And(and.children().stream().map(n -> attachRules(n, options)).toList());
        }
        if (node instanceof Or or) {
            return new Or(or.children().stream().map(n -> attachRules(n, options)).toList());
        }
        if (node instanceof Not not) {
            return new Not(attachRules(not.child(), options));
        }
        if (node instanceof Solo solo) {
            solo.rule = options.columnRule(solo.content);
            return solo;
        }
        if (node instanceof Query query) {
            ColumnSpec rule = options.keywordRule(query.key);
            if (rule == null) {
                rule = options.columnRule(query.key);
            }
            if (rule == null) {
                throw new Invalid("Unrecognized key pattern [" + query.key + "]");
            }
            query.rule = rule;
            return query;
        }
        if (node instanceof List_ list) {
            ColumnSpec rule = options.keywordRule(list.key);
            if (rule == null) {
                rule = options.columnRule(list.key);
            }
            if (rule == null) {
                throw new Invalid("Unrecognized key pattern [" + list.key + "]");
            }
            list.rule = rule;
            return list;
        }
        if (node instanceof Relationship relationship) {
            // 未声明关系列 → 与源一致：校验失败
            throw new Invalid("Unrecognized key pattern [" + relationship.key + "]");
        }

        throw new Invalid("Unknown node");
    }

    // ================================================================ NOT 消除

    private static Node removeNot(Node node, boolean negate) {
        if (node instanceof And and) {
            List<Node> children = new ArrayList<>();
            for (Node child : and.children()) {
                children.add(removeNot(child, negate));
            }
            return negate ? new Or(children) : new And(children);
        }
        if (node instanceof Or or) {
            List<Node> children = new ArrayList<>();
            for (Node child : or.children()) {
                children.add(removeNot(child, negate));
            }
            return negate ? new And(children) : new Or(children);
        }
        if (node instanceof Not not) {
            return removeNot(not.child(), !negate);
        }
        if (node instanceof Solo solo) {
            if (negate) {
                solo.negated = !solo.negated;
            }
            return solo;
        }
        if (node instanceof Query query) {
            if (negate) {
                query.operator = reverseOperator(query.operator);
            }
            return query;
        }
        if (node instanceof List_ list) {
            if (negate) {
                list.negated = !list.negated;
            }
            return list;
        }

        return node;
    }

    private static String reverseOperator(String operator) {
        return switch (operator) {
            case "=" -> "!=";
            case "!=" -> "=";
            case ">" -> "<=";
            case ">=" -> "<";
            case "<" -> ">=";
            case "<=" -> ">";
            default -> operator;
        };
    }

    // ================================================================ 优化

    private static Node optimize(Node node) {
        if (node instanceof And and) {
            List<Node> leaves = new ArrayList<>();
            for (Node child : and.children()) {
                Node optimized = optimize(child);
                if (optimized instanceof And inner) {
                    leaves.addAll(inner.children());
                } else if (optimized != null) {
                    leaves.add(optimized);
                }
            }
            if (leaves.isEmpty()) {
                return null;
            }

            return leaves.size() == 1 ? leaves.get(0) : new And(leaves);
        }
        if (node instanceof Or or) {
            List<Node> leaves = new ArrayList<>();
            for (Node child : or.children()) {
                Node optimized = optimize(child);
                if (optimized instanceof Or inner) {
                    leaves.addAll(inner.children());
                } else if (optimized != null) {
                    leaves.add(optimized);
                }
            }
            if (leaves.isEmpty()) {
                return null;
            }

            return leaves.size() == 1 ? leaves.get(0) : new Or(leaves);
        }
        if (node instanceof Not not) {
            Node inner = optimize(not.child());

            return inner == null ? null : new Not(inner);
        }

        return node;
    }

    // ================================================================ 关键字

    private static void collectKeywords(Node node, Options options, List<String> orderBy,
            List<String> select, boolean[] selectNegated) {
        if (node instanceof And and) {
            for (Node child : and.children()) {
                collectKeywords(child, options, orderBy, select, selectNegated);
            }
            return;
        }
        if (node instanceof Or or) {
            for (Node child : or.children()) {
                collectKeywords(child, options, orderBy, select, selectNegated);
            }
            return;
        }
        if (node instanceof Query query) {
            if (query.rule != null && options.keywordRule(query.key) != null) {
                applyKeyword(query.rule.column(), query.value, "=".equals(query.operator),
                        options, orderBy, select, selectNegated);
            }
            return;
        }
        if (node instanceof List_ list) {
            if (list.rule != null && options.keywordRule(list.key) != null) {
                applyKeyword(list.rule.column(), list.values, list.negated,
                        options, orderBy, select, selectNegated);
            }
        }
    }

    private static void applyKeyword(String keywordColumn, Object value, boolean negated,
            Options options, List<String> orderBy, List<String> select, boolean[] selectNegated) {
        List<Object> values = value instanceof List<?> list
                ? new ArrayList<>(list) : List.of(value);

        switch (keywordColumn) {
            case "order_by" -> {
                for (Object raw : values) {
                    String v = String.valueOf(raw);
                    boolean desc = v.startsWith("-");
                    String column = desc ? v.substring(1) : v;
                    ColumnSpec spec = options.columnRule(column);
                    String real = spec == null ? column : spec.column();
                    if (!real.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        // 源把列名交给 Grammar::wrap 后才会进 SQL；这里直接拒绝非标识符
                        continue;
                    }
                    orderBy.add(real + (desc ? " DESC" : " ASC"));
                }
            }
            case "select" -> {
                List<String> requested = new ArrayList<>();
                for (Object raw : values) {
                    String v = String.valueOf(raw);
                    ColumnSpec spec = options.columnRule(v);
                    requested.add(spec == null ? v : spec.column());
                }
                select.clear();
                for (String column : options.allColumns()) {
                    boolean hit = requested.contains(column);
                    if (negated ? !hit : hit) {
                        select.add(column);
                    }
                }
                selectNegated[0] = negated;
            }
            default -> {
                // limit / from 由 paginate() 覆盖（与源一致）
            }
        }
    }

    // ================================================================ SQL

    private record Sql(String sql, List<Object> params) {
    }

    private static Sql build(Node node, Options options) {
        if (node == null) {
            return new Sql(null, List.of());
        }
        if (node instanceof And and) {
            return combine(and.children(), " AND ", options);
        }
        if (node instanceof Or or) {
            return combine(or.children(), " OR ", options);
        }
        if (node instanceof Solo solo) {
            return buildSolo(solo, options);
        }
        if (node instanceof Query query) {
            if (options.keywordRule(query.key) != null) {
                return new Sql(null, List.of()); // 关键字不参与 WHERE
            }
            return buildQuery(query, options);
        }
        if (node instanceof List_ list) {
            if (options.keywordRule(list.key) != null) {
                return new Sql(null, List.of());
            }
            return buildList(list);
        }

        throw new Invalid("Unsupported node");
    }

    private static Sql combine(List<Node> children, String joiner, Options options) {
        List<String> parts = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (Node child : children) {
            Sql sql = build(child, options);
            if (sql.sql() == null) {
                continue;
            }
            parts.add(children.size() == 1 ? sql.sql() : "(" + sql.sql() + ")");
            params.addAll(sql.params());
        }
        if (parts.isEmpty()) {
            return new Sql(null, List.of());
        }

        return new Sql(String.join(joiner, parts), params);
    }

    private static Sql buildSolo(Solo solo, Options options) {
        ColumnSpec rule = solo.rule;

        if (rule != null && rule.booleanCast() && rule.date()) {
            // whereNull($col, 'and', !negated)
            return new Sql(rule.column() + (solo.negated ? " IS NULL" : " IS NOT NULL"),
                    List.of());
        }
        if (rule != null && rule.booleanCast()) {
            // 绑布尔值而不是 0/1：PostgreSQL 的 BOOLEAN 列不能与整数比较，
            // 而 MySQL/SQL Server/SQLite 驱动会把 Boolean 适配成对应字面量。
            return new Sql(rule.column() + " = ?", List.of(!solo.negated));
        }

        List<String> searchables = options.searchables();
        if (searchables.isEmpty()) {
            return new Sql(null, List.of());
        }

        String like = solo.negated ? " NOT LIKE ?" : " LIKE ?";
        String joiner = solo.negated ? " AND " : " OR ";
        List<String> parts = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (String column : searchables) {
            String col = options.isCaseInsensitive() ? "LOWER(" + column + ")" : column;
            parts.add(col + like);
            params.add("%" + (options.isCaseInsensitive() ? solo.content.toLowerCase(Locale.ROOT) : solo.content) + "%");
        }

        return new Sql(String.join(joiner, parts), params);
    }

    private static Sql buildQuery(Query query, Options options) {
        ColumnSpec rule = query.rule;
        if (rule == null) {
            return new Sql(null, List.of());
        }

        Object value = mapValue(query.value, rule);

        if (value == null) {
            boolean notNull = !"=".equals(query.operator);
            return new Sql(rule.column() + (notNull ? " IS NOT NULL" : " IS NULL"), List.of());
        }

        if (rule.date()) {
            return buildDate(query.operator, value, rule);
        }

        return new Sql(rule.column() + " " + query.operator + " ?", List.of(value));
    }

    private static Sql buildList(List_ list) {
        ColumnSpec rule = list.rule;
        if (rule == null) {
            return new Sql(null, List.of());
        }

        List<Object> values = new ArrayList<>();
        for (Object value : list.values) {
            values.add(mapValue(value, rule));
        }

        String placeholders = String.join(", ", java.util.Collections.nCopies(
                values.size(), "?"));
        String sql = rule.column() + (list.negated ? " NOT IN (" : " IN (") + placeholders + ")";

        return new Sql(sql, values);
    }

    private static Object mapValue(Object value, ColumnSpec rule) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (rule.map().containsKey(text)) {
            return rule.map().get(text);
        }
        if (text.matches("[-+]?\\d+")) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return text;
            }
        }
        if (text.matches("[-+]?\\d+\\.\\d+")) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return text;
            }
        }

        return text;
    }

    // ---------------------------------------------------------------- 日期

    private enum Precision {
        SECOND, MINUTE, HOUR, DAY, MONTH, YEAR
    }

    private record DateValue(Precision precision, LocalDateTime start, LocalDateTime end) {
    }

    private static Sql buildDate(String operator, Object value, ColumnSpec rule) {
        DateValue parsed = parseDate(String.valueOf(value));

        if (parsed == null) {
            return new Sql(rule.column() + " " + operator + " ?", List.of(value));
        }

        if (parsed.precision() == Precision.SECOND) {
            return new Sql(rule.column() + " " + operator + " ?",
                    List.of(format(parsed.start())));
        }

        if (Set.of(">", "<", ">=", "<=").contains(operator)) {
            LocalDateTime bound = ("<".equals(operator) || ">=".equals(operator))
                    ? parsed.start() : parsed.end();
            return new Sql(rule.column() + " " + operator + " ?", List.of(format(bound)));
        }

        boolean exclude = "!=".equals(operator) || "not in".equals(operator);
        String first = exclude ? "<" : ">=";
        String second = exclude ? ">" : "<=";

        return new Sql(rule.column() + " " + first + " ? AND " + rule.column() + " " + second
                + " ?", List.of(format(parsed.start()), format(parsed.end())));
    }

    private static final Pattern SECOND = Pattern.compile(
            "^(\\d{4})-(\\d{1,2})-(\\d{1,2}) (\\d{1,2}):(\\d{2}):(\\d{2})$");
    private static final Pattern MINUTE = Pattern.compile(
            "^(\\d{4})-(\\d{1,2})-(\\d{1,2}) (\\d{1,2}):(\\d{2})$");
    private static final Pattern HOUR = Pattern.compile(
            "^(\\d{4})-(\\d{1,2})-(\\d{1,2}) (\\d{1,2})$");
    private static final Pattern DAY = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$");
    private static final Pattern MONTH_Y = Pattern.compile("^(\\d{4})[-/ ](\\d{1,2})$");
    private static final Pattern MONTH_MY = Pattern.compile("^(\\d{1,2})[-/ ](\\d{4})$");
    private static final Pattern YEAR = Pattern.compile("^(\\d{4})$");

    private static DateValue parseDate(String text) {
        Matcher m = SECOND.matcher(text);
        if (m.matches()) {
            return new DateValue(Precision.SECOND, dateTime(m), dateTime(m));
        }
        m = MINUTE.matcher(text);
        if (m.matches()) {
            LocalDateTime start = LocalDateTime.of(LocalDate.of(
                    intOf(m.group(1)), intOf(m.group(2)), intOf(m.group(3))),
                    LocalTime.of(intOf(m.group(4)), intOf(m.group(5))));
            return new DateValue(Precision.MINUTE, start, start.plusMinutes(1).minusSeconds(1));
        }
        m = HOUR.matcher(text);
        if (m.matches()) {
            LocalDateTime start = LocalDateTime.of(LocalDate.of(
                    intOf(m.group(1)), intOf(m.group(2)), intOf(m.group(3))),
                    LocalTime.of(intOf(m.group(4)), 0));
            return new DateValue(Precision.HOUR, start, start.plusHours(1).minusSeconds(1));
        }
        m = DAY.matcher(text);
        if (m.matches()) {
            LocalDate date = LocalDate.of(intOf(m.group(1)), intOf(m.group(2)), intOf(m.group(3)));
            return new DateValue(Precision.DAY, date.atStartOfDay(),
                    date.atTime(LocalTime.of(23, 59, 59)));
        }
        m = MONTH_Y.matcher(text);
        if (m.matches()) {
            return monthValue(intOf(m.group(1)), intOf(m.group(2)));
        }
        m = MONTH_MY.matcher(text);
        if (m.matches()) {
            return monthValue(intOf(m.group(2)), intOf(m.group(1)));
        }
        m = YEAR.matcher(text);
        if (m.matches()) {
            int year = intOf(m.group(1));
            return new DateValue(Precision.YEAR, LocalDate.of(year, 1, 1).atStartOfDay(),
                    LocalDate.of(year, 12, 31).atTime(23, 59, 59));
        }

        return null;
    }

    private static DateValue monthValue(int year, int month) {
        if (month < 1 || month > 12) {
            return null;
        }
        LocalDate first = LocalDate.of(year, month, 1);

        return new DateValue(Precision.MONTH, first.atStartOfDay(),
                first.withDayOfMonth(first.lengthOfMonth()).atTime(23, 59, 59));
    }

    private static LocalDateTime dateTime(Matcher m) {
        return LocalDateTime.of(LocalDate.of(intOf(m.group(1)), intOf(m.group(2)),
                intOf(m.group(3))), LocalTime.of(intOf(m.group(4)), intOf(m.group(5)),
                        intOf(m.group(6))));
    }

    private static int intOf(String value) {
        return Integer.parseInt(value);
    }

    private static final DateTimeFormatter OUTPUT = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm:ss");

    private static String format(LocalDateTime value) {
        return value.format(OUTPUT);
    }
}
