package com.neoskin.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Composer Semver 的最小移植（只覆盖更新检查用到的比较与约束）。
 *
 * <p>源的 {@code Comparator::greaterThan('6.0.11_patch', ...)} 会把
 * {@code _patch} 这类后缀按 Composer 规则归一化：实测
 * {@code 6.0.11_patch == 6.0.11}、{@code 6.0.11_patch > 6.0.10_patch}。
 * 这里按「取前导数字段做数值比较」实现，忽略稳定后缀。</p>
 */
public final class Semver {

    private Semver() {
    }

    /** 数值版本比较：{@code a > b}。 */
    public static boolean greaterThan(String a, String b) {
        return compare(a, b) > 0;
    }

    /** 数值版本比较：{@code a < b}。 */
    public static boolean lessThan(String a, String b) {
        return compare(a, b) < 0;
    }

    public static int compare(String a, String b) {
        List<Integer> left = numericParts(a);
        List<Integer> right = numericParts(b);
        int size = Math.max(left.size(), right.size());
        for (int i = 0; i < size; i++) {
            int lv = i < left.size() ? left.get(i) : 0;
            int rv = i < right.size() ? right.get(i) : 0;
            if (lv != rv) {
                return Integer.compare(lv, rv);
            }
        }

        return 0;
    }

    private static List<Integer> numericParts(String version) {
        List<Integer> parts = new ArrayList<>();
        if (version == null) {
            return parts;
        }
        Matcher matcher = Pattern.compile("^\\s*v?(\\d+(?:\\.\\d+)*)").matcher(version);
        if (!matcher.find()) {
            return parts;
        }
        for (String part : matcher.group(1).split("\\.")) {
            try {
                parts.add(Integer.parseInt(part));
            } catch (NumberFormatException e) {
                parts.add(0);
            }
        }

        return parts;
    }

    /**
     * {@code Semver::satisfies($version, $constraint)} 的常用子集：
     * {@code *} / {@code >=X} / {@code <=X} / {@code >X} / {@code <X} /
     * {@code ^X.Y.Z} / {@code ~X.Y.Z} / 精确版本，空或 {@code null} 视为满足。
     */
    public static boolean satisfies(String version, String constraint) {
        if (constraint == null || constraint.isBlank() || "*".equals(constraint.trim())) {
            return true;
        }
        for (String token : constraint.trim().split("\\s+")) {
            if (!satisfiesOne(version, token)) {
                return false;
            }
        }

        return true;
    }

    private static boolean satisfiesOne(String version, String token) {
        if (token.isEmpty() || "*".equals(token)) {
            return true;
        }
        if (token.startsWith(">=")) {
            return compare(version, token.substring(2)) >= 0;
        }
        if (token.startsWith("<=")) {
            return compare(version, token.substring(2)) <= 0;
        }
        if (token.startsWith(">")) {
            return compare(version, token.substring(1)) > 0;
        }
        if (token.startsWith("<")) {
            return compare(version, token.substring(1)) < 0;
        }
        if (token.startsWith("^") || token.startsWith("~")) {
            String base = token.substring(1);
            if (compare(version, base) < 0) {
                return false;
            }
            List<Integer> parts = numericParts(base);
            int major = parts.isEmpty() ? 0 : parts.get(0);
            int minor = parts.size() > 1 ? parts.get(1) : 0;
            int boundMajor = token.startsWith("^") ? major + 1 : major;
            int boundMinor = token.startsWith("^") ? 0 : minor + 1;
            // 粗略上界：^X → < X+1；~X.Y → < X.(Y+1)
            if (token.startsWith("^")) {
                return compare(version, boundMajor + ".0.0") < 0;
            }
            return compare(version, boundMajor + "." + boundMinor + ".0") < 0;
        }
        if (token.startsWith("!=")) {
            return compare(version, token.substring(2)) != 0;
        }

        return compare(version, token) == 0;
    }
}
