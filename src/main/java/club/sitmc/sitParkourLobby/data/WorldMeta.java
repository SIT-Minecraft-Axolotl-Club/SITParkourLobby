package club.sitmc.sitParkourLobby.data;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个受管世界的完整元数据。
 *
 * <p>由 SITParkourLobby 自行存储和管理，不再反射获取。
 * 所有字段直接序列化到 {@code data.yml → world-metadata.<worldName>} 下。</p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li><b>category</b> — 玩法大类。{@code null} 表示未设置，该世界不计入任何菜单分类。</li>
 *   <li><b>timingMode</b> — 仅当 category=PKW 时有意义，非 PKW 世界为 {@code null}。</li>
 *   <li><b>author</b> — 作者名，默认空字符串。</li>
 *   <li><b>status</b> — 地图状态，默认 {@link MapStatus#UNRELEASED}。</li>
 *   <li><b>icon</b> — 自定义图标（Bukkit Material 枚举名），{@code null} 表示使用默认。</li>
 *   <li><b>displayName</b> — 自定义展示名，{@code null} 表示使用世界名。</li>
 *   <li><b>typeName</b> — 自定义类型展示名，{@code null} 表示使用默认类型映射。</li>
 *   <li><b>difficulty</b> — 难度描述，{@code null} 表示不显示难度行。</li>
 * </ul>
 */
public class WorldMeta {

    /** 玩法大类（null = 未设置，不计入任何菜单分类） */
    public ParkourGameType category;
    /** 计时模式（仅 PKW 有意义） */
    public TimingMode timingMode;
    /** 作者名 */
    public String author = "";
    /** 地图状态 */
    public MapStatus status = MapStatus.UNRELEASED;

    /** 自定义图标（Bukkit Material 枚举名），null=使用默认 FILLED_MAP */
    public String icon;
    /** 自定义展示名，null=使用世界名 */
    public String displayName;
    /** 自定义类型展示名，null=使用默认类型映射 */
    public String typeName;
    /** 难度描述，null=不显示难度行 */
    public String difficulty;
    /** 地图简介，null=不显示简介 */
    public String introduction;
    /** 固定槽位页码（1-based），-1 = 未设置 */
    public int fixedPage = -1;
    /** 固定槽位号（0~44），-1 = 未设置 */
    public int fixedSlot = -1;

    public WorldMeta() {}

    /**
     * 便捷构造器。
     */
    public WorldMeta(ParkourGameType category, TimingMode timingMode, String author, MapStatus status) {
        this.category = category;
        this.timingMode = timingMode;
        this.author = author != null ? author : "";
        this.status = status != null ? status : MapStatus.UNRELEASED;
    }

    // ==================== 菜单数据转换 ====================

    /**
     * 将本元数据转换为菜单系统使用的 {@code Map<String, String>} 格式。
     *
     * <p>生成的 Map 包含以下键：</p>
     * <ul>
     *   <li>{@code name} — 世界名（原始 ID，始终为世界名）</li>
     *   <li>{@code displayName} — 展示名（自定义或世界名）</li>
     *   <li>{@code id} — 地图 ID（= 世界名）</li>
     *   <li>{@code world} — 世界名（传送用）</li>
     *   <li>{@code type} — 类型标识：
     *     <ul>
     *       <li>PKW → timingMode.key（"countup" / "countdown"）</li>
     *       <li>其他 → category 名小写（"segment" / "onlysprint" / "rankup"）</li>
     *     </ul>
     *   </li>
     *   <li>{@code typeName} — 自定义类型展示名（可为空）</li>
     *   <li>{@code author} — 作者名</li>
     *   <li>{@code status} — 状态 key（如 "OPEN"）</li>
     *   <li>{@code icon} — 自定义图标材质名（可为空）</li>
     *   <li>{@code difficulty} — 难度描述（可为空）</li>
     * </ul>
     *
     * @param worldName 世界名
     * @return 菜单兼容的地图数据 Map（保持插入顺序）
     */
    public Map<String, String> toMapData(String worldName) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("name", worldName);
        map.put("displayName", displayName != null && !displayName.isEmpty() ? displayName : worldName);
        map.put("id", worldName);
        map.put("world", worldName);

        // type: PKW → timingMode.key, 其他 → category 名小写
        if (category == ParkourGameType.PKW && timingMode != null) {
            map.put("type", timingMode.key);
        } else if (category != null) {
            map.put("type", category.name().toLowerCase());
        } else {
            map.put("type", "");
        }

        map.put("typeName", typeName != null ? typeName : "");
        map.put("author", author != null ? author : "");
        map.put("status", status != null ? status.key : MapStatus.UNRELEASED.key);
        map.put("icon", icon != null ? icon : "");
        map.put("difficulty", difficulty != null ? difficulty : "");
        map.put("introduction", introduction != null ? introduction : "");

        // 固定槽位：仅有效时写入（菜单层通过 containsKey 判断）
        if (fixedPage >= 1 && fixedSlot >= 0 && fixedSlot < 45) {
            map.put("fixedPage", String.valueOf(fixedPage));
            map.put("fixedSlot", String.valueOf(fixedSlot));
        }

        return map;
    }

    // ==================== 图标解析 ====================

    /**
     * 解析 icon 字段为 Bukkit Material，失败返回 null（调用方应回退默认）。
     */
    public Material resolveIcon() {
        if (icon == null || icon.isEmpty()) return null;
        try {
            Material mat = Material.matchMaterial(icon);
            if (mat == null) return null;
            if (!mat.isItem()) return null; // 排除非物品材质（如 AIR, WATER）
            return mat;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 该世界是否已设置分类（category != null）。
     */
    public boolean hasCategory() {
        return category != null;
    }

    // ==================== Object 方法 ====================

    @Override
    public String toString() {
        return "WorldMeta{category=" + category
                + ", timingMode=" + timingMode
                + ", author='" + author + '\''
                + ", status=" + status
                + ", icon='" + icon + '\''
                + ", displayName='" + displayName + '\''
                + ", typeName='" + typeName + '\''
                + ", difficulty='" + difficulty + '\''
                + ", introduction='" + introduction + '\''
                + ", fixedPage=" + fixedPage
                + ", fixedSlot=" + fixedSlot
                + '}';
    }
}
