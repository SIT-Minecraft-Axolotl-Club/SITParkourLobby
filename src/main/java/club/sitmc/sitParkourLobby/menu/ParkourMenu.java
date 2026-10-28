package club.sitmc.sitParkourLobby.menu;

import club.sitmc.sitParkourLobby.data.ParkourDataProvider;
import club.sitmc.sitParkourLobby.data.ParkourGameType;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * 跑酷菜单（一级界面 — 玩法选择）。
 *
 * <h3>界面布局（27 格 chest）</h3>
 * <pre>
 *  Row 0 (slots  0- 8): [B][B][B][B][B][B][B][B][B]  全边框
 *  Row 1 (slots  9-17): [B][PKW][ ][SEG][ ][RKU][ ][SPR][B]  PKW(10)/SEG(12)/RKU(14)/SPR(16)
 *  Row 2 (slots 18-26): [B][日志][  ][  ][头][  ][  ][练习][B]  日志(20)/玩家信息(22)/练习(24)
 * </pre>
 *
 * <p>点击玩法图标 → 打开对应的二级地图列表 GUI。</p>
 */
public class ParkourMenu implements Listener {

    private static final String MENU_TITLE = "§b§l跑酷菜单";
    private static final String LOBBY_LOG_URL = "https://sit-minecraft.feishu.cn/wiki/Je7bwXTrEipna7kyKdUcQq6ZnKh";
    private static final String LOBBY_LOG_PERMISSION = "sitpk.world";
    private static final String LOBBY_LOG_FILE = "lobby-log.yml";
    private static final int MENU_SIZE = 27;

    // 一级界面槽位（4 个玩法图标，均匀分布在 row 1 中）
    private static final int SLOT_PKW = 10;
    private static final int SLOT_SEGMENT = 12;
    private static final int SLOT_RANKUP = 14;
    private static final int SLOT_ONLYSPRINT = 16;
    private static final int SLOT_LOBBY_LOG = 20;
    private static final int SLOT_PLAYER_INFO = 22;
    /** 练习模式入口图标 — 玩家信息后第 2 格（slot 24） */
    private static final int SLOT_PRACTICE = 24;

    /** 边框物品 */
    private static final ItemStack BORDER = createBorder();

    /**
     * type（英文标识）→ 中文显示名 映射表。
     */
    private static final Map<String, String> TYPE_TO_CHINESE = new HashMap<>();
    static {
        TYPE_TO_CHINESE.put("countup", "正计时");
        TYPE_TO_CHINESE.put("countdown", "倒计时");
        TYPE_TO_CHINESE.put("segment", "分段跑酷");
        TYPE_TO_CHINESE.put("onlysprint", "强制疾跑");
        TYPE_TO_CHINESE.put("rankup", "段位升级");
    }

    private final ParkourDataProvider dataProvider;
    private final JavaPlugin plugin;
    private final File lobbyLogFile;
    private final Object lobbyLogFileLock = new Object();
    private final Set<UUID> pendingLogEditors = ConcurrentHashMap.newKeySet();
    private volatile long lobbyLogSaveRevision;
    private String lobbyLog = "";

    // ==================== 颜色渲染工具（Paper 1.21.10 Component API） ====================

