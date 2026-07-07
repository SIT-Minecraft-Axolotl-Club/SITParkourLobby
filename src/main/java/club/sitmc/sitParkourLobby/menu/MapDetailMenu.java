package club.sitmc.sitParkourLobby.menu;

import club.sitmc.sitParkourLobby.data.MapStatus;
import club.sitmc.sitParkourLobby.data.ParkourDataProvider;
import club.sitmc.sitParkourLobby.data.ParkourGameType;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 三级界面 —— 单张地图操作（传送 + 查看排行榜）。
 *
 * <h3>界面布局（27 格 chest）</h3>
 * <pre>
 *  Row 0 (slots  0- 8): [B][B][B][B][📋][B][B][B][B]  地图信息 (4)
 *  Row 1 (slots  9-17): [B][  ][🏃][  ][📊][  ][  ][  ][B]  传送(11) / 排行(13)
 *  Row 2 (slots 18-26): [↩][B][B][B][B][B][B][B][B]  返回 (18)
 * </pre>
 */
public class MapDetailMenu implements Listener {

    private static final int MENU_SIZE = 27;

    private static final int SLOT_INFO = 15;
    private static final int SLOT_TELEPORT = 11;
    private static final int SLOT_LEADERBOARD = 13;
    private static final int SLOT_BACK = 18;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final ParkourDataProvider dataProvider;
    private ParkourMapListMenu mapListMenu;
    private LeaderboardMenu leaderboardMenu;

