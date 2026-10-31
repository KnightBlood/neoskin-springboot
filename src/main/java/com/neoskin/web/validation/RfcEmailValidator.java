package com.neoskin.web.validation;

/**
 * RFC 邮箱校验（对应 Laravel {@code email} 规则默认的 egulias RFCValidation）。
 *
 * <p>按 PHP 探针锁定的行为实现：local part 的点/引号/注释/转义规则，
 * domain part 的标签/长度/IP 字面量规则。不实现 DNS/spoof 检查。</p>
 */
public final class RfcEmailValidator {

    private RfcEmailValidator() {
    }

    public static boolean isValid(String email) {
        if (email == null) return false;
        if (email.contains("\r") || email.contains("\n")) return false;
        // 单个 @（引号内 @ 不在 probe 范围，简化：按最后一个 @ 切分）
        int at = email.indexOf('@');
        if (at < 0) return false;
        if (email.indexOf('@', at + 1) >= 0) return false;
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        if (local.isEmpty() || domain.isEmpty()) return false;
        return validateLocal(local) && validateDomain(domain);
    }

    private static boolean validateLocal(String local) {
        // 引号形式
        if (local.startsWith("\"") && local.endsWith("\"") && local.length() >= 2) {
            String inner = local.substring(1, local.length() - 1);
            // 转义：反斜杠后跟任意字符；未转义的 " 不允许
            int i = 0;
            while (i < inner.length()) {
                char c = inner.charAt(i);
                if (c == '\\') {
                    if (i + 1 >= inner.length()) return false;
                    i += 2;
                    continue;
                }
                if (c == '"') return false;
                i++;
            }
            return true;
        }
        // 非引号：处理注释 + 校验
        // 去除注释后校验点与 token
        String stripped = stripComments(local);
        if (stripped.isEmpty()) return false;
        // 点规则
        if (stripped.startsWith(".") || stripped.endsWith(".")) return false;
        if (stripped.contains("..")) return false;
        // 反斜杠 → 无效（未转义）
        if (stripped.contains("\\")) return false;
        // 无效 token
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == ' ' || c == '\t' || c == ',' || c == '[' || c == ']' ||
                    c == '<' || c == '>' || c == ':' || c == ';') {
                return false;
            }
        }
        return true;
    }

    private static boolean validateDomain(String domain) {
        // IP 字面量
        if (domain.startsWith("[")) {
            return domain.endsWith("]");
        }
        // 去除注释
        String stripped = stripComments(domain);
        if (stripped.isEmpty()) return false;
        // 总长度
        if (stripped.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 253) return false;
        // 点规则
        if (stripped.startsWith(".") || stripped.endsWith(".")) return false;
        if (stripped.contains("..")) return false;
        String[] labels = stripped.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty()) return false;
            byte[] lb = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (lb.length > 63) return false;
            if (label.startsWith("-") || label.endsWith("-")) return false;
            if (label.contains("_")) return false;
            if (label.contains(" ") || label.contains("\\") || label.contains("/")) return false;
            // 无效字符：控制字符
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (c < 0x20) return false;
            }
        }
        return true;
    }

    /** 去除平衡的括号注释。 */
    private static String stripComments(String s) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