    /**
     * 将含 § 颜色代码的字符串转为 Adventure Component。
     * 用于 Paper 1.21.10 上替代已弃用的 setDisplayName(String)/setLore(List)。
     */
    public static Component colorize(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        return LegacyComponentSerializer.legacySection().deserialize(text)
                .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 将含 § 颜色代码的字符串列表转为 Component 列表。
     */
    public static List<Component> colorizeList(List<String> lore) {
        if (lore == null) return List.of();
        return lore.stream().map(ParkourMenu::colorize).toList();
    }

    /**
     * 按可见字符宽度换行，保留 § 格式码跨行延续。
     * 原始文本中的真实换行符（{@code \n}）或字面量字符串 {@code \n}（反斜杠+n）均视为强制换行，
     * 每段再按 maxChars 软换行。
     *
     * @param text     含 § 格式码的文本
     * @param maxChars 每行最大可见字符数（不计 § 码）
     * @return 换行后的行列表
     */
    public static List<String> wrapText(String text, int maxChars) {
        if (text == null || text.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();

        // 将文本中的字面量 \n（两个字符）替换为真实换行符，实现强制换行
        text = text.replace("\\n", "\n");
        // 先按显式换行切段
        String[] segments = text.split("\n", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                result.add("");
                continue;
            }
            // 对每段按可见字符宽度软换行
            result.addAll(wrapSingleLine(segment, maxChars));
        }
        return result;
    }

    /**
     * 对不含显式 \n 的单段文本按可见字符宽度换行，
     * 行首自动补上当前生效的 § 格式码以保持颜色延续。
     */
    private static List<String> wrapSingleLine(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        StringBuilder activeCodes = new StringBuilder(); // 当前生效的 § 格式码
        StringBuilder currentLine = new StringBuilder();
        int visibleCount = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '§' && i + 1 < text.length()) {
                String code = text.substring(i, i + 2);
                currentLine.append(code);
                // 更新生效格式码：同类型码替换旧值，追加到 activeCodes
                updateActiveCodes(activeCodes, code);
                i++; // 跳过格式字符
            } else {
                currentLine.append(c);
                visibleCount++;

                if (visibleCount >= maxChars) {
                    lines.add(currentLine.toString());
                    // 下一行开头补上当前生效的格式码
                    currentLine = new StringBuilder(activeCodes.toString());
                    visibleCount = 0;
                }
            }
        }

        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }
        return lines;
    }

    /**
     * 更新生效格式码缓存：同颜色码(a-f/0-9)替换，其他格式码(k-o/r)追加。
     */
    private static void updateActiveCodes(StringBuilder activeCodes, String code) {
        char codeChar = code.charAt(1);
        // 颜色码 (0-9, a-f) 或重置码 (r) — 替换之前同类型
        if (codeChar == 'r') {
            activeCodes.setLength(0);
            return;
        }
        if ((codeChar >= '0' && codeChar <= '9') || (codeChar >= 'a' && codeChar <= 'f')) {
            // 移除上一个颜色码（如果有）
            int lastColor = -1;
            for (int i = 0; i < activeCodes.length(); i += 2) {
                char cc = activeCodes.charAt(i + 1);
                if ((cc >= '0' && cc <= '9') || (cc >= 'a' && cc <= 'f') || cc == 'r') {
                    lastColor = i;
                }
            }
            if (lastColor >= 0) {
                activeCodes.delete(lastColor, lastColor + 2);
            }
        }
        // 格式码 (k-o) 直接追加
        activeCodes.append(code);
    }

    // ==================== 字段 ====================
    private ParkourMapListMenu mapListMenu;

    /**
     * 玩家统计数据提供者：UUID → [playtimeSeconds, jumps]。
     * 由 SITParkourLobby 在 onEnable 中注入，用于玩家信息图标展示。
     */
    private Function<UUID, long[]> statsProvider;

    /** 练习模式检测：返回 true 表示玩家正在练习，菜单不可用。默认永不拦截。 */
    private java.util.function.Predicate<Player> pracCheck = p -> false;

    private final DecimalFormat hoursFormat = new DecimalFormat("0.0",
            DecimalFormatSymbols.getInstance(Locale.US));

    public ParkourMenu(ParkourDataProvider dataProvider, JavaPlugin plugin) {
        this.dataProvider = dataProvider;
        this.plugin = plugin;
        this.lobbyLogFile = new File(plugin.getDataFolder(), LOBBY_LOG_FILE);
        loadLobbyLog();
    }

    /** 注入二级菜单引用（解决循环依赖）。 */
    public void setMapListMenu(ParkourMapListMenu mapListMenu) {
        this.mapListMenu = mapListMenu;
    }

    /** 注入玩家统计数据提供者（由 SITParkourLobby 调用）。 */
    public void setStatsProvider(Function<UUID, long[]> statsProvider) {
        this.statsProvider = statsProvider;
    }

    /** 注入练习模式检测器（由 SITParkourLobby 调用）。 */
    public void setPracCheck(java.util.function.Predicate<Player> pracCheck) {
        this.pracCheck = pracCheck != null ? pracCheck : p -> false;
    }

    /** 练习模式拦截提示消息前缀 */
    private static final String PRAC_BLOCKED_MSG = "&3&lSIT-Parkour &8| &c你正在练习模式中，无法打开菜单。";

    /**
     * 为指定玩家打开一级菜单。
     */
    public void openMainMenu(Player player) {
        if (pracCheck.test(player)) {
            player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacyAmpersand().deserialize(PRAC_BLOCKED_MSG));
            return;
        }

        dataProvider.refresh();

        Inventory inv = Bukkit.createInventory(new MenuHolder(), MENU_SIZE, MENU_TITLE);

        // 全边框填充
        fillBorder(inv);

        // 四个玩法图标
        inv.setItem(SLOT_PKW, createGameIcon(
                Material.GLOBE_BANNER_PATTERN,
                "§bPKW",
                """
                        §7基本玩法取自Minecraft小游戏比赛
                        §7MC Championship（MCC）中的小游戏——Parkour Warrior
                        §7每条赛道由若干小关组成
                        §7完成尽量多的关卡、挑战较高难度的终点，以取得更好的奖牌数/得分
                        §7（地图中会存在动态关或是别的什么，找对方法，把握时机，小心红温;w）""",
                dataProvider.getPKWMaps().size()
        ));

        inv.setItem(SLOT_SEGMENT, createGameIcon(
                Material.FIELD_MASONED_BANNER_PATTERN,
                "§bSegment",
                """
                        §7有一定存档点的跑酷地图
                        §7如果中途死亡可返回存档
                        §7不会损失所有进度""",
                dataProvider.getSegmentMaps().size()
        ));

        inv.setItem(SLOT_ONLYSPRINT, createGameIcon(
                Material.GUSTER_BANNER_PATTERN,
                "§bOnlysprint",
                """
                        §7强制疾跑模式
                        §7你无法停止疾跑，停止疾跑就会tpback
                        §7这意味着你不能断开前进和疾跑键，以及不能撞墙等
                        §7（较高难度，谨慎游玩）""",
                dataProvider.getOnlySprintMaps().size()
        ));

        inv.setItem(SLOT_RANKUP, createGameIcon(
                Material.CREEPER_BANNER_PATTERN,
                "§bRankup",
                """
                        §7全程一命，没有任何存档点的地图
                        §7如果在一些特殊地方死亡
                        §7比如ld（live or die）
                        §7会损失比较多的进度
                        §7（注意心态以及难度）""",
                dataProvider.getRankUpMaps().size()
        ));

        inv.setItem(SLOT_PLAYER_INFO, createPlayerInfoIcon(player));

        // 跑酷服指南入口（左键发送链接，管理员右键编辑）
        inv.setItem(SLOT_LOBBY_LOG, createLobbyLogIcon());

        // 练习模式入口（绿宝石图标，玩家信息后第 2 格）
        inv.setItem(SLOT_PRACTICE, createPracticeIcon());

        player.openInventory(inv);
    }

    // ==================== 图标构建 ====================

    /**
     * 创建玩法图标 ItemStack。
     *
     * @param material    旗帜图案材质
     * @param displayName 显示名称（含颜色码）
     * @param subtitle    玩法副标题（灰色说明）
     * @param mapCount    该玩法地图数量
     */
    private ItemStack createGameIcon(Material material, String displayName, String subtitle,
                                      int mapCount) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(displayName));

        List<String> lore = new ArrayList<>();
        lore.add("§7§m--------------------");
        lore.add("§7地图数量: §f" + mapCount + " §7个");
        lore.add("");
        // subtitle 按 \n 拆分为多行 lore（text block 的换行符即 \n）
        for (String line : subtitle.split("\n")) {
            lore.add(line);
        }
        lore.add("");
        lore.add("§e▶ 点击查看地图列表");

        meta.lore(colorizeList(lore));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 创建玩家信息头颅图标（显示该玩家皮肤 + 个人统计数据）。
     *
     * <p>数据来源：
     * <ul>
     *   <li>延迟、所在世界 → {@link Player} 直接获取。</li>
     *   <li>游玩时间、跳跃次数 → 通过 {@link #statsProvider} 从 SITParkourLobby 获取。</li>
     * </ul>
     * 若 statsProvider 未注入或查询失败，对应行显示 "—" 占位。
     */
    private ItemStack createPlayerInfoIcon(Player player) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        meta.setOwningPlayer(player);
        meta.displayName(colorize("§b§l" + player.getName()));

        // 实时数据
        int ping = player.getPing();
        String worldName = player.getWorld().getName();

        // 持久化统计数据
        long playtimeSeconds = 0;
        int jumps = 0;
        boolean statsAvailable = false;
        if (statsProvider != null) {
            long[] raw = statsProvider.apply(player.getUniqueId());
            if (raw != null && raw.length >= 2) {
                playtimeSeconds = raw[0];
                jumps = (int) raw[1];
                statsAvailable = true;
            }
        }

        String playtimeHours = hoursFormat.format(playtimeSeconds / 3600.0);

        List<String> lore = new ArrayList<>();
        lore.add("§7§m--------------------");
        lore.add("§7延迟: §f" + ping + " §7ms");
        club.sitmc.sitParkourLobby.data.WorldMeta wm = dataProvider.getWorldMetadata().get(worldName);
        String worldDisplayName = (wm != null && wm.displayName != null && !wm.displayName.isEmpty())
                ? wm.displayName
                : worldName;
        lore.add("§7所在世界: §f" + worldDisplayName);
        if (statsAvailable) {
            lore.add("§7跳跃次数: §f" + jumps);
            lore.add("§7游玩时间: §f" + playtimeHours + " §7小时");
        } else {
            lore.add("§7跳跃次数: §f—");
            lore.add("§7游玩时间: §f—");
        }

        meta.lore(colorizeList(lore));
        skull.setItemMeta(meta);
        return skull;
    }

    /**
     * 创建练习模式入口图标（绿宝石）。
     *
     * <p>点击后关闭菜单并执行 /prac 命令进入练习模式。
     * 退出练习请使用时钟（菜单时钟在 prac 状态下点击会执行 /unprac）。</p>
     */
    private ItemStack createPracticeIcon() {
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize("§a练习模式"));

        List<String> lore = new ArrayList<>();
        lore.add("§7点击进入练习模式");
        lore.add("§7点击菜单退出练习");
        meta.lore(colorizeList(lore));

        item.setItemMeta(meta);
        return item;
    }

    /** 创建跑酷服指南图标并展示已保存的日志正文。 */
    private ItemStack createLobbyLogIcon() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize("§e跑酷服指南🔗"));

        List<String> lore = new ArrayList<>();
        lore.add("§7§m--------------------");
        if (lobbyLog == null || lobbyLog.isBlank()) {
            lore.add("§7暂无日志");
        } else {
            lore.addAll(wrapText(lobbyLog, 35));
        }
        meta.lore(colorizeList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private void loadLobbyLog() {
        if (!lobbyLogFile.isFile()) {
            lobbyLog = "";
            return;
        }

        try {
            lobbyLog = YamlConfiguration.loadConfiguration(lobbyLogFile).getString("text", "");
        } catch (RuntimeException exception) {
            lobbyLog = "";
            plugin.getLogger().log(Level.WARNING, "无法读取大厅日志，暂按空日志处理。", exception);
        }
    }

    private void beginLobbyLogEdit(Player player) {
        if (!player.hasPermission(LOBBY_LOG_PERMISSION)) {
            return;
        }

        pendingLogEditors.add(player.getUniqueId());
        player.closeInventory();
        player.sendMessage(colorize("§e请在聊天中输入新的大厅日志内容。"));
    }

    private void saveLobbyLog(String text, UUID editorId) {
        lobbyLog = text;
        long revision = ++lobbyLogSaveRevision;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean saved = false;
            synchronized (lobbyLogFileLock) {
                if (revision != lobbyLogSaveRevision) {
                    return;
                }
                try {
                    saveLobbyLogFile(text);
                    saved = true;
                } catch (IOException | RuntimeException exception) {
                    plugin.getLogger().log(Level.SEVERE, "大厅日志保存失败。", exception);
                }
            }

            boolean saveSucceeded = saved;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (revision != lobbyLogSaveRevision) {
                        return;
                    }
                    Player editor = Bukkit.getPlayer(editorId);
                    if (editor != null) {
                        String message = saveSucceeded
                                ? "§a大厅日志已保存。"
                                : "§c大厅日志保存失败，请稍后重试。";
                        editor.sendMessage(colorize(message));
                    }
                });
            } catch (RuntimeException ignored) {
                // 插件停用期间不再向玩家发送异步保存结果。
            }
        });
    }

    private void saveLobbyLogFile(String text) throws IOException {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.isDirectory() && !dataFolder.mkdirs()) {
            throw new IOException("无法创建插件数据目录: " + dataFolder.getAbsolutePath());
        }

        File temporaryFile = new File(dataFolder, LOBBY_LOG_FILE + ".tmp");
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("text", text);
        configuration.save(temporaryFile);

        Files.move(temporaryFile.toPath(), lobbyLogFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    // ==================== 边框 ====================

    /**
     * 为 Inventory 所有槽位填充边框物品。
     */
    static void fillBorder(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, BORDER);
        }
    }

    private static ItemStack createBorder() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(colorize(" "));
        item.setItemMeta(meta);
        return item;
    }

    // ==================== 点击事件 ====================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot >= event.getInventory().getSize()) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }

        if (slot == SLOT_LOBBY_LOG) {
            if (event.isRightClick()) {
                beginLobbyLogEdit(player);
            } else if (event.isLeftClick()) {
                player.showDialog(Dialog.create(builder -> builder.empty()
                        .base(DialogBase.builder(Component.text("跑酷服指南🔗")).build())
                        .type(DialogType.notice(ActionButton.builder(Component.text("打开指南"))
                                .action(DialogAction.staticAction(ClickEvent.openUrl(LOBBY_LOG_URL)))
                                .build()))));
            }
            return;
        }

        if (mapListMenu == null) return;

        switch (slot) {
            case SLOT_PKW -> mapListMenu.openMapList(player, ParkourGameType.PKW, 0);
            case SLOT_SEGMENT -> mapListMenu.openMapList(player, ParkourGameType.SEGMENT, 0);
            case SLOT_ONLYSPRINT -> mapListMenu.openMapList(player, ParkourGameType.ONLYSPRINT, 0);
            case SLOT_RANKUP -> mapListMenu.openMapList(player, ParkourGameType.RANKUP, 0);
            case SLOT_PRACTICE -> {
                player.closeInventory();
                boolean success = player.performCommand("prac");
                if (!success) {
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                            .deserialize("&3&lSIT-Parkour &8| &c无法执行进入练习模式命令，"
                                    + "请确认 SITSegment 已安装。"));
                }
            }
            // SLOT_PLAYER_INFO — 暂不处理（占位）
        }
    }

    @EventHandler
    public void onLogEditorChat(AsyncChatEvent event) {
        UUID editorId = event.getPlayer().getUniqueId();
        if (!pendingLogEditors.remove(editorId)) {
            return;
        }

        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player editor = Bukkit.getPlayer(editorId);
            if (editor == null || !editor.hasPermission(LOBBY_LOG_PERMISSION)) {
                return;
            }
            saveLobbyLog(text, editorId);
        });
    }

    @EventHandler
    public void onLogEditorQuit(PlayerQuitEvent event) {
        pendingLogEditors.remove(event.getPlayer().getUniqueId());
    }

    // ==================== 中文显示映射 ====================

    /**
     * 将英文 type 映射为中文显示名。
     */
    public static String toChineseType(String englishType) {
        return TYPE_TO_CHINESE.getOrDefault(englishType, englishType);
    }

    // ==================== 自定义 Holder ====================

    private static class MenuHolder implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return null;
        }
    }
}
