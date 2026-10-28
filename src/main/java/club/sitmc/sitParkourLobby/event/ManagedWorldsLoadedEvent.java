package club.sitmc.sitParkourLobby.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 当 SITParkourLobby 完成所有受管世界的加载后广播此事件。
 *
 * <p>其他插件（如 SITSegment）可监听此事件，在确认世界已全部就绪后
 * 再读取依赖特定世界的配置（如反序列化 Location 等），
 * 避免因世界未加载而导致的 "unknown world" 错误。</p>
 *
 * <h3>其他插件监听方式</h3>
 * <ol>
 *   <li>将 SITParkourLobby 作为 compileOnly 依赖引入。</li>
 *   <li>在监听器中声明标准 Bukkit {@code @EventHandler}:</li>
 * </ol>
 * <pre>{@code
 * import club.sitmc.sitParkourLobby.event.ManagedWorldsLoadedEvent;
 * import org.bukkit.event.EventHandler;
 * import org.bukkit.event.Listener;
 *
 * public class MyListener implements Listener {
 *
 *     @EventHandler
 *     public void onManagedWorldsLoaded(ManagedWorldsLoadedEvent event) {
 *         List<String> worldNames = event.getWorldNames();
 *         getLogger().info("受管世界已全部加载: " + worldNames);
 *         // 在世界就绪后执行依赖世界的初始化逻辑...
 *     }
 * }
 * }</pre>
 */
public class ManagedWorldsLoadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final List<String> worldNames;

    /**
     * @param worldNames 所有受管世界的名称列表（不可变副本）
     */
    public ManagedWorldsLoadedEvent(List<String> worldNames) {
        this.worldNames = List.copyOf(worldNames);
    }

    /**
     * 获取所有受管世界的名称列表。
     * 监听方可据此判断所需世界是否已在列表中。
     *
     * @return 受管世界名称的不可变列表
     */
    public List<String> getWorldNames() {
        return worldNames;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    @SuppressWarnings("unused")
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
