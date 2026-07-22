package club.sitmc.sitParkourLobby.data;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 玩家皮肤纹理本地缓存管理器。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>不发起任何外部 HTTP 请求（不调用 Mojang API、不调用 blessing-skin API）。</li>
 *   <li>玩家在线时"顺手"从 Paper PlayerProfile 中提取 textures 属性并缓存到本地文件。</li>
 *   <li>显示头颅时：在线玩家直接用其当前 PlayerProfile；离线玩家读本地缓存构造 PlayerProfile。</li>
 *   <li>所有文件 I/O 均在主线程执行，有完善的容错处理。</li>
 * </ul>
 *
 * <h3>缓存文件格式</h3>
 * <p>每个玩家一个文件：{@code plugins/SITParkourLobby/skins/<uuid>.txt}</p>
 * <pre>
 * 第 1 行：玩家名（缓存时的名称，用于构造 PlayerProfile 时提供 name）
 * 第 2 行：textures 属性的 Base64 value
 * 第 3 行：textures 属性的 signature（可能为空行）
 * </pre>
 *
 * @see PlayerProfile
 * @see ProfileProperty
 */
public class SkinCacheManager {

    private final File skinsDir;
    private final Logger logger;

    /**
     * @param dataFolder 插件 data 文件夹（即 plugins/SITParkourLobby/）
     * @param logger     日志记录器
     */
    public SkinCacheManager(File dataFolder, Logger logger) {
        this.skinsDir = new File(dataFolder, "skins");
        this.logger = logger;
        ensureSkinsDir();
    }

    private void ensureSkinsDir() {
        if (!skinsDir.exists()) {
            boolean created = skinsDir.mkdirs();
            if (created) {
                logger.info("[SkinCache] 已创建皮肤缓存目录: " + skinsDir.getAbsolutePath());
            }
        }
    }

    // ==================== 缓存写入 ====================

    /**
     * 从在线玩家的 PlayerProfile 中提取 textures 属性并缓存到本地文件。
     * <p>应在 {@code PlayerJoinEvent} 中调用。每次登录都覆盖更新，确保皮肤变更后缓存也更新。</p>
     * <p>若 profile 中无 textures 属性则静默跳过，不报错。</p>
     * <p>本方法<b>不发起任何外部 HTTP 请求</b>——只读取 Paper 已填充好的 PlayerProfile。</p>
     *
     * @param player 刚加入服务器的玩家
     */
    public void cacheSkin(Player player) {
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();

        PlayerProfile profile = player.getPlayerProfile();
        if (profile == null) {
            return;
        }

        Set<ProfileProperty> properties = profile.getProperties();
        if (properties == null || properties.isEmpty()) {
            return;
        }

        for (ProfileProperty property : properties) {
            if ("textures".equals(property.getName())) {
                String value = property.getValue();
                String signature = property.getSignature();

                if (value == null || value.isEmpty()) {
                    return;
                }

                File cacheFile = getCacheFile(uuid);
                try (BufferedWriter writer = new BufferedWriter(
                        new FileWriter(cacheFile, StandardCharsets.UTF_8))) {
                    writer.write(playerName);
                    writer.newLine();
                    writer.write(value);
                    writer.newLine();
                    if (signature != null && !signature.isEmpty()) {
                        writer.write(signature);
                    }
                    writer.newLine();
                    writer.flush();
                    logger.info("[SkinCache] 已缓存皮肤纹理: " + playerName
                            + " (" + uuid + ") → " + cacheFile.getName());
                } catch (IOException e) {
                    logger.warning("[SkinCache] 写入皮肤缓存失败: " + playerName
                            + " (" + uuid + ") — " + e.getMessage());
                }
                return; // 找到一个 textures 属性即可
            }
        }
        // profile 中没有 textures 属性——静默跳过
    }

    // ==================== 头颅获取 ====================

