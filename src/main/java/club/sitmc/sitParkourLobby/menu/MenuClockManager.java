package club.sitmc.sitParkourLobby.menu;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Logger;

/**
 * 菜单时钟管理器。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>定义并识别带 PDC 标记的菜单时钟物品。</li>
 *   <li>进服/切换世界/重生时自动将时钟固定在 slot 8（快捷栏最右格）。</li>
 *   <li>独立于按世界背包系统——slot 8 不参与背包持久化，始终由本类管理。</li>
 *   <li>完全防丢失：禁止丢弃、死亡不掉落、禁止放入容器/挪动、兜底定时补发。</li>
 *   <li>左右键点击时钟均可打开一级菜单。</li>
 * </ul>
 *
 * <h3>PDC 标记</h3>
 * <p>{@code sitparkourlobby:menu_clock} = {@code BYTE 1}</p>
 */
public class MenuClockManager implements Listener {

    /** slot 8 为菜单时钟专属槽位（快捷栏第 9 格，最右侧）。 */
    static final int CLOCK_SLOT = 8;

    private static final NamespacedKey PDC_KEY = new NamespacedKey("sitparkourlobby", "menu_clock");

    /** SITSegment 写入的 PDC 标记：special_mode=1 表示处于 prac/spec 特殊模式 */
    private static final NamespacedKey SEGMENT_SPECIAL_MODE_KEY =
            new NamespacedKey("sitsegment", "special_mode");

    private static final String CLOCK_DISPLAY_NAME = "§b跑酷菜单";
    private static final List<String> CLOCK_LORE = List.of("§7左键/右键 打开菜单");

    /** 兜底补发周期（tick）：每 5 秒扫描一次全部在线玩家。 */
    private static final long ENSURE_INTERVAL_TICKS = 100L;
    /** 首次补发延迟：服务器启动后 1 秒开始。 */
    private static final long ENSURE_INITIAL_DELAY_TICKS = 20L;

    private final JavaPlugin plugin;
    private final ParkourMenu parkourMenu;
    private final Logger logger;
    private int ensureTaskId = -1;

    /** 练习模式检测：返回 true 表示玩家正在练习，菜单不可用。默认永不拦截。 */
    private java.util.function.Predicate<Player> pracCheck = p -> false;

    public MenuClockManager(JavaPlugin plugin, ParkourMenu parkourMenu, Logger logger) {
        this.plugin = plugin;
        this.parkourMenu = parkourMenu;
        this.logger = logger;
    }

    /** 注入练习模式检测器（由 SITParkourLobby 调用）。 */
    public void setPracCheck(java.util.function.Predicate<Player> pracCheck) {
        this.pracCheck = pracCheck != null ? pracCheck : p -> false;
    }

    // ==================== 时钟物品定义 ====================

    /**
     * 创建带 PDC 标记的菜单时钟 ItemStack。
     *
     * @return 全新菜单时钟（不可堆叠）
     */
    public ItemStack createClockItem() {
        ItemStack clock = new ItemStack(Material.CLOCK);
        ItemMeta meta = clock.getItemMeta();

        meta.displayName(ParkourMenu.colorize(CLOCK_DISPLAY_NAME));
        meta.lore(ParkourMenu.colorizeList(CLOCK_LORE));
        meta.getPersistentDataContainer().set(PDC_KEY, PersistentDataType.BYTE, (byte) 1);

        clock.setItemMeta(meta);
        return clock;
    }

    /**
     * 判断一个 ItemStack 是否为本插件的菜单时钟。
     *
     * @param item 待检测的物品（可为 null）
     * @return {@code true} 表示是该时钟
     */
    private boolean isMenuClock(ItemStack item) {
        if (item == null || item.getType() != Material.CLOCK) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte tag = meta.getPersistentDataContainer().get(PDC_KEY, PersistentDataType.BYTE);
        return tag != null && tag == 1;
    }

    // ==================== 槽位确保（核心方法） ====================

    /**
     * 确保指定玩家 slot 8 存在带 PDC 标记的菜单时钟。
     *
     * <p>若 slot 8 已经是一个合法的菜单时钟则跳过；
     * 否则直接覆盖为该时钟（无论该槽位原来是空、还是被其他物品占用）。</p>
     *
     * @param player 目标玩家
     */
    public void ensureClockInSlot(Player player) {
        ItemStack slot8 = player.getInventory().getItem(CLOCK_SLOT);
        if (slot8 != null && isMenuClock(slot8)) {
            return; // 已经是正确的菜单时钟
        }

        // 覆盖 slot 8
        player.getInventory().setItem(CLOCK_SLOT, createClockItem());

        if (logger != null) {
            logger.fine("[MenuClock] 补发时钟给 " + player.getName()
                    + " (slot 8 原物品: " + (slot8 == null ? "空" : slot8.getType()) + ")");
        }
    }

    // ==================== 菜单触发 ====================

    /** 练习模式拦截提示消息前缀 */
    private static final String PRAC_BLOCKED_MSG = "&3&lSIT-Parkour &8| &c你正在练习模式中，无法打开菜单。";

