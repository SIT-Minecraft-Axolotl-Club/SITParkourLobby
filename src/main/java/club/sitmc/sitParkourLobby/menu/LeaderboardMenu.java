package club.sitmc.sitParkourLobby.menu;

import club.sitmc.sitParkourLobby.data.ParkourDataProvider;
import club.sitmc.sitParkourLobby.data.ParkourGameType;
import club.sitmc.sitParkourLobby.data.SkinCacheManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.logging.Logger;

/**
 * 排行榜 GUI。
 *
 * <h3>多榜布局（54 格 chest）</h3>
 *
 * <p><b>正计时 PKW（3 榜并列）</b></p>
 * <pre>
 *  Row 1 (slots  9-17): [B]标[1st][2nd][3rd][我][B][B][B]  标准榜
 *  Row 2 (slots 18-26): [B]标[1st][2nd][3rd][我][B][B][B]  进阶榜
 *  Row 3 (slots 27-35): [B]标[1st][2nd][3rd][我][B][B][B]  卓越榜
 * </pre>
 *
 * <p><b>倒计时 / Segment / OnlySprint（单榜居中）</b></p>
 * <pre>
 *  Row 2 (slots 18-26): [B][B][B]标[1st][2nd][3rd][我][B]
 * </pre>
 *
 * <p>其余所有槽位用灰色玻璃板（GRAY_STAINED_GLASS_PANE）边框填充。
 * 返回按钮在左下角 slot 45。</p>
 */
public class LeaderboardMenu implements Listener {

    private static final int MENU_SIZE = 45;
    private static final int SLOT_BACK = 36;

    private static final String DATA_UNAVAILABLE = "§c排行数据不可用";
    private static final String NO_RECORD = "§7暂无记录";

    /** 临时诊断日志开关 — 验证榜单数据正确后设为 false 即可关闭。 */
    private static final boolean DEBUG_LOG = false;

    private final ParkourDataProvider dataProvider;
    private final SkinCacheManager skinCacheManager;
    private final Logger logger;
    private MapDetailMenu detailMenu;

    /** 边框物品 */
    private static final ItemStack BORDER = createBorder();

    public LeaderboardMenu(ParkourDataProvider dataProvider, SkinCacheManager skinCacheManager,
                           Logger logger) {
        this.dataProvider = dataProvider;
        this.skinCacheManager = skinCacheManager;
        this.logger = logger;
    }

    public void setDetailMenu(MapDetailMenu detailMenu) {
        this.detailMenu = detailMenu;
    }

    // ==================== 打开排行榜 ====================

    /**
     * 打开排行榜界面。
     */
    public void openLeaderboard(Player player, String mapId, ParkourGameType gameType,
                                 String type, Map<String, String> mapData) {
        String pluginName = ParkourDataProvider.resolvePlugin(gameType);

        Map<String, List<Map<String, String>>> leaderboards =
                dataProvider.getLeaderboards(pluginName, mapId, 3);
        Map<String, Map<String, String>> playerRanks =
                dataProvider.getPlayerRanks(pluginName, mapId, player.getUniqueId());

        if (DEBUG_LOG) {
            logger.info("[LB-Debug] getLeaderboards 返回 board 列表: " + leaderboards.keySet());
            logger.info("[LB-Debug] getPlayerRanks 返回 board 列表: " + playerRanks.keySet());
            for (var entry : leaderboards.entrySet()) {
                logger.info("[LB-Debug] board=" + entry.getKey() + " 条目数=" + entry.getValue().size()
                        + " 内容=" + entry.getValue());
            }
            for (var entry : playerRanks.entrySet()) {
                logger.info("[LB-Debug] myRank board=" + entry.getKey() + " 数据=" + entry.getValue());
            }
        }

        List<String> boardNames = resolveBoardNames(type);

        String title = "§b§l排行榜";
        if (title.length() > 128) title = title.substring(0, 128);

        Inventory inv = Bukkit.createInventory(
                new LBHolder(mapId, gameType, type, mapData), MENU_SIZE, title);

        // 全边框填充
        ParkourMenu.fillBorder(inv);

        renderBoards(inv, boardNames, leaderboards, playerRanks, player);

        // 返回按钮 — 左下角
        inv.setItem(SLOT_BACK, backButton());

        player.openInventory(inv);
    }

    // ==================== Board 名解析 ====================

    private List<String> resolveBoardNames(String type) {
        return switch (type) {
            case ParkourDataProvider.TYPE_COUNTUP ->
                    List.of(ParkourDataProvider.BOARD_STANDARD,
                            ParkourDataProvider.BOARD_ADVANCE,
                            ParkourDataProvider.BOARD_EXPECT);
            case ParkourDataProvider.TYPE_COUNTDOWN ->
                    List.of(ParkourDataProvider.BOARD_COUNTDOWN);
            default ->
                    List.of(ParkourDataProvider.BOARD_MAIN);
        };
    }

    // ==================== 渲染 ====================

    private void renderBoards(Inventory inv, List<String> boardNames,
                              Map<String, List<Map<String, String>>> leaderboards,
                              Map<String, Map<String, String>> playerRanks,
                              Player player) {
        if (boardNames.size() == 3) {
            renderThreeBoards(inv, boardNames, leaderboards, playerRanks, player);
        } else {
            renderSingleBoard(inv, boardNames.get(0), leaderboards, playerRanks, player);
        }
    }