    public MapDetailMenu(ParkourDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    public void setMapListMenu(ParkourMapListMenu mapListMenu) {
        this.mapListMenu = mapListMenu;
    }

    public void setLeaderboardMenu(LeaderboardMenu leaderboardMenu) {
        this.leaderboardMenu = leaderboardMenu;
    }

    // ==================== 打开菜单 ====================

    /**
     * 打开某张地图的三级操作界面。
     */
    public void openDetail(Player player, Map<String, String> map, ParkourGameType gameType) {
        String name = map.getOrDefault("name", "???");
        String displayName = map.getOrDefault("displayName", name);
        String title = (!displayName.isEmpty() && !displayName.equals(name))
                ? displayName : name;
        if (title.length() > 128) title = title.substring(0, 128);

        String fullTitle = "§b§l" + title;
        Inventory inv = Bukkit.createInventory(
                new DetailHolder(map, gameType), MENU_SIZE, fullTitle);

        // 全边框填充
        ParkourMenu.fillBorder(inv);

        // 地图信息图标
        inv.setItem(SLOT_INFO, createInfoIcon(map));

        // 传送按钮
        inv.setItem(SLOT_TELEPORT, createTeleportIcon());

        // 排行榜按钮
        inv.setItem(SLOT_LEADERBOARD, createLeaderboardIcon());

        // 返回按钮 — 左下角
        inv.setItem(SLOT_BACK, backButton());

        player.openInventory(inv);
    }

    // ==================== 图标构建 ====================

    private ItemStack createInfoIcon(Map<String, String> map) {
        String iconMaterialName = map.getOrDefault("icon", "");
        Material iconMat = resolveIconMaterial(iconMaterialName);
        ItemStack item = new ItemStack(iconMat);
        ItemMeta meta = item.getItemMeta();

        String name = map.getOrDefault("name", "???");
        String displayName = map.getOrDefault("displayName", name);
        String type = map.getOrDefault("type", "");
        String typeName = map.getOrDefault("typeName", "");
        String typeChinese = ParkourMenu.toChineseType(type);
        String author = map.getOrDefault("author", "");
        String statusKey = map.getOrDefault("status", "");
        MapStatus status = MapStatus.fromKey(statusKey);
        String difficulty = map.getOrDefault("difficulty", "");
        String introduction = map.getOrDefault("introduction", "");

        // 标题：优先 displayName（已存 § 格式）
        String title = (!displayName.isEmpty() && !displayName.equals(name))
                ? displayName : name;
        meta.displayName(ParkourMenu.colorize("§b§l" + title));

        List<String> lore = new ArrayList<>();
        lore.add("§7§m--------------------");

        // 类型行：优先 typeName（已存 § 格式）
        String typeDisplay = (!typeName.isEmpty()) ? typeName : typeChinese;
        lore.add("§7玩法: §f" + typeDisplay);

        lore.add("§7作者: §f" + (author.isEmpty() ? "—" : author));
        lore.add("§7状态: " + status.colorCode + status.chineseName);

        // 难度行：仅设置后显示
        if (!difficulty.isEmpty()) {
            lore.add("§7难度: §f" + difficulty);
        }

        // 简介：仅设置后显示，空一行间隔，无标签前缀，默认灰色，超过12字符自动换行
        if (!introduction.isEmpty()) {
            lore.add("");
            String introText = introduction.startsWith("§") ? introduction : "§7" + introduction;
            lore.addAll(ParkourMenu.wrapText(introText, 12));
        }

        meta.lore(ParkourMenu.colorizeList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createTeleportIcon() {
        ItemStack item = new ItemStack(Material.ENDER_PEARL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ParkourMenu.colorize("§b传送"));

        List<String> lore = new ArrayList<>();
        lore.add("§7§m--------------------");
        lore.add("§7传送到该地图世界出生点");
        lore.add("");
        lore.add("§e▶ 点击传送");

        meta.lore(ParkourMenu.colorizeList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createLeaderboardIcon() {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ParkourMenu.colorize("§b查看排行"));

        List<String> lore = new ArrayList<>();
        lore.add("§7§m--------------------");
        lore.add("§7查看该地图排行榜");
        lore.add("");
        lore.add("§e▶ 点击查看排行");

        meta.lore(ParkourMenu.colorizeList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack backButton() {
        ItemStack item = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ParkourMenu.colorize("§c« 返回"));
        meta.lore(ParkourMenu.colorizeList(List.of("§8返回地图列表")));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 解析 icon 材质名字符串为 Material，无效或空返回默认 FILLED_MAP。
     */
    private static Material resolveIconMaterial(String materialName) {
        if (materialName == null || materialName.isEmpty()) {
            return Material.FILLED_MAP;
        }
        try {
            Material mat = Material.matchMaterial(materialName);
            if (mat != null && mat.isItem()) {
                return mat;
            }
        } catch (Exception ignored) {
        }
        return Material.FILLED_MAP;
    }

    // ==================== 点击事件 ====================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof DetailHolder holder)) {
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

        if (slot == SLOT_BACK) {
            if (mapListMenu != null) {
                mapListMenu.openMapList(player, holder.gameType, 0);
            }
            return;
        }

        if (slot == SLOT_TELEPORT) {
            handleTeleport(player, holder.map, holder.gameType);
            return;
        }

        if (slot == SLOT_LEADERBOARD) {
            if (leaderboardMenu != null) {
                String mapId = holder.map.getOrDefault("id", "");
                String type = holder.map.getOrDefault("type", "");
                leaderboardMenu.openLeaderboard(player, mapId, holder.gameType, type, holder.map);
            }
            return;
        }
    }

    // ==================== 传送逻辑 ====================

    private void handleTeleport(Player player, Map<String, String> map, ParkourGameType gameType) {
        String worldName = map.getOrDefault("world", "");
        String rawName = map.getOrDefault("name", "???");
        String customDisplay = map.getOrDefault("displayName", "");
        // 展示名优先：有自定义 displayName 则用它，否则用世界名
        String showName = (!customDisplay.isEmpty() && !customDisplay.equals(rawName))
                ? customDisplay : rawName;
        MapStatus status = MapStatus.fromKey(map.getOrDefault("status", ""));
        boolean isOp = player.isOp();

        if (!isOp && (status == MapStatus.CLOSED || status == MapStatus.UNRELEASED)) {
            player.sendMessage(LEGACY.deserialize("&3&lSIT-Parkour &8| &c该地图不可进入。"));
            return;
        }

        World targetWorld = Bukkit.getWorld(worldName);
        if (targetWorld == null) {
            player.sendMessage(LEGACY.deserialize("&3&lSIT-Parkour &8| &c目标世界不可用 &7(" + worldName + " 未加载或不存在)"));
            return;
        }

        if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.ADVENTURE) {
            player.setGameMode(GameMode.ADVENTURE);
        }
        player.teleport(targetWorld.getSpawnLocation());
        player.sendMessage(LEGACY.deserialize("&3&lSIT-Parkour &8| &a已传送到 &b" + showName));
        player.closeInventory();
    }

    // ==================== 自定义 Holder ====================

    static class DetailHolder implements InventoryHolder {
        final Map<String, String> map;
        final ParkourGameType gameType;

        DetailHolder(Map<String, String> map, ParkourGameType gameType) {
            this.map = map;
            this.gameType = gameType;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return null;
        }
    }
}
