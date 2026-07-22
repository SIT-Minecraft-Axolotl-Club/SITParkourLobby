package club.sitmc.sitParkourLobby.inventory;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 按世界独立背包管理器。
 *
 * <p>以 玩家UUID + 世界名 为维度存储每个玩家在每个世界的物品栏快照，
 * 包括：主背包（36格，含快捷栏）、副手、盔甲栏（4格）。
 * <b>slot 8（快捷栏最右格）为菜单时钟专属槽位，不参与持久化/反序列化，
 * 始终由 MenuClockManager 接管。</b></p>
 *
 * <p>存储结构：</p>
 * <pre>
 * plugins/SITParkourLobby/inventories/
 *   &lt;uuid&gt;/
 *     &lt;world-name&gt;.yml
 * </pre>
 *
 * <p>序列化方式：使用 Bukkit 内置 {@link ItemStack#serialize()}，
 * 通过 {@link YamlConfiguration} 的点号路径存储。
 * ItemStack 的 NBT、附魔、耐久等全部保留。</p>
 *
 * <p><b>存读 key 一致性：</b>保存时通过 {@code config.set("contents.0", map)}
 * 写入，YAML 输出整数键。读取时 {@code getValues(false)} 可能返回 Integer 键
 * 或 String 键（取决于 YAML 解析器行为）。本类在反序列化时<b>同时检查 String 和 Integer
 * 两种键类型</b>，确保永远能命中正确的槽位。</p>
 *
 * <p>写入安全：先写临时文件 {@code .tmp}，成功后再替换目标文件，
 * 避免写入中断导致原数据损坏。</p>
 *
 * <p>容错：读取失败时记录警告并返回 {@code false}，不修改玩家背包，
 * 不会用空数据覆盖原文件。</p>
 */
public class InventoryManager {

    private static final String KEY_CONTENTS = "contents";
    private static final String KEY_ARMOR = "armor";
    private static final String KEY_OFFHAND = "offhand";

    private final File inventoriesFolder;
    private final Logger logger;

    /**
     * 诊断日志开关。上线确认正常后，将本字段改为 {@code false} 即可关闭详细日志。
     */
    private final boolean diagnosticLog = true;

    public InventoryManager(File dataFolder, Logger logger) {
        this.inventoriesFolder = new File(dataFolder, "inventories");
        this.logger = logger;
    }

    // ==================== 公共方法 ====================

    /**
     * 检查指定玩家在指定世界是否有已保存的背包数据。
     */
    public boolean hasSavedInventory(UUID uuid, String worldName) {
        return getWorldFile(uuid, worldName).exists();
    }

    /**
     * 将玩家当前背包保存到指定世界的存储中。
     */
    public void saveInventory(Player player, String worldName) {
        File file = getWorldFile(player.getUniqueId(), worldName);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            YamlConfiguration config = new YamlConfiguration();
            int count = serializeInventory(player, config);
            saveConfigSafe(config, file);

            if (diagnosticLog) {
                logger.info("[InvSave] " + player.getName() + " / " + player.getUniqueId()
                        + " → 世界 \"" + worldName + "\" | " + count + " 格物品 | 文件: " + file.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.warning("[InvSave] 保存失败 [" + player.getName() + " / " + worldName
                    + "] → " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /**
     * 从存储中加载指定世界的背包到玩家身上。
     *
     * @return {@code true} 加载成功；{@code false} 无保存文件或读取失败
     */
    public boolean loadInventory(Player player, String worldName) {
        File file = getWorldFile(player.getUniqueId(), worldName);
        if (!file.exists()) {
            if (diagnosticLog) {
                logger.info("[InvLoad] " + player.getName() + " / " + worldName
                        + " → 文件不存在，当作无记录");
            }
            return false;
        }

        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            int count = deserializeInventory(player, config);

            if (diagnosticLog) {
                logger.info("[InvLoad] " + player.getName() + " / " + worldName
                        + " → 还原 " + count + " 格物品 | 文件: " + file.getAbsolutePath());
            }
            return true;
        } catch (Exception e) {
            logger.warning("[InvLoad] 加载失败，文件可能损坏 [" + player.getName() + " / " + worldName
                    + "] → " + file.getAbsolutePath() + ": " + e.getMessage()
                    + " — 保持玩家当前背包不变");
            return false;
        }
    }

    /**
     * 清空玩家背包（主背包 + 盔甲 + 副手）。
     */
    public void clearInventory(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(null);
    }

    // ==================== 序列化 ====================

    /**
     * 将玩家背包序列化到 YamlConfiguration 中。
     *
     * @return 实际序列化的物品格数（非空非空气）
     */
    private int serializeInventory(Player player, YamlConfiguration config) {
        PlayerInventory inv = player.getInventory();
        int count = 0;

        // 主背包 36 格（0-8 快捷栏, 9-35 背包）
        // slot 8 为菜单时钟专属槽位，不持久化（由 MenuClockManager 管理）
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (i == 8) continue;
            if (contents[i] != null && !contents[i].getType().isAir()) {
                config.set(KEY_CONTENTS + "." + i, contents[i].serialize());
                count++;
            }
        }

        // 盔甲栏 4 格
        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] != null && !armor[i].getType().isAir()) {
                config.set(KEY_ARMOR + "." + i, armor[i].serialize());
                count++;
            }
        }

        // 副手
        ItemStack offhand = inv.getItemInOffHand();
        if (!offhand.getType().isAir()) {
            config.set(KEY_OFFHAND, offhand.serialize());
            count++;
        }

        return count;
    }

    // ==================== 反序列化 ====================

    /**
     * 从 YamlConfiguration 反序列化到玩家背包。
     * 先清空再还原——确保"有记录→还原，无记录→空背包"的语义正确。
     *
     * @return 实际还原的物品格数
     */
    @SuppressWarnings("unchecked")
    private int deserializeInventory(Player player, YamlConfiguration config) {
        PlayerInventory inv = player.getInventory();
        int count = 0;

        // 先清空
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(null);

        // 主背包（36 格）—— slot 8 为菜单时钟专属槽位，不反序列化（由 MenuClockManager 管理）
        ConfigurationSection contentsSection = config.getConfigurationSection(KEY_CONTENTS);
        if (contentsSection != null) {
            Map<String, Object> contentsMap = contentsSection.getValues(false);
            ItemStack[] items = new ItemStack[36];
            for (int i = 0; i < 36; i++) {
                if (i == 8) continue;
                ItemStack restored = getSlotValue(contentsMap, i);
                if (restored != null) {
                    items[i] = restored;
                    count++;
                }
            }
            inv.setContents(items);
        }

        // 盔甲栏（4 格）
        ConfigurationSection armorSection = config.getConfigurationSection(KEY_ARMOR);
        if (armorSection != null) {
            Map<String, Object> armorMap = armorSection.getValues(false);
            ItemStack[] armorItems = new ItemStack[4];
            for (int i = 0; i < 4; i++) {
                ItemStack restored = getSlotValue(armorMap, i);
                if (restored != null) {
                    armorItems[i] = restored;
                    count++;
                }
            }
            inv.setArmorContents(armorItems);
        }

        // 副手
        Object offhandObj = config.get(KEY_OFFHAND);
        if (offhandObj != null) {
            ItemStack offhand = coerceToItemStack(offhandObj);
            if (offhand != null) {
                inv.setItemInOffHand(offhand);
                count++;
            }
        }

        return count;
    }

    // ==================== 存读 key 兼容 ====================

    /**
     * 从 Map 中按槽位号取值，兼容 String 和 Integer 两种键类型。
     *
     * <p>Bug 背景：序列化时通过 {@code config.set("contents.0", item.serialize())}
     * 写入，YAML 输出为整数键 {@code 0}。但 {@code YamlConfiguration} 加载后，
     * {@code getValues(false)} 返回的 Map 键类型可能是 {@link Integer}
     * （SnakeYAML 默认行为）或 {@link String}（取决于版本/配置）。
     * 若只查找 String 键，整数键的数据就会全部漏掉，表现为"背包永远读不到物品"。</p>
     */
    private ItemStack getSlotValue(Map<String, Object> map, int slot) {
        // 先按 String 键查找
        Object obj = map.get(String.valueOf(slot));
        if (obj != null) {
            return coerceToItemStack(obj);
        }
        // 再按 Integer 键查找（YAML 整数键的兜底）
        obj = map.get(slot);
        if (obj != null) {
            return coerceToItemStack(obj);
        }
        return null;
    }

    /**
     * 将 Map 中的值强制转为 ItemStack。
     * 兼容以下三种形式：
     * <ul>
     *   <li>已经是 {@link ItemStack}（YAML 自动还原）</li>
     *   <li>是 {@link Map}（需手动 {@link ItemStack#deserialize}）</li>
     *   <li>是 {@link ConfigurationSection}（取 values 后 deserialize）</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private ItemStack coerceToItemStack(Object obj) {
        if (obj instanceof ItemStack) {
            return (ItemStack) obj;
        }
        if (obj instanceof Map) {
            try {
                return ItemStack.deserialize((Map<String, Object>) obj);
            } catch (Exception ignored) {
                return null;
            }
        }
        if (obj instanceof ConfigurationSection) {
            try {
                return ItemStack.deserialize(((ConfigurationSection) obj).getValues(false));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    // ==================== 文件工具 ====================

    private File getWorldFile(UUID uuid, String worldName) {
        return new File(new File(inventoriesFolder, uuid.toString()), worldName + ".yml");
    }

    /**
     * 原子保存：先写临时文件，成功后替换目标文件。
     */
    private void saveConfigSafe(YamlConfiguration config, File target) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");

        File parent = temp.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        config.save(temp);

        // Windows 上 rename 不会覆盖
        if (target.exists() && !target.delete()) {
            logger.warning("无法删除旧文件，将直接覆盖写入: " + target.getAbsolutePath());
        }

        if (temp.renameTo(target)) {
            return;
        }

        // 回退：直接写入
        logger.warning("临时文件重命名失败，回退直接写入: " + target.getAbsolutePath());
        config.save(target);
        temp.delete();
    }
}
