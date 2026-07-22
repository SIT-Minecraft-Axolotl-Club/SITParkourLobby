package club.sitmc.sitParkourLobby.data;

/**
 * PKW 地图的计时模式枚举。
 * 仅当 {@link ParkourGameType#PKW} 时有意义，
 * 非 PKW 地图此字段无意义，可为空/忽略。
 */
public enum TimingMode {

    /** 正计时（从 0 开始往上计时） */
    COUNTUP("countup", "正计时"),
    /** 倒计时（从设定值往下计时） */
    COUNTDOWN("countdown", "倒计时");

    /** 地图数据中 type 字段的值（与 Warrior/Segment 约定一致） */
    public final String key;
    /** 中文显示名 */
    public final String chineseName;

    TimingMode(String key, String chineseName) {
        this.key = key;
        this.chineseName = chineseName;
    }

    /**
     * 根据枚举名或 key 字符串查找对应的 TimingMode。
     * 大小写不敏感，未知值返回 {@code null}。
     */
    public static TimingMode fromString(String str) {
        if (str == null || str.isEmpty()) return null;
        String upper = str.toUpperCase();
        for (TimingMode mode : values()) {
            if (mode.name().equals(upper) || mode.key.equalsIgnoreCase(str)) {
                return mode;
            }
        }
        return null;
    }
}
