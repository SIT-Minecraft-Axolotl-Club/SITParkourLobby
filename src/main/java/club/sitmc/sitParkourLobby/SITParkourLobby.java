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

import club.sitmc.sitParkourLobby.event.ManagedWorldsLoadedEvent;
import club.sitmc.sitParkourLobby.inventory.InventoryListener;
import club.sitmc.sitParkourLobby.inventory.InventoryManager;
import java.io.File;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.HashSet;
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
    private YamlConfiguration dataConfig;
    private File dataFile;
    private int scoreboardTaskId = -1;
    private int settingsTaskId = -1;
    private InventoryManager inventoryManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadDataFile();
        loadAllowedMaps();
        loadManagedWorlds();

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

        if (getCommand("map") != null) {
            getCommand("map").setTabCompleter(this);
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        // 按世界独立背包
        inventoryManager = new InventoryManager(getDataFolder(), getLogger());
        Bukkit.getPluginManager().registerEvents(new InventoryListener(inventoryManager, getLogger()), this);

        Bukkit.setDefaultGameMode(GameMode.ADVENTURE);
        for (World world : Bukkit.getWorlds()) {
            applyWorldSettings(world);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            loadPlayerStats(player);
            setupScoreboard(player);
            applyPlayerEntryRules(player);
            lastOnGround.put(player.getUniqueId(), player.isOnGround());
        }

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
        if (args.length != 1) {
            return List.of();
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
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

        if (name.equals("world")) {
            return tabCompleteWorld(sender, args);
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

        // 所有受管世界加载完成，广播事件通知其他插件（如 SITSegment）
        getLogger().info("所有受管世界加载完成，广播 ManagedWorldsLoadedEvent (受管世界数: " + managedWorlds.size() + ")");
        Bukkit.getPluginManager().callEvent(new ManagedWorldsLoadedEvent(List.copyOf(managedWorlds)));
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
            send(sender, "用法: /world <import|add|remove|tp|list> [世界名]");
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
            default:
                send(sender, "未知子命令: " + sub + "。可用: import, add, remove, tp, list");
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
        send(sender, "已导入并纳入受管列表: " + worldName);
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
        send(sender, "已创建虚空世界并纳入受管列表: " + worldName);
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
        send(sender, "已卸载并从受管列表移除: " + worldName + " (世界文件夹已保留)");
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
        send(sender, "已传送到世界: " + targetWorld.getName());
        return true;
    }

    private boolean handleWorldList(CommandSender sender) {
        if (managedWorlds.isEmpty()) {
            send(sender, "受管世界列表为空。");
            return true;
        }

        List<String> list = managedWorlds.stream().sorted().map(worldName -> {
            boolean loaded = Bukkit.getWorld(worldName) != null;
            return (loaded ? "&a" : "&c") + worldName + (loaded ? "&f(已加载)" : "&f(未加载)");
        }).toList();

        send(sender, "受管世界(" + managedWorlds.size() + "): " + String.join("&7, ", list));
        return true;
    }

    // ==================== /world Tab 补全 ====================

    private List<String> tabCompleteWorld(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sitpk.world")) {
            return List.of();
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("import", "add", "remove", "tp", "list").stream()
                    .filter(sub -> sub.startsWith(prefix))
                    .toList();
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String prefix = args[1].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "import":
                    // 建议存在文件夹但未在受管列表中的世界
                    File container = Bukkit.getWorldContainer();
                    File[] dirs = container.listFiles(File::isDirectory);
                    if (dirs == null) {
                        return List.of();
                    }
                    return java.util.Arrays.stream(dirs)
                            .map(File::getName)
                            .filter(name -> !managedWorlds.contains(name))
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .sorted()
                            .toList();

                case "remove":
                    // 建议受管列表中的世界
                    return managedWorlds.stream()
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .sorted()
                            .toList();

                case "tp":
                    // 建议已加载的世界
                    return Bukkit.getWorlds().stream()
                            .map(World::getName)
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .sorted()
                            .toList();

                default:
                    return List.of();
            }
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
