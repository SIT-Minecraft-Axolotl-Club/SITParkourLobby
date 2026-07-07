package club.sitmc.sitParkourLobby.menu;

import club.sitmc.sitParkourLobby.data.MapStatus;
import club.sitmc.sitParkourLobby.data.ParkourDataProvider;
import club.sitmc.sitParkourLobby.data.ParkourGameType;
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
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * 二级地图列表 GUI。
 *
 * <h3>界面布局（54 格 chest）</h3>
 * <pre>
 *  Row 0-4 (slots  0-44): 地图项（FILLED_MAP），按页排列，空位用边框填充
 *  Row 5   (slots 45-53): [↩][B][B][◀][B][▶][B][B][B]
 *                         45=返回  47=上一页  49=下一页
 *                         B = 灰色玻璃板边框
 * </pre>
 *
 * <h3>OP / 非OP 区别展示</h3>
 * <ul>
 *   <li><b>OP</b>：所有地图显示完整信息，均可点击进入三级。</li>
 *   <li><b>非OP CLOSED</b>：显示完整信息但不可点击进入。</li>
 *   <li><b>非OP UNRELEASED</b>：ItemStack 层面打码，不暴露真实名称/类型/作者。</li>
 * </ul>
 */
public class ParkourMapListMenu implements Listener {

    private static final int MENU_SIZE = 54;
    private static final int MAPS_PER_PAGE = 45;

    // Row 5 navigation slots
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREV = 47;
    private static final int SLOT_NEXT = 49;

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
    private ParkourMenu mainMenu;
    private MapDetailMenu detailMenu;

    public ParkourMapListMenu(ParkourDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    public void setMainMenu(ParkourMenu mainMenu) {
        this.mainMenu = mainMenu;
    }

    public void setDetailMenu(MapDetailMenu detailMenu) {
        this.detailMenu = detailMenu;
    }

    // ==================== 打开菜单 ====================

    /**
     * 为指定玩家打开某玩法的地图列表二级 GUI。
     */
    public void openMapList(Player player, ParkourGameType gameType, int page) {
        List<Map<String, String>> rawMaps = new ArrayList<>(gameType.getMaps(dataProvider));
        MapStatus.sort(rawMaps);

        boolean isOp = player.isOp();
        int totalPages = Math.max(1, (int) Math.ceil((double) rawMaps.size() / MAPS_PER_PAGE));
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

        String title = gameType.coloredName + " §8- §7地图列表 §8(§f" + rawMaps.size() + "§8)";
        if (title.length() > 128) title = title.substring(0, 128);

        Inventory inv = Bukkit.createInventory(
                new MapListHolder(gameType, page, rawMaps, isOp), MENU_SIZE, title);

        renderPage(inv, page, rawMaps, isOp, totalPages);
        player.openInventory(inv);
    }

    // ==================== 页面渲染 ====================

    private void renderPage(Inventory inv, int page, List<Map<String, String>> maps,
                            boolean isOp, int totalPages) {
        // 先全边框
        ParkourMenu.fillBorder(inv);

        int start = page * MAPS_PER_PAGE;
        int end = Math.min(start + MAPS_PER_PAGE, maps.size());

        // 地图项（slots 0-44）
        for (int i = start; i < end; i++) {
            int slot = i - start;
            inv.setItem(slot, createMapIcon(maps.get(i), isOp));
        }

        // 导航行（覆盖 row 5 边框）
        // 返回按钮 — 左下角
        inv.setItem(SLOT_BACK, backButton());

        // 上一页
        if (page > 0) {
            inv.setItem(SLOT_PREV, prevButton(page, totalPages));
        }

        // 下一页
        if (page < totalPages - 1) {
            inv.setItem(SLOT_NEXT, nextButton(page, totalPages));
        }
    }

    // ==================== 地图图标构建 ====================

    private ItemStack createMapIcon(Map<String, String> map, boolean isOp) {
        String name = map.getOrDefault("name", "???");
        String displayName = map.getOrDefault("displayName", name);
        String type = map.getOrDefault("type", "");
        String typeName = map.getOrDefault("typeName", "");
        String typeChinese = toChineseType(type);
        String author = map.getOrDefault("author", "");
        String statusKey = map.getOrDefault("status", "");
        MapStatus status = MapStatus.fromKey(statusKey);
        String iconMaterialName = map.getOrDefault("icon", "");
        String difficulty = map.getOrDefault("difficulty", "");
        String introduction = map.getOrDefault("introduction", "");

        boolean unreleasedForNonOp = !isOp && status == MapStatus.UNRELEASED;
        boolean closedForNonOp = !isOp && status == MapStatus.CLOSED;
        boolean canEnter = isOp || (status != MapStatus.CLOSED && status != MapStatus.UNRELEASED);

        // 图标材质：UNRELEASED 非OP 强制 BARRIER；否则用自定义图标，无效则回退 FILLED_MAP
        Material iconMat;
        if (unreleasedForNonOp) {
            iconMat = Material.BARRIER;
        } else {
            iconMat = resolveIconMaterial(iconMaterialName);
        }

        ItemStack item = new ItemStack(iconMat);
        ItemMeta meta = item.getItemMeta();

        // 展示名：UNRELEASED 非OP 强制打码 "???"（使用 Component API 确保 § 生效）
        if (unreleasedForNonOp) {
            meta.displayName(ParkourMenu.colorize("§7???"));
        } else {
            String title = (!displayName.isEmpty() && !displayName.equals(name))
                    ? displayName : name;
            // title 已存储为 § 格式，直接用 colorize 渲染
            if (!displayName.isEmpty() && !displayName.equals(name)) {
                meta.displayName(ParkourMenu.colorize(title));
            } else {
                meta.displayName(ParkourMenu.colorize("§b" + title));
            }
        }

        List<String> lore = new ArrayList<>();
        lore.add("§7§m--------------------");

        if (unreleasedForNonOp) {
            // 非OP UNRELEASED：仅显示状态，隐藏所有真实信息（含 introduction）
            lore.add("§7状态: " + status.colorCode + status.chineseName);
        } else {
            // 类型行：优先使用自定义 typeName（已存 § 格式，直接拼接）
            String typeDisplay = (!typeName.isEmpty()) ? typeName : typeChinese;
            lore.add("§7类型: " + typeDisplay);

            lore.add("§7作者: " + (author.isEmpty() ? "—" : author));
            lore.add("§7状态: " + status.colorCode + status.chineseName);

            // 难度行：仅设置后显示
            if (!difficulty.isEmpty()) {
                lore.add("§7难度: " + difficulty);
            }

            // 简介：仅设置后显示，在所有信息行之下，空一行间隔，无标签前缀，默认灰色，超过12字符自动换行
            if (!introduction.isEmpty()) {
                lore.add(""); // 空行间隔
                String introText = introduction.startsWith("§") ? introduction : "§7" + introduction;
                lore.addAll(ParkourMenu.wrapText(introText, 12));
            }
        }

        lore.add("");

        if (canEnter) {
            lore.add("§e▶ 点击进入");
        } else if (closedForNonOp) {
            lore.add("§c✘ 已关闭，无法进入");
        } else {
            lore.add("§7✘ 暂未开放");
        }

        meta.lore(ParkourMenu.colorizeList(lore));
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

    // ==================== 导航按钮 ====================

    private static ItemStack backButton() {
        ItemStack item = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ParkourMenu.colorize("§c« 返回"));
        meta.lore(ParkourMenu.colorizeList(List.of("§8返回玩法选择")));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack prevButton(int page, int totalPages) {
        ItemStack item = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ParkourMenu.colorize("§a« 上一页"));
        meta.lore(ParkourMenu.colorizeList(List.of("§8第 " + page + " / " + totalPages + " 页")));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack nextButton(int page, int totalPages) {
        ItemStack item = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ParkourMenu.colorize("§a下一页 »"));
        meta.lore(ParkourMenu.colorizeList(List.of("§8第 " + (page + 2) + " / " + totalPages + " 页")));
        item.setItemMeta(meta);
        return item;
    }

    // ==================== 点击事件 ====================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MapListHolder holder)) {
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

        // 导航按钮
        if (slot == SLOT_BACK) {
            if (mainMenu != null) {
                mainMenu.openMainMenu(player);
            }
            return;
        }

        if (slot == SLOT_PREV && holder.page > 0) {
            openMapList(player, holder.gameType, holder.page - 1);
            return;
        }

        if (slot == SLOT_NEXT && holder.page < holder.totalPages() - 1) {
            openMapList(player, holder.gameType, holder.page + 1);
            return;
        }

        // 地图项点击
        if (slot < MAPS_PER_PAGE) {
            int mapIndex = holder.page * MAPS_PER_PAGE + slot;
            if (mapIndex < holder.sortedMaps.size()) {
                handleMapClick(player, holder.sortedMaps.get(mapIndex), holder.isOp, holder.gameType);
            }
        }
    }

