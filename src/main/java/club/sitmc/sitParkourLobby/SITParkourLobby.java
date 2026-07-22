package club.sitmc.sitParkourLobby;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import io.papermc.paper.scoreboard.numbers.NumberFormat;

import club.sitmc.sitParkourLobby.data.MapStatus;
import club.sitmc.sitParkourLobby.data.ParkourDataProvider;
import club.sitmc.sitParkourLobby.data.ParkourGameType;
import club.sitmc.sitParkourLobby.data.SkinCacheManager;
import club.sitmc.sitParkourLobby.data.TimingMode;
import club.sitmc.sitParkourLobby.data.WorldMeta;
import club.sitmc.sitParkourLobby.event.ManagedWorldsLoadedEvent;
import club.sitmc.sitParkourLobby.inventory.InventoryListener;
import club.sitmc.sitParkourLobby.inventory.InventoryManager;
import club.sitmc.sitParkourLobby.menu.LeaderboardMenu;
import club.sitmc.sitParkourLobby.menu.MapDetailMenu;
import club.sitmc.sitParkourLobby.menu.MenuClockManager;
import club.sitmc.sitParkourLobby.menu.ParkourMapListMenu;
import club.sitmc.sitParkourLobby.menu.ParkourMenu;
import java.io.File;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SITParkourLobby extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private boolean ranStartupReload = false;
    private boolean startupWorldsLoaded = false;
    private static final String MESSAGE_PREFIX = "&3&lSIT-Parkour &8| &f";
    private static final String NO_COLLISION_TEAM_NAME = "no_collision";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String DATA_FILE_NAME = "data.yml";
    private static final String MAPS_DATA_PATH = "maps";
    private static final String MANAGED_WORLDS_DATA_PATH = "managed-worlds";
    private static final String WORLD_META_DATA_PATH = "world-metadata";
    private static final String SCOREBOARD_TITLE = "&3&lSIT-Minecraft";
    private static final List<String> SCOREBOARD_TEMPLATE = List.of(
            "&b=====================",
            "&b* &b欢迎 &6&l%player_name%",
            "&b* &b延迟&7: &f%ping%&7ms",
            "&b* &b跳跃次数&7: &f%jumps%",
            "&b* &b游玩时间&7: &f%playtime%&7h",
            "&b* &b官网&7: &6sitmc.club",
            "&b====================="
    );
    private static final String[] UNIQUE_SUFFIXES = {
            "&0", "&1", "&2", "&3", "&4", "&5", "&6", "&7", "&8", "&9", "&a", "&b", "&c", "&d", "&e", "&f"
    };

    private Team noCollisionTeam;
    private final Map<UUID, PlayerStats> stats = new HashMap<>();
    private final Map<UUID, Boolean> lastOnGround = new HashMap<>();
    private final Map<UUID, Scoreboard> playerBoards = new HashMap<>();
    private final DecimalFormat hoursFormat = new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.US));
    private final Set<String> allowedMaps = new HashSet<>();
    private final Set<String> managedWorlds = new HashSet<>();
    /** 世界名 → 元数据（category/author/status/timingMode），完全由本插件自行管理 */
    private final Map<String, WorldMeta> worldMetaMap = new LinkedHashMap<>();
    private YamlConfiguration dataConfig;
    private File dataFile;
    private int scoreboardTaskId = -1;
    private int settingsTaskId = -1;
    private InventoryManager inventoryManager;
    private ParkourDataProvider dataProvider;
    private ParkourMenu parkourMenu;
    private SkinCacheManager skinCacheManager;
    private MenuClockManager menuClockManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadDataFile();
        loadAllowedMaps();
        loadManagedWorlds();
        loadWorldMetadata();

        // 受管世界不在 onEnable 中加载（STARTUP 阶段禁止创建世界）
        // 改为在 ServerLoadEvent 触发后加载，见 onServerLoad + loadAllManagedWorlds

        registerCommand("lobby");
        registerCommand("hub");
        registerCommand("spawn");
        registerCommand("map");
        registerCommand("addmap");
        registerCommand("delmap");
        registerCommand("listmap");
        registerCommand("create");
        registerCommand("falldamage");
        registerCommand("world");
        registerCommand("menu");

        if (getCommand("map") != null) {
            getCommand("map").setTabCompleter(this);
        }
        if (getCommand("world") != null) {
            getCommand("world").setTabCompleter(this);
        }
        if (getCommand("menu") != null) {
            getCommand("menu").setTabCompleter(this);
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        // 按世界独立背包
        inventoryManager = new InventoryManager(getDataFolder(), getLogger());
        Bukkit.getPluginManager().registerEvents(new InventoryListener(inventoryManager, getLogger()), this);

        // 皮肤缓存管理器
        skinCacheManager = new SkinCacheManager(getDataFolder(), getLogger());

        // 跑酷菜单（三级菜单联动）
        dataProvider = new ParkourDataProvider(getLogger());
        // 注入自有元数据（不再反射 getParkourMaps）
        dataProvider.setWorldMetadata(worldMetaMap);

        LeaderboardMenu leaderboardMenu = new LeaderboardMenu(dataProvider, skinCacheManager, getLogger());
        MapDetailMenu mapDetailMenu = new MapDetailMenu(dataProvider);
        ParkourMapListMenu mapListMenu = new ParkourMapListMenu(dataProvider);
        parkourMenu = new ParkourMenu(dataProvider);

        // 注入引用链（解决循环依赖）
        parkourMenu.setStatsProvider(uuid -> {
            PlayerStats ps = getOrCreateStats(uuid);
            return new long[]{ps.playtimeSeconds, ps.jumps};
        });
        parkourMenu.setMapListMenu(mapListMenu);
        mapListMenu.setMainMenu(parkourMenu);
        mapListMenu.setDetailMenu(mapDetailMenu);
        mapDetailMenu.setMapListMenu(mapListMenu);
        mapDetailMenu.setLeaderboardMenu(leaderboardMenu);
        leaderboardMenu.setDetailMenu(mapDetailMenu);

        // 注册监听器
        Bukkit.getPluginManager().registerEvents(parkourMenu, this);
        Bukkit.getPluginManager().registerEvents(mapListMenu, this);
        Bukkit.getPluginManager().registerEvents(mapDetailMenu, this);
        Bukkit.getPluginManager().registerEvents(leaderboardMenu, this);

        // 菜单时钟管理器（slot 8 专属，独立于世界背包系统）
        menuClockManager = new MenuClockManager(this, parkourMenu, getLogger());
        menuClockManager.setPracCheck(this::isInPracMode);
        parkourMenu.setPracCheck(this::isInPracMode);
        Bukkit.getPluginManager().registerEvents(menuClockManager, this);

        Bukkit.setDefaultGameMode(GameMode.ADVENTURE);
        for (World world : Bukkit.getWorlds()) {
            applyWorldSettings(world);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            loadPlayerStats(player);
            setupScoreboard(player);
            applyPlayerEntryRules(player);
            lastOnGround.put(player.getUniqueId(), player.isOnGround());
            menuClockManager.ensureClockInSlot(player);
        }

        menuClockManager.startEnsureTask();
        startScoreboardTask();
        startSettingsTask();
        Bukkit.getScheduler().runTask(this, this::initializeNoCollisionTeam);

        // 兜底：若 ServerLoadEvent 因某些原因未触发，runTask 延迟 1 tick 后也尝试加载
        // 与 ServerLoadEvent 共享 startupWorldsLoaded 标志位，确保只执行一次
        Bukkit.getScheduler().runTask(this, this::loadAllManagedWorlds);
    }

    @Override
    public void onDisable() {
        if (scoreboardTaskId != -1) {
            Bukkit.getScheduler().cancelTask(scoreboardTaskId);
            scoreboardTaskId = -1;
        }
        if (settingsTaskId != -1) {
            Bukkit.getScheduler().cancelTask(settingsTaskId);
            settingsTaskId = -1;
        }
        if (menuClockManager != null) {
            menuClockManager.stopEnsureTask();
        }
        saveAllPlayerStats();
    }

    private void registerCommand(String name) {
        if (getCommand(name) != null) {
            getCommand(name).setExecutor(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("create")) {
            if (!sender.hasPermission("sitpk.create")) {
                send(sender, "你没有权限使用该命令。");
                return true;
            }
            if (args.length != 1) {
                send(sender, "用法: /create <世界名>");
                return true;
            }

            String worldName = args[0];
            if (Bukkit.getWorld(worldName) != null) {
                send(sender, "世界已存在: " + worldName);
                return true;
            }

            WorldCreator creator = new WorldCreator(worldName);
            creator.generator(new VoidChunkGenerator());
            creator.biomeProvider(new VoidBiomeProvider());
            World created = creator.createWorld();
            if (created == null) {
                send(sender, "创建世界失败: " + worldName);
                return true;
            }

            applyWorldSettings(created);
            placeSpawnBedrock(created);
            send(sender, "已创建虚空世界: " + worldName);
            if (sender instanceof Player player) {
                teleportToSpawn(player, created);
            }
            return true;
        }

        if (name.equals("addmap")) {
            if (!sender.hasPermission("sitpk.addmap")) {
                send(sender, "你没有权限使用该命令。");
                return true;
            }
            if (args.length != 1) {
                send(sender, "用法: /addmap <世界名>");
                return true;
            }

            String worldName = args[0];
            World targetWorld = Bukkit.getWorld(worldName);
            if (targetWorld == null) {
                send(sender, "未找到世界: " + worldName);
                return true;
            }
            if (allowedMaps.add(targetWorld.getName())) {
                saveAllowedMaps();
                send(sender, "已添加到 /map 列表: " + targetWorld.getName());
            } else {
                send(sender, "已经在 /map 列表中: " + targetWorld.getName());
            }
            return true;
        }

        if (name.equals("delmap")) {
            if (!sender.hasPermission("sitpk.addmap")) {
                send(sender, "你没有权限使用该命令。");
                return true;
            }
            if (args.length != 1) {
                send(sender, "用法: /delmap <世界名>");
                return true;
            }

            String worldName = args[0];
            if (allowedMaps.remove(worldName)) {
                saveAllowedMaps();
                send(sender, "已从 /map 列表移除: " + worldName);
            } else {
                send(sender, "不在 /map 列表中: " + worldName);
            }
            return true;
        }

        if (name.equals("listmap")) {
            if (hasMapAdmin(sender)) {
                List<String> worlds = Bukkit.getWorlds().stream()
                        .map(World::getName)
                        .sorted()
                        .toList();
                if (worlds.isEmpty()) {
                    send(sender, "没有已加载的世界。");
                    return true;
                }
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < worlds.size(); i++) {
                    String worldName = worlds.get(i);
                    if (i > 0) {
                        builder.append(", ");
                    }
                    if (allowedMaps.contains(worldName)) {
                        builder.append("&a").append(worldName).append("&f*");
                    } else {
                        builder.append(worldName);
                    }
                }
                send(sender, "世界列表(&a已添加&f): " + builder);
                return true;
            }

            if (allowedMaps.isEmpty()) {
                send(sender, "暂无可用地图。");
                return true;
            }

            List<String> maps = allowedMaps.stream().sorted().toList();
            send(sender, "可用地图: " + String.join(", ", maps));
            return true;
        }

        if (name.equals("world")) {
            return handleWorldCommand(sender, args);
        }

        if (name.equals("menu")) {
            // /menu skindebug <玩家名> — 皮肤缓存诊断（控制台也可用）
            if (args.length >= 2 && args[0].equalsIgnoreCase("skindebug")) {
                String targetName = args[1];
                SkinCacheManager.SkinDebugInfo info = skinCacheManager.getDebugInfo(targetName);
                send(sender, "&e===== 皮肤缓存诊断: &f" + targetName + " &e=====");
                send(sender, "&b在线状态: " + (info.isOnline ? "&a在线" : "&7离线"));
                send(sender, "&b是否有缓存: " + (info.hasCache ? "&a是" : "&c否"));
                send(sender, "&b缓存文件路径: &f" + info.cacheFilePath);
                if (info.cachedPlayerName != null) {
                    send(sender, "&b缓存中的玩家名: &f" + info.cachedPlayerName);
                }
                if (info.texturesPreview != null) {
                    send(sender, "&bTextures 预览: &f" + info.texturesPreview);
                }
                return true;
            }

            if (!(sender instanceof Player player)) {
                send(sender, "只有玩家可以使用该命令。");
                return true;
            }

            // 练习模式 → 退出练习（与时钟一致，读值判断：1=prac）
            Byte specialMode = player.getPersistentDataContainer()
                    .get(new org.bukkit.NamespacedKey("sitsegment", "special_mode"),
                            org.bukkit.persistence.PersistentDataType.BYTE);
            if (specialMode != null && specialMode == 1) {
                boolean success = player.performCommand("unprac");
                if (!success) {
                    send(sender, "无法执行退出练习命令，请确认 SITSegment 已安装。");
                }
                return true;
            }

            // /menu (无参数) — 打开主菜单
            if (args.length == 0) {
                parkourMenu.openMainMenu(player);
                return true;
            }

            send(sender, "用法: /menu 或 /menu skindebug <玩家名>");
            return true;
        }

        if (!(sender instanceof Player player)) {
            send(sender, "只有玩家可以使用该命令。");
            return true;
        }

        if (name.equals("map")) {
            if (args.length != 1) {
                send(player, "用法: /map <世界名>");
                return true;
            }

            World targetWorld = Bukkit.getWorld(args[0]);
            if (targetWorld == null) {
                send(player, "未找到世界: " + args[0]);
                return true;
            }
            if (!hasMapAdmin(player) && !allowedMaps.contains(targetWorld.getName())) {
                send(player, "该世界未添加到 /map 列表。");
                return true;
            }

            teleportToSpawn(player, targetWorld);
            return true;
        }

        if (name.equals("falldamage")) {
            if (!sender.hasPermission("sitpk.falldamage")) {
                send(sender, "你没有权限使用该命令。");
                return true;
            }

            World world = player.getWorld();
            Boolean current = world.getGameRuleValue(GameRule.FALL_DAMAGE);
            boolean enabled = current == null || current;
            if (args.length == 0 || args[0].equalsIgnoreCase("toggle")) {
                enabled = !enabled;
            } else if (args[0].equalsIgnoreCase("on")) {
                enabled = true;
            } else if (args[0].equalsIgnoreCase("off")) {
                enabled = false;
            } else {
                send(sender, "用法: /falldamage [on|off|toggle]");
                return true;
            }

            world.setGameRule(GameRule.FALL_DAMAGE, enabled);
            send(sender, "世界 " + world.getName() + " 的摔落伤害现在" + (enabled ? "开启" : "关闭") + "。");
            return true;
        }

        World lobbyWorld = getLobbyWorld();
        if (lobbyWorld == null) {
            send(player, "大厅世界不可用。");
            return true;
        }

        teleportToSpawn(player, lobbyWorld);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        // /world 使用专用的多级补全（子命令 + 世界名 + 枚举值）
        if (name.equals("world")) {
            return tabCompleteWorld(sender, args);
        }

        // /menu 的多级补全（skindebug + 玩家名）
        if (name.equals("menu")) {
            return tabCompleteMenu(sender, args);
        }

        // 其余命令仅补全第一个参数（世界名）
        if (args.length != 1) {
            return List.of();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);

        if (name.equals("map")) {
            if (hasMapAdmin(sender)) {
                return Bukkit.getWorlds().stream()
                        .map(World::getName)
                        .filter(worldName -> worldName.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .sorted()
                        .toList();
            }
            return allowedMaps.stream()
                    .filter(worldName -> worldName.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .toList();
        }

        if (name.equals("addmap")) {
            if (!hasMapAdmin(sender)) {
                return List.of();
            }
            return Bukkit.getWorlds().stream()
                    .map(World::getName)
                    .filter(worldName -> !allowedMaps.contains(worldName))
                    .filter(worldName -> worldName.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .toList();
        }

        if (name.equals("delmap")) {
            if (!hasMapAdmin(sender)) {
                return List.of();
            }
            return allowedMaps.stream()
                    .filter(worldName -> worldName.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .toList();
        }

        return List.of();
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP) {
            return;
        }

        if (!ranStartupReload) {
            ranStartupReload = true;
            initializeNoCollisionTeam();
        }

        // 服务器已完全启动，此时可以安全创建/加载世界
        loadAllManagedWorlds();
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        applyWorldSettings(event.getWorld());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        applyPlayerEntryRules(player);
        loadPlayerStats(player);
        setupScoreboard(player);
        lastOnGround.put(player.getUniqueId(), player.isOnGround());
        // 缓存皮肤纹理（从 Paper PlayerProfile 中提取，不发起任何外部网络请求）
        skinCacheManager.cacheSkin(player);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        applyPlayerEntryRules(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        savePlayerStats(player.getUniqueId());
        playerBoards.remove(player.getUniqueId());
        lastOnGround.remove(player.getUniqueId());
    }

    /**
     * 死亡重生：去掉 Multiverse 后，原版会将玩家重生到主世界出生点。
     * 此处理器恢复 MV 的行为——非床/锚重生时，将重生位置设为
     * 玩家死亡所在世界的出生点。
     */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        // 床重生或重生锚重生 → 尊重玩家设置
        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }

        // 非床/锚重生 → 使用死亡时所在世界的出生点
        Player player = event.getPlayer();
        World deathWorld = player.getWorld();
        event.setRespawnLocation(deathWorld.getSpawnLocation());
    }

    /**
     * 禁用玩家通过下界门/末地门的传送。
     * 本服为跑酷/大厅服，不需要下界和末地。
     */
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        event.setCancelled(true);
    }

    /**
     * 禁用实体（物品/生物等）通过传送门的传送，
     * 避免实体把传送门"用起来"。
     */
    @EventHandler
    public void onEntityPortal(EntityPortalEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        boolean wasOnGround = lastOnGround.getOrDefault(uuid, player.isOnGround());
        boolean isOnGround = player.isOnGround();
        if (wasOnGround && !isOnGround && event.getTo() != null && event.getFrom() != null) {
            if (event.getTo().getY() > event.getFrom().getY()) {
                incrementJumps(player);
            }
        }
        lastOnGround.put(uuid, isOnGround);
    }

    private World getLobbyWorld() {
        String worldName = getConfig().getString("lobby-world");
        if (worldName == null || worldName.trim().isEmpty()) {
            return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        }
        return Bukkit.getWorld(worldName);
    }

    private void teleportToSpawn(Player player, World world) {
        if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.ADVENTURE) {
            player.setGameMode(GameMode.ADVENTURE);
        }
        player.teleport(world.getSpawnLocation());
    }

    private void applyWorldSettings(World world) {
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setSpawnFlags(false, false);
        world.setPVP(false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.PVP, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
        world.setTime(8000);
        world.setStorm(false);
        world.setThundering(false);
    }

    private void placeSpawnBedrock(World world) {
        Location spawn = world.getSpawnLocation();
        int x = spawn.getBlockX();
        int y = Math.max(world.getMinHeight(), spawn.getBlockY() - 1);
        int z = spawn.getBlockZ();
        world.getBlockAt(x, y, z).setType(Material.BEDROCK);
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(LEGACY.deserialize(MESSAGE_PREFIX + message));
    }

    private void initializeNoCollisionTeam() {
        if (!setupNoCollisionTeam()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            addPlayerToNoCollisionTeam(player);
        }
    }

    private boolean setupNoCollisionTeam() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return false;
        }
        Scoreboard scoreboard = manager.getMainScoreboard();
        Team team = scoreboard.getTeam(NO_COLLISION_TEAM_NAME);
        if (team == null) {
            team = scoreboard.registerNewTeam(NO_COLLISION_TEAM_NAME);
        }
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        this.noCollisionTeam = team;
        return true;
    }

    private void addPlayerToNoCollisionTeam(Player player) {
        if (noCollisionTeam == null && !setupNoCollisionTeam()) {
            return;
        }
        player.setCollidable(false);
        noCollisionTeam.addEntry(player.getName());

        Scoreboard customBoard = playerBoards.get(player.getUniqueId());
        if (customBoard != null) {
            Team customTeam = ensureNoCollisionTeam(customBoard);
            customTeam.addEntry(player.getName());
        }
    }

    private Team ensureNoCollisionTeam(Scoreboard board) {
        Team team = board.getTeam(NO_COLLISION_TEAM_NAME);
        if (team == null) {
            team = board.registerNewTeam(NO_COLLISION_TEAM_NAME);
        }
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        return team;
    }

    private void applyPlayerEntryRules(Player player) {
        applyWorldSettings(player.getWorld());
        if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.ADVENTURE) {
            player.setGameMode(GameMode.ADVENTURE);
        }
        player.setCollidable(false);
        addPlayerToNoCollisionTeam(player);
    }

    private void loadDataFile() {
        dataFile = new File(getDataFolder(), DATA_FILE_NAME);
        if (!dataFile.exists()) {
            File parent = dataFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void loadAllowedMaps() {
        allowedMaps.clear();
        allowedMaps.addAll(dataConfig.getStringList(MAPS_DATA_PATH));
    }

    private void saveAllowedMaps() {
        dataConfig.set(MAPS_DATA_PATH, allowedMaps.stream().sorted().toList());
        try {
            dataConfig.save(dataFile);
        } catch (Exception ignored) {
        }
    }
    private PlayerStats getOrCreateStats(UUID uuid) {
        PlayerStats existing = stats.get(uuid);
        if (existing != null) {
            return existing;
        }
        PlayerStats created = new PlayerStats();
        String base = "players." + uuid;
        created.jumps = dataConfig.getInt(base + ".jumps", 0);
        created.playtimeSeconds = dataConfig.getLong(base + ".playtimeSeconds", 0L);
        stats.put(uuid, created);
        return created;
    }

    private void loadPlayerStats(Player player) {
        getOrCreateStats(player.getUniqueId());
    }

    private void savePlayerStats(UUID uuid) {
        PlayerStats playerStats = stats.get(uuid);
        if (playerStats == null) {
            return;
        }
        String base = "players." + uuid;
        dataConfig.set(base + ".jumps", playerStats.jumps);
        dataConfig.set(base + ".playtimeSeconds", playerStats.playtimeSeconds);
        try {
            dataConfig.save(dataFile);
        } catch (Exception ignored) {
        }
    }

    private void saveAllPlayerStats() {
        for (Map.Entry<UUID, PlayerStats> entry : stats.entrySet()) {
            String base = "players." + entry.getKey();
            dataConfig.set(base + ".jumps", entry.getValue().jumps);
            dataConfig.set(base + ".playtimeSeconds", entry.getValue().playtimeSeconds);
        }
        dataConfig.set(MAPS_DATA_PATH, allowedMaps.stream().sorted().toList());
        dataConfig.set(MANAGED_WORLDS_DATA_PATH, managedWorlds.stream().sorted().toList());
        // 同时保存世界元数据
        saveWorldMetadataToConfig();
        try {
            dataConfig.save(dataFile);
        } catch (Exception ignored) {
        }
    }

    private void startScoreboardTask() {
        if (scoreboardTaskId != -1) {
            return;
        }
        scoreboardTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerStats playerStats = getOrCreateStats(player.getUniqueId());
                playerStats.playtimeSeconds += 1;
                updateScoreboard(player, playerStats);
            }
        }, 20L, 20L);
    }

    private void startSettingsTask() {
        if (settingsTaskId != -1) {
            return;
        }
        settingsTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (World world : Bukkit.getWorlds()) {
                applyWorldSettings(world);
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.setCollidable(false);
            }
        }, 40L, 100L);
    }

    private void setupScoreboard(Player player) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }
        Scoreboard board = manager.getNewScoreboard();
        Objective objective = board.registerNewObjective("sitpk", "dummy",
                ChatColor.translateAlternateColorCodes('&', SCOREBOARD_TITLE));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
        objective.setDisplayName(ChatColor.translateAlternateColorCodes('&', SCOREBOARD_TITLE));
        playerBoards.put(player.getUniqueId(), board);
        player.setScoreboard(board);

        Team collisionTeam = ensureNoCollisionTeam(board);
        collisionTeam.addEntry(player.getName());

        updateScoreboard(player, getOrCreateStats(player.getUniqueId()));
    }

    private void updateScoreboard(Player player, PlayerStats playerStats) {
        Scoreboard board = playerBoards.get(player.getUniqueId());
        if (board == null) {
            setupScoreboard(player);
            board = playerBoards.get(player.getUniqueId());
            if (board == null) {
                return;
            }
        }

        Objective objective = board.getObjective("sitpk");
        if (objective == null) {
            objective = board.registerNewObjective("sitpk", "dummy",
                    ChatColor.translateAlternateColorCodes('&', SCOREBOARD_TITLE));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        objective.numberFormat(NumberFormat.blank());
        objective.setDisplayName(ChatColor.translateAlternateColorCodes('&', SCOREBOARD_TITLE));

        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }

        List<String> lines = buildScoreboardLines(player, playerStats);
        int score = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i) + UNIQUE_SUFFIXES[i % UNIQUE_SUFFIXES.length];
            String entry = ChatColor.translateAlternateColorCodes('&', raw);
            objective.getScore(entry).setScore(score--);
        }
    }

    private List<String> buildScoreboardLines(Player player, PlayerStats playerStats) {
        String playtimeHours = hoursFormat.format(playerStats.playtimeSeconds / 3600.0);
        return SCOREBOARD_TEMPLATE.stream()
                .map(line -> line
                        .replace("%player_name%", player.getName())
                        .replace("%ping%", String.valueOf(player.getPing()))
                        .replace("%jumps%", String.valueOf(playerStats.jumps))
                        .replace("%playtime%", playtimeHours))
                .toList();
    }

    private boolean hasMapAdmin(CommandSender sender) {
        return sender.hasPermission("sitpk.addmap");
    }

    // ==================== 练习/旁观模式拦截 ====================

    /** SITSegment 插件实例引用（缓存，菜单拦截用） */
    private Object segmentPluginInstance;
    /** SITSegment#isPracticing(Player) 方法引用 */
    private java.lang.reflect.Method segmentIsPracticingMethod;
    /** SITSegment#isSpectating(Player) 方法引用 */
    private java.lang.reflect.Method segmentIsSpectatingMethod;
    /** 是否已尝试解析过 SITSegment 方法（避免重复警告） */
    private boolean segmentMethodsResolved;

    /**
     * 尝试解析 SITSegment 的 isPracticing / isSpectating 方法。
     * 仅在首次调用时执行，结果缓存到字段中。
     * @return true 表示 SITSegment 可用且方法已解析成功
     */
    private boolean resolveSegmentMethods() {
        if (segmentMethodsResolved) {
            return segmentPluginInstance != null;
        }
        segmentMethodsResolved = true;

        org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("SITSegment");
        if (plugin == null || !plugin.isEnabled()) {
            return false;
        }

        try {
            segmentIsPracticingMethod = plugin.getClass().getMethod("isPracticing", Player.class);
            segmentIsSpectatingMethod = plugin.getClass().getMethod("isSpectating", Player.class);
            segmentPluginInstance = plugin;
            return true;
        } catch (NoSuchMethodException e) {
            getLogger().warning("[PracCheck] SITSegment 未提供 isPracticing/isSpectating 方法，"
                    + "练习/旁观模式拦截不可用: " + e.getMessage());
            return false;
        }
    }

    /**
     * 查询指定玩家是否处于练习模式（/prac）或旁观模式（/spec）。
     * 若 SITSegment 不可用或反射失败，安全降级返回 false。
     */
    public boolean isInPracMode(Player player) {
        if (!resolveSegmentMethods()) {
            return false;
        }
        try {
            boolean practicing = (boolean) segmentIsPracticingMethod.invoke(segmentPluginInstance, player);
            boolean spectating = (boolean) segmentIsSpectatingMethod.invoke(segmentPluginInstance, player);
            return practicing || spectating;
        } catch (Exception e) {
            return false;
        }
    }

    private void incrementJumps(Player player) {
        PlayerStats playerStats = getOrCreateStats(player.getUniqueId());
        playerStats.jumps += 1;
    }

    private static final class PlayerStats {
        private long playtimeSeconds = 0L;
        private int jumps = 0;
    }

    private static final class VoidChunkGenerator extends ChunkGenerator {
        @Override
        public List<org.bukkit.generator.BlockPopulator> getDefaultPopulators(World world) {
            return List.of();
        }

        @Override
        public boolean shouldGenerateNoise() { return false; }

        @Override
        public boolean shouldGenerateSurface() { return false; }

        @Override
        public boolean shouldGenerateCaves() { return false; }

        @Override
        public boolean shouldGenerateDecorations() { return false; }

        @Override
        public boolean shouldGenerateMobs() { return false; }

        @Override
        public boolean shouldGenerateStructures() { return false; }
    }

    // ==================== 受管世界列表持久化 ====================

    private void loadManagedWorlds() {
        managedWorlds.clear();
        managedWorlds.addAll(dataConfig.getStringList(MANAGED_WORLDS_DATA_PATH));
    }

    private void saveManagedWorlds() {
        dataConfig.set(MANAGED_WORLDS_DATA_PATH, managedWorlds.stream().sorted().toList());
        try {
            dataConfig.save(dataFile);
        } catch (Exception ignored) {
        }
    }

    // ==================== 世界元数据持久化 ====================

    /**
     * 从 data.yml 的 "world-metadata" 节点加载所有受管世界的元数据。
     * 容错：缺字段/非法值回退默认（category 无默认=null，其余同枚举定义）。
     */
    private void loadWorldMetadata() {
        worldMetaMap.clear();
        ConfigurationSection section = dataConfig.getConfigurationSection(WORLD_META_DATA_PATH);
        if (section == null) {
            getLogger().info("[WorldMeta] data.yml 中无 world-metadata 节点，初始化为空。");
            return;
        }

        int loaded = 0;
        for (String worldName : section.getKeys(false)) {
            ConfigurationSection worldSec = section.getConfigurationSection(worldName);
            if (worldSec == null) continue;

            WorldMeta meta = new WorldMeta();

            // category: 四选一枚举，非法/缺失 → null（不计入任何分类）
            String catStr = worldSec.getString("category");
            if (catStr != null) {
                meta.category = ParkourGameType.fromString(catStr);
                if (meta.category == null) {
                    getLogger().warning("[WorldMeta] 世界 " + worldName + " 的 category 值非法: " + catStr + "，视为未设置。");
                }
            }

            // timingMode: 仅 PKW 有意义，缺省 null
            String timingStr = worldSec.getString("timing-mode");
            if (timingStr != null) {
                meta.timingMode = TimingMode.fromString(timingStr);
            }

            // author: 默认 ""
            meta.author = worldSec.getString("author", "");

            // status: 大小写不敏感查找，缺省/非法 → UNRELEASED
            String statusStr = worldSec.getString("status");
            if (statusStr != null && !statusStr.isEmpty()) {
                MapStatus parsed = MapStatus.fromKey(statusStr.toUpperCase());
                meta.status = (parsed != MapStatus.UNKNOWN) ? parsed : MapStatus.UNRELEASED;
                if (parsed == MapStatus.UNKNOWN) {
                    getLogger().warning("[WorldMeta] 世界 " + worldName + " 的 status 值非法: " + statusStr + "，回退为 UNRELEASED。");
                }
            }

            // 自定义图标（Bukkit Material 名），可选
            meta.icon = worldSec.getString("icon");
            if (meta.icon != null && meta.icon.isEmpty()) meta.icon = null;

            // 自定义展示名，可选
            meta.displayName = worldSec.getString("display-name");
            if (meta.displayName != null && meta.displayName.isEmpty()) meta.displayName = null;

            // 自定义类型展示名，可选
            meta.typeName = worldSec.getString("type-name");
            if (meta.typeName != null && meta.typeName.isEmpty()) meta.typeName = null;

            // 难度描述，可选
            meta.difficulty = worldSec.getString("difficulty");
            if (meta.difficulty != null && meta.difficulty.isEmpty()) meta.difficulty = null;

            // 地图简介，可选
            meta.introduction = worldSec.getString("introduction");
            if (meta.introduction != null && meta.introduction.isEmpty()) meta.introduction = null;

            // 固定槽位：非法值回退 -1
            meta.fixedPage = worldSec.getInt("fixed-page", -1);
            meta.fixedSlot = worldSec.getInt("fixed-slot", -1);
            if (meta.fixedPage < 1 || meta.fixedSlot < 0 || meta.fixedSlot >= 45) {
                if (meta.fixedPage >= 1 || meta.fixedSlot >= 0) {
                    getLogger().warning("[WorldMeta] 世界 " + worldName + " 的 fixed-page/fixed-slot 非法"
                            + " (page=" + meta.fixedPage + ", slot=" + meta.fixedSlot + ")，回退为未设置。");
                }
                meta.fixedPage = -1;
                meta.fixedSlot = -1;
            }

            worldMetaMap.put(worldName, meta);
            loaded++;
        }

        getLogger().info("[WorldMeta] 已加载 " + loaded + " 个世界的元数据。");
    }

    /**
     * 将内存中的 worldMetaMap 序列化到 dataConfig 的 "world-metadata" 节点。
     * 不立即 save 文件——由调用方（saveAllPlayerStats / 各命令处理）负责 save。
     */
    private void saveWorldMetadataToConfig() {
        // 先清除旧节点，再全量重写（处理世界被移除的情况）
        dataConfig.set(WORLD_META_DATA_PATH, null);

        for (Map.Entry<String, WorldMeta> entry : worldMetaMap.entrySet()) {
            String worldName = entry.getKey();
            WorldMeta meta = entry.getValue();
            String path = WORLD_META_DATA_PATH + "." + worldName;

            dataConfig.set(path + ".category", meta.category != null ? meta.category.name() : null);
            dataConfig.set(path + ".timing-mode", meta.timingMode != null ? meta.timingMode.name() : null);
            dataConfig.set(path + ".author", meta.author != null ? meta.author : "");
            dataConfig.set(path + ".status", meta.status != null ? meta.status.key : MapStatus.UNRELEASED.key);
            dataConfig.set(path + ".icon", (meta.icon != null && !meta.icon.isEmpty()) ? meta.icon : null);
            dataConfig.set(path + ".display-name", (meta.displayName != null && !meta.displayName.isEmpty()) ? meta.displayName : null);
            dataConfig.set(path + ".type-name", (meta.typeName != null && !meta.typeName.isEmpty()) ? meta.typeName : null);
            dataConfig.set(path + ".difficulty", (meta.difficulty != null && !meta.difficulty.isEmpty()) ? meta.difficulty : null);
            dataConfig.set(path + ".introduction", (meta.introduction != null && !meta.introduction.isEmpty()) ? meta.introduction : null);
            dataConfig.set(path + ".fixed-page", meta.fixedPage >= 1 ? meta.fixedPage : null);
            dataConfig.set(path + ".fixed-slot", meta.fixedSlot >= 0 && meta.fixedSlot < 45 ? meta.fixedSlot : null);
        }
    }

    /**
     * 保存世界元数据到磁盘。
     */
    private void saveWorldMetadata() {
        saveWorldMetadataToConfig();
        try {
            dataConfig.save(dataFile);
        } catch (Exception ignored) {
        }
    }

    // ==================== 服务器就绪后加载受管世界 ====================

    /**
     * 在服务器完全启动后加载受管世界。
     * 由 ServerLoadEvent (主) 和 onEnable 末尾的 runTask (兜底) 共同触发，
     * startupWorldsLoaded 标志位确保无论谁先触发，都只执行一次。
     */
    private void loadAllManagedWorlds() {
        if (startupWorldsLoaded) {
            return;
        }
        startupWorldsLoaded = true;

        if (!managedWorlds.isEmpty()) {
            getLogger().info("正在加载 " + managedWorlds.size() + " 个受管世界...");
            for (String worldName : managedWorlds) {
                loadSingleManagedWorld(worldName);
            }
        }

        // 为所有受管世界中尚无元数据记录的世界初始化默认元数据
        initMissingWorldMeta();

        // 刷新数据提供者
        dataProvider.setWorldMetadata(worldMetaMap);

        // 所有受管世界加载完成，广播事件通知其他插件（如 SITSegment）
        getLogger().info("所有受管世界加载完成，广播 ManagedWorldsLoadedEvent (受管世界数: " + managedWorlds.size() + ")");
        Bukkit.getPluginManager().callEvent(new ManagedWorldsLoadedEvent(List.copyOf(managedWorlds)));
    }

    /**
     * 为受管世界中尚无元数据记录的世界创建默认 WorldMeta（category=null）。
     * 确保每个受管世界在 worldMetaMap 中都有条目，方便 /world list 和后续配置。
     */
    private void initMissingWorldMeta() {
        boolean changed = false;
        for (String worldName : managedWorlds) {
            if (!worldMetaMap.containsKey(worldName)) {
                worldMetaMap.put(worldName, new WorldMeta());
                changed = true;
                getLogger().info("[WorldMeta] 为新受管世界初始化默认元数据: " + worldName);
            }
        }
        if (changed) {
            saveWorldMetadata();
        }
    }

    private void loadSingleManagedWorld(String worldName) {
        if (Bukkit.getWorld(worldName) != null) {
            getLogger().info("受管世界已加载，跳过: " + worldName);
            return;
        }
        try {
            WorldCreator creator = new WorldCreator(worldName);
            creator.generator(new VoidChunkGenerator());
            creator.biomeProvider(new VoidBiomeProvider());
            World world = creator.createWorld();
            if (world == null) {
                getLogger().warning("加载受管世界失败 (createWorld 返回 null): " + worldName);
            } else {
                // applyWorldSettings 会由 WorldLoadEvent 触发，此处显式调用确保覆盖
                applyWorldSettings(world);
                getLogger().info("已加载受管世界: " + worldName);
            }
        } catch (Exception e) {
            getLogger().warning("加载受管世界异常: " + worldName + " — " + e.getMessage());
        }
    }

    // ==================== /world 命令处理 ====================

    private boolean handleWorldCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sitpk.world")) {
            send(sender, "你没有权限使用该命令。");
            return true;
        }

        if (args.length == 0) {
            send(sender, "用法: /world <import|add|remove|tp|list|category|timing|author|status|mapinfo> [参数...]");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "import":
                return handleWorldImport(sender, args);
            case "add":
                return handleWorldAdd(sender, args);
            case "remove":
                return handleWorldRemove(sender, args);
            case "tp":
                return handleWorldTp(sender, args);
            case "list":
                return handleWorldList(sender);
            case "category":
                return handleWorldCategory(sender, args);
            case "timing":
                return handleWorldTiming(sender, args);
            case "author":
                return handleWorldAuthor(sender, args);
            case "status":
                return handleWorldStatus(sender, args);
            case "mapinfo":
                return handleWorldMapInfo(sender, args);
            case "icon":
                return handleWorldIcon(sender, args);
            case "displayname":
                return handleWorldDisplayName(sender, args);
            case "typename":
                return handleWorldTypeName(sender, args);
            case "difficulty":
                return handleWorldDifficulty(sender, args);
            case "intro":
                return handleWorldIntro(sender, args);
            case "slot":
                return handleWorldSlot(sender, args);
            default:
                send(sender, "未知子命令: " + sub + "。可用: import, add, remove, tp, list, category, timing, author, status, mapinfo, icon, displayname, typename, difficulty, intro, slot");
                return true;
        }
    }

    private boolean handleWorldImport(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "用法: /world import <世界名>");
            return true;
        }

        String worldName = args[1];

        // 检查世界文件夹是否存在
        File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
        if (!worldFolder.exists() || !worldFolder.isDirectory()) {
            send(sender, "世界文件夹不存在: " + worldName + " (路径: " + worldFolder.getAbsolutePath() + ")");
            return true;
        }

        // 如果已在受管列表中
        if (managedWorlds.contains(worldName)) {
            send(sender, "该世界已在受管列表中: " + worldName);
            return true;
        }

        // 如果未加载，加载它
        if (Bukkit.getWorld(worldName) == null) {
            try {
                WorldCreator creator = new WorldCreator(worldName);
                creator.generator(new VoidChunkGenerator());
                creator.biomeProvider(new VoidBiomeProvider());
                World world = creator.createWorld();
                if (world == null) {
                    send(sender, "导入失败，无法加载世界: " + worldName);
                    return true;
                }
                applyWorldSettings(world);
                getLogger().info("导入时加载世界: " + worldName);
            } catch (Exception e) {
                send(sender, "导入失败，加载异常: " + e.getMessage());
                getLogger().warning("导入世界异常: " + worldName + " — " + e.getMessage());
                return true;
            }
        }

        managedWorlds.add(worldName);
        saveManagedWorlds();

        // 为新导入的世界初始化默认元数据
        if (!worldMetaMap.containsKey(worldName)) {
            worldMetaMap.put(worldName, new WorldMeta());
            saveWorldMetadata();
        }

        // 刷新数据提供者
        dataProvider.setWorldMetadata(worldMetaMap);

        send(sender, "已导入并纳入受管列表: " + worldName + " &7(提示: 请用 /world category 设置玩法大类)");
        return true;
    }

    private boolean handleWorldAdd(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "用法: /world add <世界名>");
            return true;
        }

        String worldName = args[1];

        if (Bukkit.getWorld(worldName) != null) {
            send(sender, "世界已存在: " + worldName);
            return true;
        }

        WorldCreator creator = new WorldCreator(worldName);
        creator.generator(new VoidChunkGenerator());
        creator.biomeProvider(new VoidBiomeProvider());
        World created;
        try {
            created = creator.createWorld();
        } catch (Exception e) {
            send(sender, "创建世界失败: " + e.getMessage());
            getLogger().warning("创建世界异常: " + worldName + " — " + e.getMessage());
            return true;
        }

        if (created == null) {
            send(sender, "创建世界失败: " + worldName);
            return true;
        }

        applyWorldSettings(created);
        placeSpawnBedrock(created);
        managedWorlds.add(worldName);
        saveManagedWorlds();

        // 为新创建的世界初始化默认元数据
        if (!worldMetaMap.containsKey(worldName)) {
            worldMetaMap.put(worldName, new WorldMeta());
            saveWorldMetadata();
        }

        // 刷新数据提供者
        dataProvider.setWorldMetadata(worldMetaMap);

        send(sender, "已创建虚空世界并纳入受管列表: " + worldName + " &7(提示: 请用 /world category 设置玩法大类)");
        if (sender instanceof Player player) {
            teleportToSpawn(player, created);
        }
        return true;
    }

    private boolean handleWorldRemove(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "用法: /world remove <世界名>");
            return true;
        }

        String worldName = args[1];

        if (!managedWorlds.contains(worldName)) {
            send(sender, "该世界不在受管列表中: " + worldName);
            return true;
        }

        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            // 疏散玩家到大厅世界
            World lobbyWorld = getLobbyWorld();
            if (lobbyWorld != null && !lobbyWorld.equals(world)) {
                for (Player player : world.getPlayers()) {
                    send(player, "世界 " + worldName + " 正在被卸载，已将你传送回大厅。");
                    player.teleport(lobbyWorld.getSpawnLocation());
                }
            }

            // 卸载世界 (save=true 保存但不物理删除)
            boolean unloaded = Bukkit.unloadWorld(world, true);
            if (!unloaded) {
                send(sender, "卸载世界失败: " + worldName);
                return true;
            }
        }

        managedWorlds.remove(worldName);
        saveManagedWorlds();

        // 清理元数据
        worldMetaMap.remove(worldName);
        saveWorldMetadata();

        // 刷新数据提供者
        dataProvider.setWorldMetadata(worldMetaMap);

        send(sender, "已卸载并从受管列表移除: " + worldName + " (世界文件夹已保留，元数据已清理)");
        return true;
    }

    private boolean handleWorldTp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "只有玩家可以使用该命令。");
            return true;
        }

        if (args.length != 2) {
            send(sender, "用法: /world tp <世界名>");
            return true;
        }

        World targetWorld = Bukkit.getWorld(args[1]);
        if (targetWorld == null) {
            send(sender, "未找到世界: " + args[1]);
            return true;
        }

        teleportToSpawn(player, targetWorld);
        String worldName = targetWorld.getName();
        WorldMeta meta = worldMetaMap.get(worldName);
        String showName = (meta != null && meta.displayName != null && !meta.displayName.isEmpty())
                ? meta.displayName : worldName;
        send(sender, "已传送到: &b" + showName + " &7(" + worldName + ")");
        return true;
    }

    private boolean handleWorldList(CommandSender sender) {
        if (managedWorlds.isEmpty()) {
            send(sender, "受管世界列表为空。");
            return true;
        }

        List<String> list = managedWorlds.stream().sorted().map(worldName -> {
            boolean loaded = Bukkit.getWorld(worldName) != null;
            WorldMeta meta = worldMetaMap.get(worldName);
            String loadStatus = loaded ? "&a已加载" : "&c未加载";

            String catStr;
            if (meta == null || meta.category == null) {
                catStr = "&e未设置分类";
            } else {
                catStr = "&f" + meta.category.chineseName;
            }

            String statusStr = "";
            if (meta != null && meta.status != null) {
                statusStr = " " + meta.status.colorCode + meta.status.chineseName;
            }

            return (loaded ? "&a" : "&c") + worldName
                    + "&7(" + loadStatus + "&7, " + catStr + "&7" + statusStr + "&7)";
        }).toList();

        send(sender, "受管世界(" + managedWorlds.size() + "): " + String.join("&7, ", list));
        return true;
    }

    // ==================== /world category ====================

    private boolean handleWorldCategory(CommandSender sender, String[] args) {
        // /world category — 列出未设置分类的世界
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            List<String> uncategorized = managedWorlds.stream()
                    .filter(w -> {
                        WorldMeta meta = worldMetaMap.get(w);
                        return meta == null || meta.category == null;
                    })
                    .sorted()
                    .toList();

            if (uncategorized.isEmpty()) {
                send(sender, "&a所有受管世界均已设置分类。");
            } else {
                send(sender, "&e未设置分类的受管世界 (" + uncategorized.size() + "): &f" + String.join("&7, &f", uncategorized));
                send(sender, "&7使用 &f/world category <世界名> <PKW|SEGMENT|ONLYSPRINT|RANKUP> &7设置分类。");
            }
            return true;
        }

        // /world category <世界名> <PKW|SEGMENT|ONLYSPRINT|RANKUP>
        if (args.length < 3) {
            send(sender, "用法: /world category <世界名> <PKW|SEGMENT|ONLYSPRINT|RANKUP>");
            send(sender, "或  /world category list  — 查看未设置分类的世界");
            return true;
        }

        String worldName = args[1];
        if (!managedWorlds.contains(worldName)) {
            send(sender, "该世界不在受管列表中: " + worldName);
            return true;
        }

        ParkourGameType category = ParkourGameType.fromString(args[2]);
        if (category == null) {
            send(sender, "非法分类: " + args[2] + "。可选: PKW, SEGMENT, ONLYSPRINT, RANKUP");
            return true;
        }

        WorldMeta meta = worldMetaMap.computeIfAbsent(worldName, k -> new WorldMeta());
        meta.category = category;

        // 如果从 PKW 切换到非 PKW，清除 timingMode
        if (category != ParkourGameType.PKW) {
            meta.timingMode = null;
        }

        saveWorldMetadata();
        dataProvider.setWorldMetadata(worldMetaMap);
        send(sender, "世界 " + worldName + " 的玩法大类已设置为: &b" + category.chineseName + " &7(" + category.name() + ")");
        return true;
    }

    // ==================== /world timing ====================

    private boolean handleWorldTiming(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "用法: /world timing <世界名> <COUNTUP|COUNTDOWN>");
            return true;
        }

        String worldName = args[1];
        if (!managedWorlds.contains(worldName)) {
            send(sender, "该世界不在受管列表中: " + worldName);
            return true;
        }

        WorldMeta meta = worldMetaMap.get(worldName);
        if (meta == null || meta.category == null) {
            send(sender, "请先设置该世界的玩法大类: /world category " + worldName + " <类型>");
            return true;
        }

        if (meta.category != ParkourGameType.PKW) {
            send(sender, "仅 PKW 类型可设置计时模式。当前分类: " + meta.category.chineseName);
            return true;
        }

        TimingMode timing = TimingMode.fromString(args[2]);
        if (timing == null) {
            send(sender, "非法计时模式: " + args[2] + "。可选: COUNTUP, COUNTDOWN");
            return true;
        }

        meta.timingMode = timing;
        saveWorldMetadata();
        dataProvider.setWorldMetadata(worldMetaMap);
        send(sender, "世界 " + worldName + " 的计时模式已设置为: &b" + timing.chineseName + " &7(" + timing.name() + ")");
        return true;
    }

    // ==================== /world author ====================

    private boolean handleWorldAuthor(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "用法: /world author <世界名> <作者名>");
            return true;
        }

        String worldName = args[1];
        if (!managedWorlds.contains(worldName)) {
            send(sender, "该世界不在受管列表中: " + worldName);
            return true;
        }

        // 作者名可包含空格：从 args[2] 开始拼接
        String author;
        if (args.length >= 3) {
            StringBuilder sb = new StringBuilder(args[2]);
            for (int i = 3; i < args.length; i++) {
                sb.append(' ').append(args[i]);
            }
            author = sb.toString();
        } else {
            author = "";
        }

        WorldMeta meta = worldMetaMap.computeIfAbsent(worldName, k -> new WorldMeta());
        // & → § 转换后存储，与 displayName/typeName 一致
        meta.author = (!author.isEmpty())
                ? org.bukkit.ChatColor.translateAlternateColorCodes('&', author) : "";
        saveWorldMetadata();
        dataProvider.setWorldMetadata(worldMetaMap);

        if (author.isEmpty()) {
            send(sender, "世界 " + worldName + " 的作者已清除。");
        } else {
            send(sender, "世界 " + worldName + " 的作者已设置为: &b" + author);
        }
        return true;
    }

    // ==================== /world status ====================

    private boolean handleWorldStatus(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "用法: /world status <世界名> <OPEN|CLOSED|UNRELEASED|EVENT|TESTING>");
            return true;
        }

        String worldName = args[1];
        if (!managedWorlds.contains(worldName)) {
            send(sender, "该世界不在受管列表中: " + worldName);
            return true;
        }

        // 大小写不敏感查找
        MapStatus status = MapStatus.fromKey(args[2].toUpperCase());
        if (status == MapStatus.UNKNOWN) {
            send(sender, "非法状态: " + args[2] + "。可选: OPEN, CLOSED, UNRELEASED, EVENT, TESTING");
            return true;
        }

        WorldMeta meta = worldMetaMap.computeIfAbsent(worldName, k -> new WorldMeta());
        meta.status = status;
        saveWorldMetadata();
        dataProvider.setWorldMetadata(worldMetaMap);
        send(sender, "世界 " + worldName + " 的状态已设置为: " + status.colorCode + status.chineseName + " &7(" + status.key + ")");
        return true;
    }

    // ==================== /world mapinfo ====================

    private boolean handleWorldMapInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "用法: /world mapinfo <世界名>");
            return true;
        }

        String worldName = args[1];
        if (!managedWorlds.contains(worldName)) {
            send(sender, "该世界不在受管列表中: " + worldName);
            return true;
        }

        WorldMeta meta = worldMetaMap.get(worldName);
        if (meta == null) {
            send(sender, "世界 " + worldName + " 暂无元数据，请先使用 /world category 设置分类。");
            return true;
        }

        boolean loaded = Bukkit.getWorld(worldName) != null;

        send(sender, "&b===== 地图元数据: &f" + worldName + " &b=====");
        send(sender, "&7世界名: &f" + worldName);
        send(sender, "&7加载状态: " + (loaded ? "&a已加载" : "&c未加载"));

        if (meta.category != null) {
            send(sender, "&7玩法大类: &f" + meta.category.chineseName + " &7(" + meta.category.name() + ")");
        } else {
            send(sender, "&7玩法大类: &e未设置");
        }

        if (meta.category == ParkourGameType.PKW && meta.timingMode != null) {
            send(sender, "&7计时模式: &f" + meta.timingMode.chineseName + " &7(" + meta.timingMode.name() + ")");
        } else if (meta.category == ParkourGameType.PKW) {
            send(sender, "&7计时模式: &e未设置 &7(仅PKW类型需要)");
        }

        send(sender, "&7作者: &f" + (meta.author != null && !meta.author.isEmpty() ? meta.author : "—"));
        send(sender, "&7状态: " + meta.status.colorCode + meta.status.chineseName + " &7(" + meta.status.key + ")");

        // 新增四个自定义字段
        send(sender, "&7自定义图标: " + (meta.icon != null ? "&f" + meta.icon + (meta.resolveIcon() == null ? " &c(非法材质)" : "") : "&8(默认)"));
        send(sender, "&7展示名: " + (meta.displayName != null ? "&f" + meta.displayName : "&8(默认, 使用世界名)"));
        send(sender, "&7类型展示名: " + (meta.typeName != null ? "&f" + meta.typeName : "&8(默认, 使用类型映射)"));
        send(sender, "&7难度: " + (meta.difficulty != null ? "&f" + meta.difficulty : "&8(未设置, 不显示)"));
        send(sender, "&7简介: " + (meta.introduction != null ? "&f" + meta.introduction : "&8(未设置, 不显示)"));
        // 固定槽位
        if (meta.fixedPage >= 1 && meta.fixedSlot >= 0 && meta.fixedSlot < 45) {
            send(sender, "&7固定槽位: &fpage " + meta.fixedPage + " slot " + meta.fixedSlot);
        } else {
            send(sender, "&7固定槽位: &8(未设置)");
        }
        return true;
    }

    // ==================== /world icon ====================

    private boolean handleWorldIcon(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "用法: /world icon <世界名> <材质名|reset>");
            send(sender, "&7重置为默认: /world icon <世界名> reset");
            send(sender, "&7常用材质示例: DIAMOND_BLOCK, EMERALD_BLOCK, NETHER_STAR, PLAYER_HEAD, ENDER_PEARL");
            return true;
        }

        String worldName = args[1];
        if (!managedWorlds.contains(worldName)) {
            send(sender, "该世界不在受管列表中: " + worldName);
            return true;
        }

        String value = args[2];

        // 清除/重置
        if (value.equalsIgnoreCase("reset")) {
            WorldMeta meta = worldMetaMap.get(worldName);
            if (meta != null) meta.icon = null;
            saveWorldMetadata();
            dataProvider.setWorldMetadata(worldMetaMap);
            send(sender, "世界 " + worldName + " 的自定义图标已清除，恢复默认。");
            return true;
        }

        // 校验材质合法性
        Material mat = Material.matchMaterial(value);
        if (mat == null || !mat.isItem()) {
            send(sender, "&c非法材质名: " + value + "。请使用 Bukkit Material 枚举名。");
            send(sender, "&7常用示例: DIAMOND_BLOCK, EMERALD_BLOCK, NETHER_STAR, PLAYER_HEAD, ENDER_PEARL, GLOBE_BANNER_PATTERN");
            return true;
        }

        WorldMeta meta = worldMetaMap.computeIfAbsent(worldName, k -> new WorldMeta());
        meta.icon = mat.name(); // 存规范名
        saveWorldMetadata();
        dataProvider.setWorldMetadata(worldMetaMap);
        send(sender, "世界 " + worldName + " 的自定义图标已设置为: &b" + mat.name());
        return true;
    }

    // ==================== /world displayname ====================

    private boolean handleWorldDisplayName(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "用法: /world displayname <世界名> <展示名...|reset>");
            send(sender, "&7重置为默认: /world displayname <世界名> reset");
            send(sender, "&7支持颜色代码 &0-9a-fk-o 和空格");
            return true;
        }

        String worldName = args[1];
        if (!managedWorlds.contains(worldName)) {
            send(sender, "该世界不在受管列表中: " + worldName);
            return true;
        }

        // 若第三个参数是 reset，清除
        String value;
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            value = null;
        } else if (args.length >= 3) {
            StringBuilder sb = new StringBuilder(args[2]);
            for (int i = 3; i < args.length; i++) {
                sb.append(' ').append(args[i]);
            }
            value = sb.toString();
        } else {
            send(sender, "用法: /world displayname <世界名> <展示名...|reset>");
            return true;
        }

        WorldMeta meta = worldMetaMap.computeIfAbsent(worldName, k -> new WorldMeta());
        // & → § 转换后存储，确保 § 格式在菜单中直接可用
        meta.displayName = (value != null && !value.isEmpty())
                ? org.bukkit.ChatColor.translateAlternateColorCodes('&', value) : null;
        saveWorldMetadata();
        dataProvider.setWorldMetadata(worldMetaMap);

        if (meta.displayName == null) {
            send(sender, "世界 " + worldName + " 的展示名已清除，恢复使用世界名。");
        } else {
            send(sender, "世界 " + worldName + " 的展示名已设置为: &b" + meta.displayName);
        }
        return true;
    }

    // ==================== /world typename ====================

    private boolean handleWorldTypeName(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "用法: /world typename <世界名> <类型展示名...|reset>");
            send(sender, "&7重置为默认: /world typename <世界名> reset");
            send(sender, "&7支持颜色代码 &0-9a-fk-o 和空格");
            return true;
        }

        String worldName = args[1];
        if (!managedWorlds.contains(worldName)) {
            send(sender, "该世界不在受管列表中: " + worldName);
            return true;
        }

        String value;
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            value = null;
        } else if (args.length >= 3) {
            StringBuilder sb = new StringBuilder(args[2]);
            for (int i = 3; i < args.length; i++) {
                sb.append(' ').append(args[i]);
            }
            value = sb.toString();
        } else {
            send(sender, "用法: /world typename <世界名> <类型展示名...|reset>");
            return true;
        }

        WorldMeta meta = worldMetaMap.computeIfAbsent(worldName, k -> new WorldMeta());
        // & → § 转换后存储
        meta.typeName = (value != null && !value.isEmpty())
                ? org.bukkit.ChatColor.translateAlternateColorCodes('&', value) : null;
        saveWorldMetadata();
        dataProvider.setWorldMetadata(worldMetaMap);

        if (meta.typeName == null) {
            send(sender, "世界 " + worldName + " 的类型展示名已清除，恢复默认类型映射。");
        } else {
            send(sender, "世界 " + worldName + " 的类型展示名已设置为: &b" + meta.typeName);
        }
        return true;
    }

    // ==================== /world difficulty ====================

    private boolean handleWorldDifficulty(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "用法: /world difficulty <世界名> <难度描述...|reset>");
            send(sender, "&7重置为默认: /world difficulty <世界名> reset");
            send(sender, "&7支持颜色代码 &0-9a-fk-o 和空格");
            return true;
        }

        String worldName = args[1];
        if (!managedWorlds.contains(worldName)) {
            send(sender, "该世界不在受管列表中: " + worldName);
            return true;
        }

        String value;
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            value = null;
        } else if (args.length >= 3) {
            StringBuilder sb = new StringBuilder(args[2]);
            for (int i = 3; i < args.length; i++) {
                sb.append(' ').append(args[i]);
            }
            value = sb.toString();
        } else {
            send(sender, "用法: /world difficulty <世界名> <难度描述...|reset>");
            return true;
        }

        WorldMeta meta = worldMetaMap.computeIfAbsent(worldName, k -> new WorldMeta());
        // & → § 转换后存储
        meta.difficulty = (value != null && !value.isEmpty())
                ? org.bukkit.ChatColor.translateAlternateColorCodes('&', value) : null;
        saveWorldMetadata();
        dataProvider.setWorldMetadata(worldMetaMap);

        if (meta.difficulty == null) {
            send(sender, "世界 " + worldName + " 的难度已清除，不再显示难度行。");
        } else {
            send(sender, "世界 " + worldName + " 的难度已设置为: &b" + meta.difficulty);
        }
        return true;
    }

    // ==================== /world intro ====================

    private boolean handleWorldIntro(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "用法: /world intro <世界名> <简介内容...|reset>");
            send(sender, "&7重置为默认: /world intro <世界名> reset");
            send(sender, "&7支持颜色代码 &0-9a-fk-o 和空格");
            return true;
        }

        String worldName = args[1];
        if (!managedWorlds.contains(worldName)) {
            send(sender, "该世界不在受管列表中: " + worldName);
            return true;
        }

        String value;
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            value = null;
        } else if (args.length >= 3) {
            StringBuilder sb = new StringBuilder(args[2]);
            for (int i = 3; i < args.length; i++) {
                sb.append(' ').append(args[i]);
            }
            value = sb.toString();
        } else {
            send(sender, "用法: /world intro <世界名> <简介内容...|reset>");
            return true;
        }

        WorldMeta meta = worldMetaMap.computeIfAbsent(worldName, k -> new WorldMeta());
        // & → § 转换后存储
        meta.introduction = (value != null && !value.isEmpty())
                ? org.bukkit.ChatColor.translateAlternateColorCodes('&', value) : null;
        saveWorldMetadata();
        dataProvider.setWorldMetadata(worldMetaMap);

        if (meta.introduction == null) {
            send(sender, "世界 " + worldName + " 的简介已清除，不再显示。");
        } else {
            send(sender, "世界 " + worldName + " 的简介已设置为: &b" + meta.introduction);
        }
        return true;
    }

    // ==================== /world slot ====================

    private boolean handleWorldSlot(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "用法: /world slot <世界名> <页码> <slot号>  或  /world slot <世界名> reset");
            return true;
        }

        String worldName = args[1];
        if (!managedWorlds.contains(worldName)) {
            send(sender, "该世界不在受管列表中: " + worldName);
            return true;
        }

        // /world slot <世界名> reset/clear/- → 清除固定
        if (args.length == 3 && (args[2].equalsIgnoreCase("reset")
                || args[2].equalsIgnoreCase("clear") || args[2].equals("-"))) {
            WorldMeta meta = worldMetaMap.get(worldName);
            if (meta == null) {
                send(sender, "世界 " + worldName + " 暂无元数据，无需清除固定槽位。");
                return true;
            }
            meta.fixedPage = -1;
            meta.fixedSlot = -1;
            saveWorldMetadata();
            dataProvider.setWorldMetadata(worldMetaMap);
            send(sender, "世界 " + worldName + " 的固定槽位已清除，恢复自动排序。");
            return true;
        }

        // /world slot <世界名> <页码> <slot号> → 设置固定
        if (args.length != 4) {
            send(sender, "用法: /world slot <世界名> <页码> <slot号>  或  /world slot <世界名> reset");
            return true;
        }

        int page, slot;
        try {
            page = Integer.parseInt(args[2]);
            slot = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            send(sender, "&c页码和 slot 号必须为整数。");
            return true;
        }

        // 校验页码
        if (page < 1) {
            send(sender, "&c页码必须 ≥ 1。");
            return true;
        }

        // 校验 slot 范围
        if (slot < 0 || slot > 53) {
            send(sender, "&cslot 号必须在 0~53 范围内。");
            return true;
        }

        // 功能保留区拦截（slot 45~53 为导航/边框行）
        if (slot >= 45) {
            send(sender, "&c该位置为界面保留位（slot 45~53 为功能区: "
                    + "45=返回按钮, 46=边框, 47=上一页, 48=边框, 49=下一页, 50~53=边框），不可放置地图。");
            return true;
        }

        // 前置条件：必须先设玩法大类
        WorldMeta meta = worldMetaMap.computeIfAbsent(worldName, k -> new WorldMeta());
        ParkourGameType gameType = meta.category;
        if (gameType == null) {
            send(sender, "&c请先设置该世界的玩法大类: /world category " + worldName + " <PKW|SEGMENT|ONLYSPRINT|RANKUP>");
            return true;
        }

        // 冲突检测：同玩法大类内，若已有其他地图占用了相同 page+slot，则清除旧地图的固定
        for (Map.Entry<String, WorldMeta> entry : worldMetaMap.entrySet()) {
            String otherName = entry.getKey();
            WorldMeta otherMeta = entry.getValue();
            if (otherName.equals(worldName)) continue; // 跳过自身
            if (otherMeta.category != gameType) continue; // 不同玩法大类不冲突
            if (otherMeta.fixedPage == page && otherMeta.fixedSlot == slot) {
                // 冲突！清除旧地图的固定槽位
                otherMeta.fixedPage = -1;
                otherMeta.fixedSlot = -1;
                String oldDisplay = (otherMeta.displayName != null && !otherMeta.displayName.isEmpty())
                        ? otherMeta.displayName : otherName;
                send(sender, "&e该位置原由 &b" + oldDisplay + " &e占用，&b"
                        + oldDisplay + " &e已回退为自动排序。");
                break; // 命令层已保证唯一，只应有一个冲突
            }
        }

        meta.fixedPage = page;
        meta.fixedSlot = slot;
        saveWorldMetadata();
        dataProvider.setWorldMetadata(worldMetaMap);
        send(sender, "世界 " + worldName + " 已固定在第 &b" + page + " &f页的第 &b" + slot + " &f格。");
        return true;
    }

    // ==================== /world Tab 补全 ====================

    private List<String> tabCompleteWorld(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sitpk.world")) {
            return List.of();
        }

        // ---- 第一级：子命令名 ----
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("import", "add", "remove", "tp", "list",
                    "category", "timing", "author", "status", "mapinfo",
                    "icon", "displayname", "typename", "difficulty", "intro",
                    "slot").stream()
                    .filter(sub -> sub.startsWith(prefix))
                    .toList();
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // ---- 第二级：世界名（按子命令语义筛选未配置项） ----
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);

            switch (sub) {
                case "import":
                    // 建议存在文件夹但未在受管列表中的世界
                    File container = Bukkit.getWorldContainer();
                    File[] dirs = container.listFiles(File::isDirectory);
                    if (dirs == null) return List.of();
                    return java.util.Arrays.stream(dirs)
                            .map(File::getName)
                            .filter(name -> !managedWorlds.contains(name))
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .sorted()
                            .toList();

                case "remove":
                case "tp":
                case "mapinfo":
                case "icon":
                case "displayname":
                case "typename":
                case "difficulty":
                case "intro":
                case "slot":
                    // 操作类/自定义字段命令：显示全部受管世界
                    return managedWorlds.stream()
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .sorted()
                            .toList();

                case "category": {
                    // 仅显示未设置 category 的世界 + "list" 关键字
                    List<String> suggestions = new java.util.ArrayList<>(
                            managedWorlds.stream()
                                    .filter(name -> {
                                        WorldMeta m = worldMetaMap.get(name);
                                        return m == null || m.category == null;
                                    })
                                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                                    .sorted()
                                    .toList()
                    );
                    if ("list".startsWith(prefix)) {
                        suggestions.add("list");
                    }
                    return suggestions;
                }

                case "timing":
                    // 仅显示 category=PKW 且未设置 timingMode 的世界
                    return managedWorlds.stream()
                            .filter(name -> {
                                WorldMeta m = worldMetaMap.get(name);
                                return m != null
                                        && m.category == ParkourGameType.PKW
                                        && m.timingMode == null;
                            })
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .sorted()
                            .toList();

                case "author":
                    // 仅显示未设置 author（空字符串或 null）的世界
                    return managedWorlds.stream()
                            .filter(name -> {
                                WorldMeta m = worldMetaMap.get(name);
                                return m == null || m.author == null || m.author.isEmpty();
                            })
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .sorted()
                            .toList();

                case "status":
                    // 仅显示 status 仍为默认值 UNRELEASED 的世界
                    return managedWorlds.stream()
                            .filter(name -> {
                                WorldMeta m = worldMetaMap.get(name);
                                return m == null || m.status == MapStatus.UNRELEASED;
                            })
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .sorted()
                            .toList();

                default:
                    return List.of();
            }
        }

        // ---- 第三级：枚举值补全 ----
        if (args.length == 3) {
            String prefix = args[2].toLowerCase(Locale.ROOT);

            switch (sub) {
                case "category":
                    // /world category <世界名> <PKW|SEGMENT|ONLYSPRINT|RANKUP>
                    return ParkourGameType.enumNames().stream()
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .toList();

                case "timing": {
                    // /world timing <世界名> <COUNTUP|COUNTDOWN>
                    // 上下文感知：若世界已是PKW，提示两个选项；若未知或非PKW也提示（由命令层校验）
                    return java.util.Arrays.stream(TimingMode.values())
                            .map(Enum::name)
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .toList();
                }

                case "status":
                    // /world status <世界名> <OPEN|CLOSED|UNRELEASED|EVENT|TESTING>
                    return java.util.Arrays.stream(MapStatus.values())
                            .filter(s -> s != MapStatus.UNKNOWN)
                            .map(s -> s.key)
                            .filter(key -> key.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .toList();

                case "slot":
                    // /world slot <世界名> <页码|reset> — 提示 reset 关键字
                    if ("reset".startsWith(prefix)) {
                        return List.of("reset");
                    }
                    return List.of();

                case "icon":
                    // /world icon <世界名> <材质名|reset>
                    return List.of("DIAMOND_BLOCK", "EMERALD_BLOCK", "NETHER_STAR",
                            "PLAYER_HEAD", "ENDER_PEARL", "FILLED_MAP",
                            "GLOBE_BANNER_PATTERN", "CREEPER_BANNER_PATTERN",
                            "FIELD_MASONED_BANNER_PATTERN", "GUSTER_BANNER_PATTERN",
                            "FLOWER_BANNER_PATTERN", "MOJANG_BANNER_PATTERN",
                            "EXPERIENCE_BOTTLE", "BOOK", "ENCHANTED_BOOK",
                            "GOLDEN_APPLE", "FIREWORK_ROCKET", "BEACON",
                            "reset").stream()
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .toList();

                default:
                    return List.of();
            }
        }

        // ---- /world displayname/typename/difficulty 第三级及以后无需补全（自由文本） ----
        return List.of();
    }

    // ==================== /menu Tab 补全 ====================

    private List<String> tabCompleteMenu(CommandSender sender, String[] args) {
        if (args.length == 0) return List.of();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return java.util.stream.Stream.of("skindebug")
                    .filter(sub -> sub.startsWith(prefix))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("skindebug")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .toList();
        }

        return List.of();
    }

    private static final class VoidBiomeProvider extends BiomeProvider {
        @Override
        public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
            return Biome.THE_VOID;
        }

        @Override
        public List<Biome> getBiomes(WorldInfo worldInfo) {
            return List.of(Biome.THE_VOID);
        }
    }
}
