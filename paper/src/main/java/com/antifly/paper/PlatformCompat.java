package com.antifly.paper;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

// AntiFly ships a single jar for the whole Bukkit family (Bukkit, Spigot, Paper,
// Purpur, Folia), so every API that only some of them expose is probed once
// here, reached reflectively, and replaced by a plain Bukkit equivalent when the
// running server does not have it.
final class PlatformCompat {
    private static final Method GET_GLOBAL_REGION_SCHEDULER = probeMethod(Bukkit.class, "getGlobalRegionScheduler");
    private static final Method GLOBAL_SCHEDULER_RUN =
        probeMethod(returnTypeOf(GET_GLOBAL_REGION_SCHEDULER), "run", Plugin.class, Consumer.class);
    private static final Method GLOBAL_SCHEDULER_RUN_AT_FIXED_RATE = probeMethod(
        returnTypeOf(GET_GLOBAL_REGION_SCHEDULER), "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
    private static final Method PLAYER_GET_SCHEDULER = probeMethod(Player.class, "getScheduler");
    private static final Method ENTITY_SCHEDULER_RUN = probeMethod(
        returnTypeOf(PLAYER_GET_SCHEDULER), "run", Plugin.class, Consumer.class, Runnable.class);
    private static final Method IS_IN_LAVA = probeMethod(Entity.class, "isInLava");
    private static final Method COMPONENT_TEXT = probeAdventureMethod("text", String.class);
    private static final Method SEND_ACTION_BAR = probeAdventureActionBar();
    private static final boolean FOLIA = probeClass("io.papermc.paper.threadedregions.RegionizedServer");

    private PlatformCompat() {
    }

    static void runGlobal(Plugin plugin, Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (GLOBAL_SCHEDULER_RUN != null) {
            try {
                GLOBAL_SCHEDULER_RUN.invoke(GET_GLOBAL_REGION_SCHEDULER.invoke(null), plugin, consumerOf(task));
                return;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the classic scheduler below.
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    static void runGlobalAtFixedRate(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (GLOBAL_SCHEDULER_RUN_AT_FIXED_RATE != null) {
            try {
                GLOBAL_SCHEDULER_RUN_AT_FIXED_RATE.invoke(GET_GLOBAL_REGION_SCHEDULER.invoke(null), plugin,
                    consumerOf(task), delayTicks, periodTicks);
                return;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the classic scheduler below.
            }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    // Folia only allows touching an entity from the thread that owns it. Every
    // other server runs the task inline exactly as before.
    static void runForPlayer(Plugin plugin, Player player, Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (FOLIA && ENTITY_SCHEDULER_RUN != null) {
            try {
                ENTITY_SCHEDULER_RUN.invoke(PLAYER_GET_SCHEDULER.invoke(player), plugin, consumerOf(task), (Runnable) null);
                return;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the main-thread path below.
            }
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            runGlobal(plugin, task);
        }
    }

    static void runForSender(Plugin plugin, CommandSender sender, Runnable task) {
        if (sender instanceof Player player) {
            runForPlayer(plugin, player, task);
        } else {
            runGlobal(plugin, task);
        }
    }

    // Paper, Purpur and Folia route this through Adventure. Servers without that
    // API simply show no debug action bar.
    static void sendActionBar(Player player, String legacyText) {
        if (SEND_ACTION_BAR == null || COMPONENT_TEXT == null) {
            return;
        }
        try {
            SEND_ACTION_BAR.invoke(player, COMPONENT_TEXT.invoke(null, legacyText));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Debug output only, never worth surfacing to the server owner.
        }
    }

    // Paper implements the bounding-box check; on Spigot the block at the
    // entity's feet is the best available probe.
    static boolean isInLava(Entity entity) {
        if (IS_IN_LAVA != null) {
            try {
                return Boolean.TRUE.equals(IS_IN_LAVA.invoke(entity));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the block probe below.
            }
        }
        return entity.getLocation().getBlock().getType() == Material.LAVA;
    }

    private static Consumer<Object> consumerOf(Runnable task) {
        return scheduledTask -> task.run();
    }

    private static Class<?> returnTypeOf(Method method) {
        return method == null ? null : method.getReturnType();
    }

    private static boolean probeClass(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static Method probeMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException | LinkageError e) {
            return null;
        }
    }

    private static Method probeAdventureMethod(String name, Class<?>... parameterTypes) {
        try {
            return probeMethod(Class.forName("net.kyori.adventure.text.Component"), name, parameterTypes);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    // Resolved by name so the Adventure classes are never linked on a server
    // that does not ship them.
    private static Method probeAdventureActionBar() {
        try {
            Class<?> component = Class.forName("net.kyori.adventure.text.Component");
            return probeMethod(Player.class, "sendActionBar", component);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
