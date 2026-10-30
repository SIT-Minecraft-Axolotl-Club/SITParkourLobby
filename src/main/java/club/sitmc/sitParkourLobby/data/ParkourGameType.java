package club.sitmc.sitParkourLobby.data;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 玩法大类（四个一级分类）。
 */
public enum ParkourGameType {

    PKW("PKW", "§b"),
    SEGMENT("Segment", "§a"),
    ONLYSPRINT("Onlysprint", "§e"),
    RANKUP("Rankup", "§d");

    /** 中文名称（含颜色码，用于菜单标题） */
    public final String coloredName;
    /** 纯中文名称（不含颜色码） */
    public final String chineseName;

    ParkourGameType(String chineseName, String colorCode) {
        this.chineseName = chineseName;
        this.coloredName = colorCode + chineseName;
    }

    /**
     * 从 {@link ParkourDataProvider} 获取该玩法对应的已缓存地图列表。
     */
    public List<Map<String, String>> getMaps(ParkourDataProvider provider) {
        return switch (this) {
            case PKW -> provider.getPKWMaps();
            case SEGMENT -> provider.getSegmentMaps();
            case ONLYSPRINT -> provider.getOnlySprintMaps();
            case RANKUP -> provider.getRankUpMaps();
        };
    }

    /**
     * 根据名称（大小写不敏感）查找对应的枚举值。
     * @param name 枚举名或中文名
     * @return 对应的 ParkourGameType，未找到返回 {@code null}
     */
    public static ParkourGameType fromString(String name) {
        if (name == null || name.isEmpty()) return null;
        String upper = name.toUpperCase();
        for (ParkourGameType type : values()) {
            if (type.name().equals(upper)) return type;
        }
        // 也支持通过 chineseName 查找
        for (ParkourGameType type : values()) {
            if (type.chineseName.equals(name)) return type;
        }
        return null;
    }

    /**
     * 所有合法枚举名列表（用于命令补全）。
     */
    public static List<String> enumNames() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
