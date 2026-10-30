package club.sitmc.sitParkourLobby.data;

import java.util.*;

/**
 * 地图状态枚举。
 *
 * <h3>排序规则</h3>
 * <p>先按状态优先级升序（{@link #priority} 越小越靠前），
 * 同状态内按地图名称字典序升序。</p>
 *
 * <h3>OP / 非OP 区别</h3>
 * <ul>
 *   <li>OP：所有状态均可查看完整信息并可点击进入三级。</li>
 *   <li>非OP：{@link #CLOSED} 可查看信息但不可进入；
 *       {@link #UNRELEASED} 名称打码为 "???" 且不可进入。</li>
 * </ul>
 */
public enum MapStatus {

    EVENT("EVENT", 0, "活动中", "§6"),
    OPEN("OPEN", 1, "开放中", "§a"),
    TESTING("TESTING", 2, "测试中", "§d"),
    CLOSED("CLOSED", 3, "已关闭", "§c"),
    UNRELEASED("UNRELEASED", 4, "未开放", "§7"),
    /** 兜底：未知状态，排在最后 */
    UNKNOWN(null, 99, "未知", "§7");

    /** 地图数据中 status 字段的原始字符串值 */
    public final String key;
    /** 排序优先级（越小越靠前） */
    public final int priority;
    /** 中文显示名 */
    public final String chineseName;
    /** 颜色代码（§x 格式） */
    public final String colorCode;

    MapStatus(String key, int priority, String chineseName, String colorCode) {
        this.key = key;
        this.priority = priority;
        this.chineseName = chineseName;
        this.colorCode = colorCode;
    }

    // ==================== 查询方法 ====================

    private static final Map<String, MapStatus> BY_KEY = new HashMap<>();
    static {
        for (MapStatus s : values()) {
            if (s.key != null) {
                BY_KEY.put(s.key, s);
            }
        }
    }

    /**
     * 根据英文 key 查找对应的枚举值，未知 key 返回 {@link #UNKNOWN}。
     */
    public static MapStatus fromKey(String key) {
        return BY_KEY.getOrDefault(key, UNKNOWN);
    }

    /**
     * 对地图列表排序：先按状态优先级，同状态内按 name 字典序升序。
     */
    public static void sort(List<Map<String, String>> maps) {
        maps.sort(Comparator
                .<Map<String, String>, Integer>comparing(m -> fromKey(m.getOrDefault("status", "")).priority)
                .thenComparing(m -> m.getOrDefault("name", ""), String.CASE_INSENSITIVE_ORDER));
    }
}
