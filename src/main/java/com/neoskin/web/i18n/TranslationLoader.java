package com.neoskin.web.i18n;

import java.util.Map;

/**
 * {@code Illuminate\Contracts\Translation\Loader} 的移植接口。
 *
 * <p>三层产物结构（对齐 Laravel）：</p>
 * <ul>
 *   <li>常规组：{@code Map<group, Map<...嵌套 Item 值>>}，即源里返回
 *       {@code ['group' => ['item' => '...' ]]} 的形态；</li>
 *   <li>JSON 组：{@code group === '*' && namespace === '*'} → 单层
 *       {@code Map<key, value>}。</li>
 * </ul>
 */
public interface TranslationLoader {

    /**
     * 读取一个组的翻译行。
     *
     * @param locale    语言，如 {@code zh_CN}
     * @param group     组名，如 {@code auth}
     * @param namespace {@code '*'}（常规）或插件命名空间
     * @return 嵌套行；按键遍历顺序保持源文件/数据库顺序。组不存在 → 空表。
     */
    Map<String, Object> load(String locale, String group, String namespace);
}