    /**
     * 为该玩家打开一级菜单（受 pracCheck 拦截）。
     */
    private void openMenu(Player player) {
        if (pracCheck.test(player)) {
            player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacyAmpersand().deserialize(PRAC_BLOCKED_MSG));
            return;
        }
        parkourMenu.openMainMenu(player);
    }

    // ==================== 生命周期事件 ====================

    /**
     * 进服：在 InventoryListener 之后（MONITOR 优先级）确保时钟就位。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        ensureClockInSlot(event.getPlayer());
    }

    /**
     * 世界切换：在 InventoryListener (LOWEST) 完成背包存读之后，
     * 重新确保 slot 8 为菜单时钟（不受世界背包切换影响）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // from 和 to 相同时不处理（Bukkit 仍可能触发事件）
        if (event.getFrom().equals(event.getPlayer().getWorld())) {
            return;
        }
        ensureClockInSlot(event.getPlayer());
    }

    /**
     * 重生：一个 tick 后确保时钟（给其他重生逻辑留出完成空间）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> ensureClockInSlot(event.getPlayer()));
    }

    // ==================== 防丢失：丢弃 ====================

    /**
     * 禁止丢弃菜单时钟。
     */
    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        if (isMenuClock(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    // ==================== 防丢失：死亡掉落 ====================

    /**
     * 死亡时从掉落列表中移除菜单时钟。
     * <p>注意：本插件设置 keepInventory=true，通常不会触发掉落；
     * 此处作为兜底保护。</p>
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isMenuClock);
    }

    // ==================== 防丢失：禁止挪动 / 放入容器 ====================

    /**
     * 禁止任何将菜单时钟移出 slot 8 的操作。
     *
     * <h3>拦截策略</h3>
     * <ul>
     *   <li>点击菜单时钟本身 → 取消。</li>
     *   <li>鼠标指针上挂着菜单时钟 → 取消。</li>
     *   <li>数字键（hotbar）交换目标是 slot 8 且该槽位有时钟 → 取消。</li>
     * </ul>
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        // 被点击的物品是菜单时钟 → 不许动
        if (current != null && isMenuClock(current)) {
            event.setCancelled(true);
            return;
        }

        // 鼠标上挂着菜单时钟（试图放入某处）→ 不许放
        if (cursor != null && isMenuClock(cursor)) {
            event.setCancelled(true);
            return;
        }

        // 数字键交换：如果目标热键栏是 slot 8，且该位置当前是菜单时钟，取消
        int hotbar = event.getHotbarButton();
        if (hotbar == CLOCK_SLOT) {
            ItemStack slot8 = player.getInventory().getItem(CLOCK_SLOT);
            if (slot8 != null && isMenuClock(slot8)) {
                event.setCancelled(true);
            }
        }
    }

    // ==================== 左右键触发菜单 ====================

    /**
     * 主手为菜单时钟时，左键或右键均打开菜单。
     *
     * <p>若玩家处于 prac 状态（PDC special_mode=1），则改为执行 /unprac 退出练习，
     * 不打开菜单界面。spec 状态玩家处于旁观模式无法交互物品，不会触发此事件。</p>
     *
     * <p>左键攻击实体（LEFT_CLICK_ENTITY）走 {@code PlayerInteractEntityEvent}，
     * 不在本事件范围内，因此攻击生物不会误触发菜单。</p>
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || !isMenuClock(item)) {
            return;
        }

        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK
                || action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);

            Player player = event.getPlayer();

            // 读取 SITSegment 写入的 PDC 标记：值为 1 表示处于 prac 特殊模式
            // 注意：Segment 在非 prac 时也会保留此 key（值为 0），因此必须读值判断，
            // 不能用 has()（has() 只要 key 存在就返回 true，无法区分 0/1）
            Byte specialMode = player.getPersistentDataContainer()
                    .get(SEGMENT_SPECIAL_MODE_KEY, PersistentDataType.BYTE);
            if (specialMode != null && specialMode == 1) {
                // 值=1 → 处于 prac 状态 → 退出练习，不打开菜单
                boolean success = player.performCommand("unprac");
                if (!success) {
                    player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacyAmpersand().deserialize(
                                    "&3&lSIT-Parkour &8| &c无法执行退出练习命令，"
                                    + "请确认 SITSegment 已安装。"));
                }
                return;
            }

            // 无标记或值≠1 → 不在 prac 状态 → 正常打开菜单
            openMenu(player);
        }
    }

    // ==================== 兜底定时补发 ====================

    /**
     * 启动兜底补发定时任务。
     * <p>每 {@value #ENSURE_INTERVAL_TICKS} tick（{@value #ENSURE_INTERVAL_TICKS}/20 秒）
     * 扫描全部在线玩家，确保每人 slot 8 都是菜单时钟。</p>
     */
    public void startEnsureTask() {
        if (ensureTaskId != -1) {
            return;
        }
        ensureTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                ensureClockInSlot(player);
            }
        }, ENSURE_INITIAL_DELAY_TICKS, ENSURE_INTERVAL_TICKS);
    }

    /**
     * 停止兜底补发定时任务。
     */
    public void stopEnsureTask() {
        if (ensureTaskId != -1) {
            Bukkit.getScheduler().cancelTask(ensureTaskId);
            ensureTaskId = -1;
        }
    }
}