    /**
     * 获取指定在线玩家的头颅 ItemStack（直接复制其当前 PlayerProfile）。
     * <p>这是最准确的方式，因为在线玩家的 profile 是 authlib-injector 实时注入的。</p>
     *
     * @param player 在线玩家
     * @return 设置了该玩家皮肤的头颅 ItemStack
     */
    public ItemStack getSkullItem(Player player) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        // Paper API: setPlayerProfile 直接复制完整 profile（含 textures properties）
        meta.setPlayerProfile(player.getPlayerProfile());
        skull.setItemMeta(meta);
        return skull;
    }

    /**
     * 获取指定玩家（可能在线也可能离线）的头颅 ItemStack。
     *
     * <h3>取用逻辑</h3>
     * <ol>
     *   <li>若玩家在线 → 使用 {@link #getSkullItem(Player)} 直接复制在线 profile。</li>
     *   <li>若玩家离线且存在本地缓存 → 从缓存构造 PlayerProfile 并应用。</li>
     *   <li>若玩家离线且无缓存 → 返回默认 Steve 头颅（不报错）。</li>
     * </ol>
     *
     * <p><b>全程不发起任何外部 HTTP 请求。</b></p>
     *
     * @param uuid       玩家 UUID
     * @param playerName 玩家名（用于构造 PlayerProfile 和显示名称兜底）
     * @return 设置了皮肤的头颅 ItemStack；若无可用皮肤则为默认 Steve 头
     */
    public ItemStack getSkullItem(UUID uuid, String playerName) {
        // 1. 尝试在线玩家
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            return getSkullItem(onlinePlayer);
        }

        // 2. 尝试离线缓存
        CachedSkin cached = loadCachedSkin(uuid);
        if (cached != null) {
            return createSkullFromCache(cached, playerName);
        }

        // 3. 无缓存 → 默认 Steve 头
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        // 不设皮肤，即为默认 Steve 头
        return skull;
    }

    /**
     * 按玩家名查询头颅（先通过 Bukkit.getPlayerExact 查在线，再通过缓存查离线）。
     * <p>注意：离线时需要通过缓存文件中记录的名字来反查 UUID，效率较低；
     * 推荐调用方尽可能使用 UUID 版本的 {@link #getSkullItem(UUID, String)}。</p>
     *
     * @param playerName 玩家名（在线时精确匹配；离线时用于查找缓存）
     * @return 设置了皮肤的头颅 ItemStack；若无可用皮肤则为默认 Steve 头
     */
    public ItemStack getSkullItemByName(String playerName) {
        // 1. 尝试在线玩家（精确匹配）
        Player onlinePlayer = Bukkit.getPlayerExact(playerName);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            return getSkullItem(onlinePlayer);
        }

        // 2. 遍历缓存文件查找匹配的名字
        File[] files = skinsDir.listFiles((dir, name) -> name.endsWith(".txt"));
        if (files != null) {
            for (File file : files) {
                CachedSkin cached = loadFromFile(file);
                if (cached != null && playerName.equalsIgnoreCase(cached.playerName)) {
                    return createSkullFromCache(cached, playerName);
                }
            }
        }

        // 3. 无缓存 → 默认 Steve 头
        return new ItemStack(Material.PLAYER_HEAD);
    }

    // ==================== 缓存读取 ====================

    /**
     * 缓存数据结构。
     */
    private static class CachedSkin {
        final UUID uuid;
        final String playerName;
        final String texturesValue;
        final String texturesSignature; // 可能为 null

        CachedSkin(UUID uuid, String playerName, String texturesValue, String texturesSignature) {
            this.uuid = uuid;
            this.playerName = playerName;
            this.texturesValue = texturesValue;
            this.texturesSignature = texturesSignature;
        }
    }

    /**
     * 按 UUID 加载缓存。
     *
     * @param uuid 玩家 UUID
     * @return CachedSkin 或 null（文件不存在/读取失败/格式不正确）
     */
    private CachedSkin loadCachedSkin(UUID uuid) {
        File cacheFile = getCacheFile(uuid);
        return loadFromFile(cacheFile);
    }

    /**
     * 从指定文件加载缓存。
     *
     * @param file 缓存文件
     * @return CachedSkin 或 null
     */
    private CachedSkin loadFromFile(File file) {
        if (!file.exists() || !file.isFile()) {
            return null;
        }

        try (BufferedReader reader = new BufferedReader(
                new FileReader(file, StandardCharsets.UTF_8))) {
            String playerName = reader.readLine();
            String value = reader.readLine();
            String signature = reader.readLine();

            if (playerName == null || playerName.isEmpty()) return null;
            if (value == null || value.isEmpty()) return null;

            // 从文件名解析 UUID
            String fileName = file.getName();
            String uuidStr = fileName.substring(0, fileName.length() - 4); // 去掉 .txt
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                return null;
            }

            // signature 可能为空字符串（表示无签名）
            if (signature != null && signature.isEmpty()) {
                signature = null;
            }

            return new CachedSkin(uuid, playerName.isEmpty() ? null : playerName, value, signature);
        } catch (IOException e) {
            logger.warning("[SkinCache] 读取皮肤缓存失败: " + file.getName() + " — " + e.getMessage());
            return null;
        }
    }

    /**
     * 从缓存的 textures 数据构造带皮肤的骷髅头颅。
     *
     * <h3>实现细节</h3>
     * <ol>
     *   <li>通过 {@link Bukkit#createProfile(UUID, String)} 创建空的 PlayerProfile。</li>
     *   <li>构造 {@link ProfileProperty}("textures", value, signature) 并添加到 profile。</li>
     *   <li>通过 Paper 的 {@link SkullMeta#setPlayerProfile(PlayerProfile)} 将 profile 应用到头颅。</li>
     * </ol>
     * <p>此过程<b>完全在内存中进行，不发起任何网络请求</b>。</p>
     *
     * @param cached     缓存的皮肤数据
     * @param playerName 显示用玩家名（兜底，如果缓存中名字为空）
     * @return 设置了皮肤的骷髅头颅 ItemStack
     */
    private ItemStack createSkullFromCache(CachedSkin cached, String playerName) {
        String name = (cached.playerName != null && !cached.playerName.isEmpty())
                ? cached.playerName : playerName;
        if (name == null) name = "Unknown";

        // 使用 Paper API 创建 PlayerProfile
        PlayerProfile profile = Bukkit.createProfile(cached.uuid, name);

        // 将缓存的 textures 属性注入 profile
        ProfileProperty texturesProp = new ProfileProperty(
                "textures",
                cached.texturesValue,
                cached.texturesSignature // 可以为 null
        );
        profile.getProperties().add(texturesProp);

        // 创建头颅并应用 profile
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        meta.setPlayerProfile(profile);
        skull.setItemMeta(meta);

        return skull;
    }

    // ==================== 诊断 / 调试 ====================

    /**
     * 诊断信息结果。
     */
    public static class SkinDebugInfo {
        public final String playerName;
        public final boolean isOnline;
        public final boolean hasCache;
        public final String cacheFilePath;
        public final String cachedPlayerName;
        public final String texturesPreview; // 前 N 个字符预览

        SkinDebugInfo(String playerName, boolean isOnline, boolean hasCache,
                      String cacheFilePath, String cachedPlayerName, String texturesPreview) {
            this.playerName = playerName;
            this.isOnline = isOnline;
            this.hasCache = hasCache;
            this.cacheFilePath = cacheFilePath;
            this.cachedPlayerName = cachedPlayerName;
            this.texturesPreview = texturesPreview;
        }
    }

    /**
     * 获取指定玩家名的皮肤缓存诊断信息。
     * <p>优先按在线玩家查找，其次按缓存文件中记录的名字匹配。</p>
     *
     * @param playerName 玩家名
     * @return 诊断信息（永远不为 null）
     */
    public SkinDebugInfo getDebugInfo(String playerName) {
        // 查找在线玩家
        Player onlinePlayer = Bukkit.getPlayerExact(playerName);
        UUID uuid;
        boolean isOnline;

        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            uuid = onlinePlayer.getUniqueId();
            isOnline = true;
        } else {
            // 离线：遍历缓存文件按名字匹配
            uuid = findUuidByName(playerName);
            isOnline = false;
        }

        File cacheFile = uuid != null ? getCacheFile(uuid) : null;
        boolean hasCache = cacheFile != null && cacheFile.exists() && cacheFile.isFile();

        String cacheFilePath = cacheFile != null ? cacheFile.getAbsolutePath() : "无";
        String cachedPlayerName = null;
        String texturesPreview = null;

        if (hasCache && cacheFile != null) {
            CachedSkin cached = loadFromFile(cacheFile);
            if (cached != null) {
                cachedPlayerName = cached.playerName;
                String val = cached.texturesValue;
                if (val != null && val.length() > 60) {
                    texturesPreview = val.substring(0, 60) + "... (总计 " + val.length() + " 字符)";
                } else if (val != null) {
                    texturesPreview = val;
                } else {
                    texturesPreview = "（空）";
                }
            }
        }

        return new SkinDebugInfo(playerName, isOnline, hasCache, cacheFilePath,
                cachedPlayerName, texturesPreview);
    }

    /**
     * 通过玩家名在缓存文件中查找 UUID。
     *
     * @param playerName 玩家名
     * @return UUID 或 null
     */
    private UUID findUuidByName(String playerName) {
        File[] files = skinsDir.listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null) return null;

        for (File file : files) {
            CachedSkin cached = loadFromFile(file);
            if (cached != null && playerName.equalsIgnoreCase(cached.playerName)) {
                return cached.uuid;
            }
        }
        return null;
    }

    // ==================== 工具方法 ====================

    /**
     * 获取指定 UUID 的缓存文件。
     */
    private File getCacheFile(UUID uuid) {
        return new File(skinsDir, uuid.toString() + ".txt");
    }
}
