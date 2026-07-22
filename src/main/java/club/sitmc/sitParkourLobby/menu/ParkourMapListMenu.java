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
     *
     * <p>排列逻辑：
     * <ol>
     *   <li>将地图拆分为固定地图（含 fixedPage/fixedSlot）和自动排序地图。</li>
     *   <li>仅对自动地图按 {@link MapStatus#sort(List)} 排序（固定地图不参与）。</li>
     *   <li>计算总页数 = max(1, 最大固定页码, 自动地图所需页码)。</li>
     *   <li>调用 {@link #buildSlotMap(int, List, List)} 生成当前页的 slot→map 映射。</li>
     * </ol>
     */
    public void openMapList(Player player, ParkourGameType gameType, int page) {
        List<Map<String, String>> rawMaps = new ArrayList<>(gameType.getMaps(dataProvider));

        // 拆分为固定地图和自动排序地图
        List<Map<String, String>> fixedMaps = new ArrayList<>();
        List<Map<String, String>> autoMaps = new ArrayList<>();
        for (Map<String, String> map : rawMaps) {
            if (map.containsKey("fixedPage") && map.containsKey("fixedSlot")) {
                fixedMaps.add(map);
            } else {
                autoMaps.add(map);
            }
        }

        // 仅对自动排序地图执行原排序规则
        MapStatus.sort(autoMaps);

        boolean isOp = player.isOp();

        // 计算最大固定页码
        int maxFixedPage = 0;
        for (Map<String, String> fm : fixedMaps) {
            try {
                int fp = Integer.parseInt(fm.get("fixedPage"));
                if (fp > maxFixedPage) maxFixedPage = fp;
            } catch (NumberFormatException ignored) {
            }
        }

        // 计算自动地图所需页数
        int autoPageCount = countAutoPages(autoMaps, fixedMaps);
        int totalPages = Math.max(1, Math.max(maxFixedPage, autoPageCount));
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

        // 构建当前页的 slot→map 映射
        Map<Integer, Map<String, String>> slotMap = buildSlotMap(page, fixedMaps, autoMaps);

        String title = gameType.coloredName + " §8- §7地图列表 §8(§f" + rawMaps.size() + "§8)";
        if (title.length() > 128) title = title.substring(0, 128);

        Inventory inv = Bukkit.createInventory(
                new MapListHolder(gameType, page, slotMap, isOp, totalPages), MENU_SIZE, title);

        renderPage(inv, page, slotMap, isOp, totalPages);
        player.openInventory(inv);
    }

    // ==================== 分页计算 ====================

    /**
     * 计算自动排序地图所需的页数。
     *
     * <p>模拟逐页填充：每页可用 content slot 数 = MAPS_PER_PAGE - 该页上的固定地图数。
     * 持续直到所有自动地图都分配完毕。</p>
     *
     * @param autoMaps   自动排序的地图列表
     * @param fixedMaps  固定地图列表
     * @return 自动地图所需的总页数（可能为 0）
     */
    private int countAutoPages(List<Map<String, String>> autoMaps, List<Map<String, String>> fixedMaps) {
        if (autoMaps.isEmpty()) return 0;
        int remaining = autoMaps.size();
        int page = 1; // 1-indexed
        int safety = 0;
        while (remaining > 0 && safety < 1000) {
            int fixedOnThisPage = 0;
            for (Map<String, String> fm : fixedMaps) {
                try {
                    if (Integer.parseInt(fm.get("fixedPage")) == page) {
                        fixedOnThisPage++;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            int slotsForAuto = MAPS_PER_PAGE - fixedOnThisPage;
            if (slotsForAuto > 0) {
                remaining -= slotsForAuto;
            }
            page++;
            safety++;
        }
        return page - 1; // page 在最后处理后自增，减回即为最后一页
    }

    // ==================== Slot 映射构建 ====================

    /**
     * 构建当前页的 slot → map 映射。
     *
     * <h3>两步放置</h3>
     * <ol>
     *   <li><b>固定地图先落位</b>：遍历 fixedMaps，把 fixedPage 匹配当前页的地图
     *       放入其 fixedSlot。防御性检测：同 slot 已有地图时保留第一个，记录警告。</li>
     *   <li><b>自动地图填空</b>：计算前面各页已消耗的自动地图数（每页 MAPS_PER_PAGE - fixedCount），
     *       得到本页的起始偏移量，然后按 slot 0→44 顺序填入未占用的空位。</li>
     * </ol>
     *
     * @param page       当前页（0-indexed）
     * @param fixedMaps  所有固定地图
     * @param autoMaps   所有自动排序地图（已按状态+名称排序）
     * @return slot → map data 的映射（仅包含有地图的槽位）
     */
    private Map<Integer, Map<String, String>> buildSlotMap(int page,
                                                           List<Map<String, String>> fixedMaps,
                                                           List<Map<String, String>> autoMaps) {
        Map<Integer, Map<String, String>> slotMap = new LinkedHashMap<>();
        int currentPage = page + 1; // 转为 1-indexed

        // Step 1：放置固定地图
        for (Map<String, String> fm : fixedMaps) {
            try {
                int fPage = Integer.parseInt(fm.get("fixedPage"));
                int fSlot = Integer.parseInt(fm.get("fixedSlot"));
                if (fPage == currentPage) {
                    if (slotMap.containsKey(fSlot)) {
                        // 防御：渲染时不应出现冲突（命令层已解决），记录警告
                        String existingName = slotMap.get(fSlot).getOrDefault("name", "???");
                        String newName = fm.getOrDefault("name", "???");
                        System.err.println("[ParkourMapListMenu] 警告: 固定槽位冲突 page="
                                + fPage + " slot=" + fSlot + " 原=" + existingName + " 新=" + newName);
                    } else {
                        slotMap.put(fSlot, fm);
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // Step 2：计算自动地图的起始偏移量
        int autoIndex = 0;
        for (int pg = 1; pg < currentPage; pg++) {
            int fixedOnPg = 0;
            for (Map<String, String> fm : fixedMaps) {
                try {
                    if (Integer.parseInt(fm.get("fixedPage")) == pg) {
                        fixedOnPg++;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            autoIndex += MAPS_PER_PAGE - fixedOnPg;
        }

        // Step 3：填入自动地图（按 slot 顺序填空位）
        for (int slot = 0; slot < MAPS_PER_PAGE; slot++) {
            if (slotMap.containsKey(slot)) continue; // 已被固定地图占用
            if (autoIndex >= 0 && autoIndex < autoMaps.size()) {
                slotMap.put(slot, autoMaps.get(autoIndex));
                autoIndex++;
            }
        }

        return slotMap;
    }

    // ==================== 页面渲染 ====================

    /**
     * 渲染当前页：全边框 → 放置 slotMap 中的地图 → 导航按钮。
     */
    private void renderPage(Inventory inv, int page, Map<Integer, Map<String, String>> slotMap,
                            boolean isOp, int totalPages) {
        // 先全边框
        ParkourMenu.fillBorder(inv);

        // 地图项（按 slotMap 放置，可能不连续）
        for (Map.Entry<Integer, Map<String, String>> entry : slotMap.entrySet()) {
            inv.setItem(entry.getKey(), createMapIcon(entry.getValue(), isOp));
        }

        // 导航行（覆盖 row 5 边框）
        inv.setItem(SLOT_BACK, backButton());

        if (page > 0) {
            inv.setItem(SLOT_PREV, prevButton(page, totalPages));
        }

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
                lore.addAll(ParkourMenu.wrapText(introText, 24));
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
            Map<String, String> map = holder.slotMap.get(slot);
            if (map != null) {
                handleMapClick(player, map, holder.isOp, holder.gameType);
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
        /** slot → map data（仅当前页，含固定地图和自动地图） */
        final Map<Integer, Map<String, String>> slotMap;
        final boolean isOp;
        final int totalPages;

        MapListHolder(ParkourGameType gameType, int page,
                      Map<Integer, Map<String, String>> slotMap, boolean isOp, int totalPages) {
            this.gameType = gameType;
            this.page = page;
            this.slotMap = slotMap;
            this.isOp = isOp;
            this.totalPages = totalPages;
        }

        int totalPages() {
            return totalPages;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return null;
        }
    }
}
