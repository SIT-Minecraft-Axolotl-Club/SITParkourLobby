package club.sitmc.sitParkourLobby.data;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Logger;

/**
 * 跑酷地图数据提供者。
 *
 * <h3>数据来源</h3>
 * <p>地图元数据（category / timingMode / author / status）全部由
 * SITParkourLobby 自行存储管理，通过 {@link #setWorldMetadata(Map)} 注入。
 * 不再反射调用 Warrior/Segment 的 {@code getParkourMaps()}。</p>
 *
 * <h3>排行榜查询（保留反射）</h3>
 * <p>{@link #getLeaderboards(String, String, int)} 和
 * {@link #getPlayerRanks(String, String, UUID)} 仍通过反射调用
 * Warrior/Segment 获取排行数据。</p>
 *
 * <h3>路由规则</h3>
 * <ul>
 *   <li>category = PKW → 反射调用 SITParkourWarrior</li>
 *   <li>category = SEGMENT / ONLYSPRINT / RANKUP → 反射调用 SITSegment</li>
 * </ul>
 *
 * <h3>降级策略</h3>
 * <p>排行榜查询时若对应插件不可用或反射失败，返回空 Map，
 * 菜单显示"排行数据不可用"——不影响菜单浏览和传送功能。</p>
 */
public class ParkourDataProvider {

    private static final String WARRIOR_PLUGIN = "SITParkourWarrior";
    private static final String SEGMENT_PLUGIN = "SITSegment";

    /** type 常量 —— 用于菜单类型映射 */
    public static final String TYPE_COUNTUP = "countup";
    public static final String TYPE_COUNTDOWN = "countdown";
    public static final String TYPE_SEGMENT = "segment";
    public static final String TYPE_ONLYSPRINT = "onlysprint";
    public static final String TYPE_RANKUP = "rankup";

    // ==================== 排行榜：board 名常量 + 中文映射 ====================

    /** 正计时 PKW 的三个榜 */
    public static final String BOARD_STANDARD = "standard";
    public static final String BOARD_ADVANCE = "advance";
    public static final String BOARD_EXPECT = "expect";
    /** 倒计时 PKW 的单榜 */
    public static final String BOARD_COUNTDOWN = "countdown";
    /** Segment / OnlySprint / RankUp 的单榜 */
    public static final String BOARD_MAIN = "main";

    /** board 名 → 中文标题 */
    private static final Map<String, String> BOARD_TO_CHINESE = new HashMap<>();
    static {
        BOARD_TO_CHINESE.put(BOARD_STANDARD, "标准榜");
        BOARD_TO_CHINESE.put(BOARD_ADVANCE, "进阶榜");
        BOARD_TO_CHINESE.put(BOARD_EXPECT, "卓越榜");
        BOARD_TO_CHINESE.put(BOARD_COUNTDOWN, "得分榜");
        BOARD_TO_CHINESE.put(BOARD_MAIN, "排行榜");
    }

    /** board 名 → 中文标题 */
    public static String boardChineseName(String boardName) {
        return BOARD_TO_CHINESE.getOrDefault(boardName, boardName);
    }

    /**
     * 根据玩法大类返回该地图所在插件的 Bukkit 插件名。
     */
    public static String resolvePlugin(ParkourGameType gameType) {
        return switch (gameType) {
            case PKW -> WARRIOR_PLUGIN;
            case SEGMENT, ONLYSPRINT, RANKUP -> SEGMENT_PLUGIN;
        };
    }

    private final Logger logger;

    // ==================== 自有数据存储 ====================

    /** 世界名 → 元数据（由 SITParkourLobby 注入） */
    private Map<String, WorldMeta> worldMetadata = Collections.emptyMap();

    /** 按分类缓存的地图列表 */
    private List<Map<String, String>> cachedPkwMaps = Collections.emptyList();
    private List<Map<String, String>> cachedSegmentMaps = Collections.emptyList();
    private List<Map<String, String>> cachedOnlySprintMaps = Collections.emptyList();
    private List<Map<String, String>> cachedRankUpMaps = Collections.emptyList();

    public ParkourDataProvider(Logger logger) {
        this.logger = logger;
    }

    // ==================== 数据注入 ====================

    /**
     * 由 SITParkourLobby 在加载/更新元数据后调用。
     * @param metadata 世界名 → WorldMeta 的映射（防御性复制）
     */
    public void setWorldMetadata(Map<String, WorldMeta> metadata) {
        this.worldMetadata = metadata != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
                : Collections.emptyMap();
    }

    /**
     * 获取当前元数据的只读视图（供命令层查询）。
     */
    public Map<String, WorldMeta> getWorldMetadata() {
        return worldMetadata;
    }

    // ==================== 公共查询方法 ====================

