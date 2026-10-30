package club.sitmc.sitParkourLobby.inventory;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.logging.Logger;

/**
 * 按世界独立背包的事件监听器。
 *
 * <h3>PlayerChangedWorldEvent (LOWEST)</h3>
 * <ol>
 *   <li>保存离开世界的背包。</li>
 *   <li>加载进入世界的背包（无记录则清空，干净进入）。</li>
 * </ol>
 *
 * <h3>PlayerJoinEvent</h3>
 * <p>加载玩家当前世界的背包。若无记录（老玩家首次上线或新世界），
 * 将当前背包保存为该世界初始记录，<b>不清空</b>。</p>
 *
 * <h3>PlayerQuitEvent</h3>
 * <p>保存玩家当前世界的背包。</p>
 */
public class InventoryListener implements Listener {

    private final InventoryManager manager;
    private final Logger logger;
    private final boolean diagnosticLog = true;

    public InventoryListener(InventoryManager manager, Logger logger) {
        this.manager = manager;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        String fromWorld = event.getFrom().getName();
        String toWorld = player.getWorld().getName();

        if (fromWorld.equals(toWorld)) {
            return;
        }

        if (diagnosticLog) {
            logger.info("[InvSwitch] " + player.getName() + " 切换世界: \"" + fromWorld + "\" → \"" + toWorld + "\"");
        }

        // 1. 保存离开世界的背包
        manager.saveInventory(player, fromWorld);

        // 2. 加载进入世界的背包
        if (manager.loadInventory(player, toWorld)) {
            if (diagnosticLog) {
                logger.info("[InvSwitch] " + player.getName() + " → \"" + toWorld + "\" 背包已还原（命中记录）");
            }
            return;
        }

        // 3. 无记录 → 清空（干净进入）
        if (diagnosticLog) {
            logger.info("[InvSwitch] " + player.getName() + " → \"" + toWorld + "\" 无记录，清空空背包");
        }
        manager.clearInventory(player);
        manager.saveInventory(player, toWorld);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String worldName = player.getWorld().getName();

        if (manager.loadInventory(player, worldName)) {
            if (diagnosticLog) {
                logger.info("[InvJoin] " + player.getName() + " 加入，世界 \"" + worldName + "\" 背包已还原");
            }
            return;
        }

        // 无记录 → 保存当前背包为初始记录，不清空（保护老玩家）
        if (diagnosticLog) {
            logger.info("[InvJoin] " + player.getName() + " 加入，世界 \"" + worldName
                    + "\" 无记录，保存当前背包为初始记录（不清空）");
        }
        manager.saveInventory(player, worldName);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String worldName = player.getWorld().getName();
        manager.saveInventory(player, worldName);
    }
}