    private void handleMapClick(Player player, Map<String, String> map, boolean isOp,
                                 ParkourGameType gameType) {
        MapStatus status = MapStatus.fromKey(map.getOrDefault("status", ""));

        if (!isOp) {
            if (status == MapStatus.CLOSED) {
                player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacyAmpersand().deserialize("&3&lSIT-Parkour &8| &c该地图已关闭，无法进入。"));
                return;
            }
            if (status == MapStatus.UNRELEASED) {
                player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacyAmpersand().deserialize("&3&lSIT-Parkour &8| &7该地图暂未开放，无法进入。"));
                return;
            }
        }

        if (detailMenu != null) {
            detailMenu.openDetail(player, map, gameType);
        }
    }

    // ==================== 中文映射 ====================

    private static String toChineseType(String englishType) {
        return TYPE_TO_CHINESE.getOrDefault(englishType, englishType);
    }

    // ==================== 边框 ====================

    private static ItemStack createBorder() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ParkourMenu.colorize(" "));
        item.setItemMeta(meta);
        return item;
    }

    // ==================== 自定义 Holder ====================

    static class MapListHolder implements InventoryHolder {
        final ParkourGameType gameType;
        final int page;
        final List<Map<String, String>> sortedMaps;
        final boolean isOp;

        MapListHolder(ParkourGameType gameType, int page, List<Map<String, String>> sortedMaps, boolean isOp) {
            this.gameType = gameType;
            this.page = page;
            this.sortedMaps = sortedMaps;
            this.isOp = isOp;
        }

        int totalPages() {
            return Math.max(1, (int) Math.ceil((double) sortedMaps.size() / MAPS_PER_PAGE));
        }

        @Override
        public @NotNull Inventory getInventory() {
            return null;
        }
    }
}