    /**
     * 重新从本地元数据刷新所有分类缓存（在菜单打开时调用）。
     * 不再依赖反射调用 getParkourMaps()。
     */
    public void refresh() {
        List<Map<String, String>> pkwList = new ArrayList<>();
        List<Map<String, String>> segmentList = new ArrayList<>();
        List<Map<String, String>> onlySprintList = new ArrayList<>();
        List<Map<String, String>> rankUpList = new ArrayList<>();

        for (Map.Entry<String, WorldMeta> entry : worldMetadata.entrySet()) {
            String worldName = entry.getKey();
            WorldMeta meta = entry.getValue();

            // 未设置 category → 不计入任何分类
            if (meta.category == null) continue;

            Map<String, String> mapData = meta.toMapData(worldName);

            switch (meta.category) {
                case PKW -> pkwList.add(mapData);
                case SEGMENT -> segmentList.add(mapData);
                case ONLYSPRINT -> onlySprintList.add(mapData);
                case RANKUP -> rankUpList.add(mapData);
            }
        }

        cachedPkwMaps = Collections.unmodifiableList(pkwList);
        cachedSegmentMaps = Collections.unmodifiableList(segmentList);
        cachedOnlySprintMaps = Collections.unmodifiableList(onlySprintList);
        cachedRankUpMaps = Collections.unmodifiableList(rankUpList);
    }

    /** 获取 PKW 玩法地图列表（category=PKW 的受管世界）。 */
    public List<Map<String, String>> getPKWMaps() {
        return cachedPkwMaps;
    }

    /** 获取 Segment 玩法地图列表（category=SEGMENT 的受管世界）。 */
    public List<Map<String, String>> getSegmentMaps() {
        return cachedSegmentMaps;
    }

    /** 获取 OnlySprint 玩法地图列表（category=ONLYSPRINT 的受管世界）。 */
    public List<Map<String, String>> getOnlySprintMaps() {
        return cachedOnlySprintMaps;
    }

    /** 获取 RankUp 玩法地图列表（category=RANKUP 的受管世界）。 */
    public List<Map<String, String>> getRankUpMaps() {
        return cachedRankUpMaps;
    }

    // ==================== 排行榜查询（保留反射） ====================

    /**
     * 通过反射调用获取指定插件的排行榜数据。
     *
     * @param pluginName Bukkit 插件名
     * @param mapId      地图 ID（= 世界名）
     * @param topN       取前 N 名
     * @return board 名 → 上榜玩家列表；失败返回空 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, List<Map<String, String>>> getLeaderboards(String pluginName, String mapId, int topN) {
        Object result = invokePluginMethod(pluginName, "getLeaderboards",
                new Class<?>[]{String.class, int.class}, new Object[]{mapId, topN});
        if (result instanceof Map) {
            return (Map<String, List<Map<String, String>>>) result;
        }
        return Collections.emptyMap();
    }

    /**
     * 通过反射调用获取指定玩家在指定地图的各榜排名。
     *
     * @param pluginName Bukkit 插件名
     * @param mapId      地图 ID（= 世界名）
     * @param playerUuid 玩家 UUID
     * @return board 名 → 玩家排名数据；失败返回空 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, String>> getPlayerRanks(String pluginName, String mapId, UUID playerUuid) {
        Object result = invokePluginMethod(pluginName, "getPlayerRanks",
                new Class<?>[]{String.class, UUID.class}, new Object[]{mapId, playerUuid});
        if (result instanceof Map) {
            return (Map<String, Map<String, String>>) result;
        }
        return Collections.emptyMap();
    }

    /**
     * 通用反射调用：指定插件上的带参方法，返回 Object，失败返回 null。
     */
    private Object invokePluginMethod(String pluginName, String methodName,
                                      Class<?>[] paramTypes, Object[] args) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (plugin == null || !plugin.isEnabled()) {
            logger.warning("[ParkourData] 插件 " + pluginName + " 不可用，无法调用 " + methodName + "()");
            return null;
        }

        Method method;
        try {
            method = plugin.getClass().getMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            logger.warning("[ParkourData] " + pluginName + " 中未找到 " + methodName + paramList(paramTypes) + " 方法");
            return null;
        }

        try {
            Object result = method.invoke(plugin, args);
            logger.info("[ParkourData] 反射调用成功: " + pluginName + "." + methodName + paramList(paramTypes)
                    + " → " + (result == null ? "null" : result.getClass().getSimpleName()));
            return result;
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause();
            logger.warning("[ParkourData] 反射调用 " + pluginName + "." + methodName + "() 失败: "
                    + (cause != null ? cause.toString() : e.toString()));
            return null;
        } catch (Exception e) {
            logger.warning("[ParkourData] 调用 " + pluginName + "." + methodName + "() 异常: " + e);
            return null;
        }
    }

    private static String paramList(Class<?>[] types) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(types[i].getSimpleName());
        }
        return sb.append(")").toString();
    }
}
