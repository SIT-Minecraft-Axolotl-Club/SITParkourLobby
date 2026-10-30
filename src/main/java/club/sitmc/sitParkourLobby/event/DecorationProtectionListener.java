package club.sitmc.sitParkourLobby.event;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.GameMode;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Set;

/**
 * 跑酷大厅装饰物保护监听器。
 *
 * <p>限制大厅中冒险模式玩家对展示框、画、盔甲架、花盆和龙蛋的操作与破坏，
 * 不处理非玩家造成的破坏或掉落。</p>
 *
 * <p>方块类装饰物（花盆、种植花盆、龙蛋）的左键攻击与右键使用都会被拦截：
 * 龙蛋没有独立的破坏/交互事件，原版在左键攻击时就会触发传送，
 * 因此只拦右键会留下左键可交互的缺口。</p>
 */
public final class DecorationProtectionListener implements Listener {

    private final Set<String> managedWorldNames;

    /**
     * 使用插件当前的受管世界集合。集合由主插件维护，因此导入或移除世界后
     * 保护范围会随受管列表即时更新，无需重复注册监听器。
     */
    public DecorationProtectionListener(Set<String> managedWorldNames) {
        this.managedWorldNames = managedWorldNames;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        if (isProtectedWorld(event.getEntity().getWorld())
                && isProtectedEntity(event.getEntity())
                && isAdventurePlayerCause(event.getRemover())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (isProtectedWorld(event.getEntity().getWorld())
                && isProtectedEntity(event.getEntity())
                && isAdventurePlayerCause(event.getDamager())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (isAdventurePlayerInWorld(event.getPlayer(), event.getRightClicked().getWorld())
                && isProtectedEntity(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (isAdventurePlayerInWorld(event.getPlayer(), event.getRightClicked().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isAdventurePlayerInWorld(event.getPlayer(), event.getBlock().getWorld())
                && isProtectedBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * 拦截方块类装饰物的左右键点击。
     *
     * <p>左键（{@link Action#LEFT_CLICK_BLOCK}）对龙蛋会直接触发原版传送，
     * 对花盆则进入破坏流程，因此必须与右键一并拦截。</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (!isAdventurePlayerInWorld(event.getPlayer(),
                clickedBlock == null ? null : clickedBlock.getWorld())
                || !isProtectedBlock(clickedBlock)) {
            return;
        }

        event.setCancelled(true);
    }

    private static boolean isProtectedEntity(Entity entity) {
        return entity != null && switch (entity.getType()) {
            case ITEM_FRAME, GLOW_ITEM_FRAME, PAINTING, ARMOR_STAND -> true;
            default -> false;
        };
    }

    private boolean isAdventurePlayerCause(Entity source) {
        if (source instanceof Player player) {
            return isAdventurePlayer(player);
        }
        if (source instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return isAdventurePlayer(player);
        }
        return false;
    }

    private boolean isAdventurePlayer(Player player) {
        return isAdventurePlayerInWorld(player, player.getWorld());
    }

    private boolean isAdventurePlayerInWorld(Player player, org.bukkit.World targetWorld) {
        return player != null
                && player.getGameMode() == GameMode.ADVENTURE
                && isProtectedWorld(player.getWorld())
                && isProtectedWorld(targetWorld);
    }

    private boolean isProtectedWorld(org.bukkit.World world) {
        return world != null && managedWorldNames.stream()
                .anyMatch(worldName -> worldName != null && worldName.equalsIgnoreCase(world.getName()));
    }

    private static boolean isProtectedBlock(Block block) {
        if (block == null) {
            return false;
        }

        Material material = block.getType();
        String materialName = material.name();
        return material == Material.DRAGON_EGG
                || material == Material.FLOWER_POT
                || materialName.startsWith("POTTED_");
    }
}