    private void renderThreeBoards(Inventory inv, List<String> boardNames,
                                   Map<String, List<Map<String, String>>> leaderboards,
                                   Map<String, Map<String, String>> playerRanks,
                                   Player player) {
        int[] titleSlots = {10, 19, 28};

        for (int i = 0; i < boardNames.size(); i++) {
            String boardName = boardNames.get(i);
            int titleSlot = titleSlots[i];
            renderOneBoard(inv, boardName,
                    leaderboards.getOrDefault(boardName, Collections.emptyList()),
                    playerRanks.get(boardName),
                    player, titleSlot);
        }
    }

    private void renderSingleBoard(Inventory inv, String boardName,
                                   Map<String, List<Map<String, String>>> leaderboards,
                                   Map<String, Map<String, String>> playerRanks,
                                   Player player) {
        renderOneBoard(inv, boardName,
                leaderboards.getOrDefault(boardName, Collections.emptyList()),
                playerRanks.get(boardName),
                player, 19);
    }

    /**
     * 渲染单个榜：标题 + 前3名头颅 + 我的名次。
     */
    private void renderOneBoard(Inventory inv, String boardName,
                                List<Map<String, String>> entries,
                                Map<String, String> myData,
                                Player player, int titleSlot) {
        String chineseTitle = ParkourDataProvider.boardChineseName(boardName);

        inv.setItem(titleSlot, boardTitleItem(chineseTitle, boardName));

        for (int i = 0; i < 3; i++) {
            int slot = titleSlot + 1 + i;
            if (i < entries.size()) {
                inv.setItem(slot, createRankHead(entries.get(i), i + 1));
            } else {
                inv.setItem(slot, emptyRankSlot("§7虚位以待"));
            }
        }

        int mySlot = titleSlot + 5;
        if (myData != null && !myData.isEmpty()) {
            inv.setItem(mySlot, createMyRankHead(player, myData, chineseTitle));
        } else {
            inv.setItem(mySlot, emptyRankSlot(NO_RECORD));
        }
    }

    // ==================== 图标构建 ====================

    private static ItemStack boardTitleItem(String chineseTitle, String boardName) {
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ParkourMenu.colorize("§b§l" + chineseTitle));
        meta.lore(ParkourMenu.colorizeList(List.of("§7" + boardName)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createRankHead(Map<String, String> entry, int rank) {
        if (DEBUG_LOG) {
            logger.info("[LB-Debug] 榜单条目原始 Map: " + entry);
        }

        String playerName = entry.getOrDefault("player", "???");
        String score = entry.getOrDefault("score", "???");
        String uuidStr = entry.get("uuid");

        ItemStack head;
        if (!playerName.equals("???")) {
            if (uuidStr != null && !uuidStr.isEmpty()) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    head = skinCacheManager.getSkullItem(uuid, playerName);
                } catch (IllegalArgumentException e) {
                    head = skinCacheManager.getSkullItemByName(playerName);
                }
            } else {
                head = skinCacheManager.getSkullItemByName(playerName);
            }
        } else {
            head = new ItemStack(Material.PLAYER_HEAD);
        }

        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.displayName(ParkourMenu.colorize("§6#" + rank + " §b" + playerName));
        List<String> lore = new ArrayList<>();
        lore.add("§7§m--------------------");
        lore.add("§7成绩: §e" + score);
        meta.lore(ParkourMenu.colorizeList(lore));
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack createMyRankHead(Player player, Map<String, String> myData, String boardTitle) {
        String rank = myData.getOrDefault("rank", "?");
        String score = myData.getOrDefault("score", "???");

        ItemStack head = skinCacheManager.getSkullItem(player);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        meta.displayName(ParkourMenu.colorize("§d我的名次 §7(" + boardTitle + ")"));
        meta.lore(ParkourMenu.colorizeList(List.of(
                "§7§m--------------------",
                "§7排名: §e#" + rank,
                "§7成绩: §e" + score,
                "§7玩家: §b" + player.getName()
        )));
        head.setItemMeta(meta);
        return head;
    }

    private static ItemStack emptyRankSlot(String message) {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ParkourMenu.colorize(message));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack backButton() {
        ItemStack item = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ParkourMenu.colorize("§c« 返回"));
        meta.lore(ParkourMenu.colorizeList(List.of("§8返回地图操作界面")));
        item.setItemMeta(meta);
        return item;
    }

    // ==================== 边框 ====================

    private static ItemStack createBorder() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ParkourMenu.colorize(" "));
        item.setItemMeta(meta);
        return item;
    }

    // ==================== 点击事件 ====================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof LBHolder)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot >= event.getInventory().getSize()) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        if (slot == SLOT_BACK && detailMenu != null) {
            if (event.getInventory().getHolder() instanceof LBHolder holder && holder.mapData != null) {
                detailMenu.openDetail(player, holder.mapData, holder.gameType);
            }
            return;
        }
    }

    // ==================== 自定义 Holder ====================

    static class LBHolder implements InventoryHolder {
        final String mapId;
        final ParkourGameType gameType;
        final String type;
        final Map<String, String> mapData;

        LBHolder(String mapId, ParkourGameType gameType, String type, Map<String, String> mapData) {
            this.mapId = mapId;
            this.gameType = gameType;
            this.type = type;
            this.mapData = mapData;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return null;
        }
    }
}
