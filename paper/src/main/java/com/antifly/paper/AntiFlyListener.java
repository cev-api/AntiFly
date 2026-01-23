package com.antifly.paper;

import com.antifly.common.AntiFlyConstants;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public final class AntiFlyListener implements Listener {
    private static final long LOG_COOLDOWN_MS = 500;
    private final AntiFlyPlugin plugin;

    public AntiFlyListener(AntiFlyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (event.getTo() == null) {
            return;
        }

        AntiFlyPlugin.PlayerState state = plugin.getState(player);
        Location from = event.getFrom();
        Location to = event.getTo();

        if (player.isDead()) {
            resetState(state, null);
            return;
        }

        if (!plugin.isAntiFlyEnabled()) {
            boolean inFluid = player.isInWater() || player.isInLava() || player.isSwimming();
            boolean serverOnGround = hasGroundSupport(player, to);
            updateSupport(state, serverOnGround, inFluid, to);
            state.lastPos = to.clone();
            state.lastServerOnGround = serverOnGround;
            state.wasGliding = player.isGliding();
            return;
        }

        boolean inFluid = player.isInWater() || player.isInLava() || player.isSwimming();
        boolean inVehicle = player.isInsideVehicle();
        boolean clientOnGround = player.isOnGround();
        boolean serverOnGround = hasGroundSupport(player, to);
        boolean trustedGround = serverOnGround
            || (clientOnGround && hasGroundSupportLoose(player, to) && player.getVelocity().getY() <= 0.05);
        boolean isGliding = player.isGliding();
        if (!isGliding) {
            state.glideStallTicks = 0;
            state.glideSlowdownGraceTicks = 0;
            state.lastGlideHorizontal = 0.0;
            if (state.wasGliding) {
                state.glideGroundGraceTicks = AntiFlyConstants.GLIDE_GROUND_GRACE_TICKS;
            }
            if (state.glideGroundGraceTicks > 0) {
                state.glideGroundGraceTicks--;
            }
        }
        boolean inBoatWater = false;
        if (inVehicle && player.getVehicle() instanceof Boat boat) {
            Location boatLoc = boat.getLocation();
            inBoatWater = boat.isInWater()
                || boat.isOnGround()
                || boatLoc.getBlock().isLiquid()
                || boatLoc.clone().subtract(0, 1, 0).getBlock().isLiquid();
        }
        if (inBoatWater) {
            inFluid = true;
        }

        if (inVehicle && !state.wasInVehicle) {
            state.vehicleGraceTicks = 20;
        }
        state.wasInVehicle = inVehicle;

        if (inVehicle && state.vehicleGraceTicks > 0) {
            state.vehicleGraceTicks--;
            state.lastPos = to.clone();
            return;
        }

        if (inVehicle && !serverOnGround && !inFluid) {
            Location target = state.lastSupport != null ? state.lastSupport : to;
            rubberBandVehicle(player, state, target, "vehicle_flight");
            state.wasGliding = false;
            return;
        }

        if (isExempt(player)) {
            updateSupport(state, trustedGround, inFluid, to);
            state.lastPos = to.clone();
            state.lastServerOnGround = serverOnGround;
            state.wasGliding = isGliding;
            return;
        }

        if (isGliding) {
            if (!handleGlide(player, state, from, to, serverOnGround, inFluid, inVehicle)) {
                state.wasGliding = false;
                return;
            }
            state.airTicks++;
            state.lastPos = to.clone();
            state.lastServerOnGround = serverOnGround;
            state.wasGliding = true;
            return;
        }

        if (trustedGround) {
            state.groundSpoofTicks = 0;
            Vector vel = player.getVelocity();
            double deltaY = to.getY() - from.getY();
            double horizontal = horizontalDistance(from, to);
            double maxAllowed = maxGroundSpeed(player);
            if (deltaY > 0.02 || vel.getY() > 0.05) {
                maxAllowed *= 1.5;
            }
            if (state.glideGroundGraceTicks <= 0 && horizontal > maxAllowed) {
                Location target = state.lastGround != null ? state.lastGround : from;
                rubberBand(player, state, target, "ground_speed", horizontal, maxAllowed);
                state.wasGliding = false;
                return;
            }
            updateSupport(state, true, false, to);
        } else if (inFluid) {
            state.groundSpoofTicks = 0;
            Vector vel = player.getVelocity();
            double horizontal = Math.max(horizontalDistance(from, to), Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ()));
            double maxAllowed = maxWaterSpeed(player);
            double deltaY = Math.max(to.getY() - from.getY(), vel.getY());
            double maxUp = maxWaterVertical(player);
            if (horizontal > maxAllowed) {
                Location target = state.lastSupport != null ? state.lastSupport : from;
                rubberBand(player, state, target, "water_speed", horizontal, maxAllowed);
                state.wasGliding = false;
                return;
            }
            if (deltaY > maxUp) {
                Location target = state.lastSupport != null ? state.lastSupport : from;
                rubberBand(player, state, target, "water_vertical", deltaY, maxUp);
                state.wasGliding = false;
                return;
            }
            updateSupport(state, false, true, to);
        } else if (!inFluid) {
            Vector vel = player.getVelocity();
            state.airTicks++;
            boolean graceAir = state.airTicks <= 4;
            double horizontal = Math.max(horizontalDistance(from, to), Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ()));
            double maxAllowed = maxAirSpeed(player);
            if (!graceAir && horizontal > maxAllowed) {
                Location target = state.lastSupport != null ? state.lastSupport : from;
                rubberBand(player, state, target, "air_speed", horizontal, maxAllowed);
                state.wasGliding = false;
                return;
            }
            double deltaY = Math.max(to.getY() - from.getY(), vel.getY());
            double maxUp = maxAirVertical(player);
            if (!graceAir && deltaY > maxUp) {
                Location target = state.lastSupport != null ? state.lastSupport : from;
                rubberBand(player, state, target, "air_vertical", deltaY, maxUp);
                state.wasGliding = false;
                return;
            }
            state.groundSpoofTicks = 0;
            // Ignore mid-air jump detection; we only care about sustained flight.
            boolean hoveringStill = !serverOnGround
                && state.airTicks > AntiFlyConstants.MAX_AIR_TICKS
                && Math.abs(deltaY) <= AntiFlyConstants.HOVER_DELTA_Y_EPSILON;
            if (hoveringStill) {
                state.hoverTicks++;
                if (state.hoverTicks > AntiFlyConstants.HOVER_TICKS) {
                    Location target = state.lastSupport != null ? state.lastSupport : from;
                    rubberBand(player, state, target, "air_hover", deltaY, 0.0);
                    state.wasGliding = false;
                    return;
                }
            } else {
                state.hoverTicks = 0;
            }
            if (isVoidBelow(player, to) && !serverOnGround && !inFluid
                && deltaY >= AntiFlyConstants.AIR_DESCENT_EPSILON) {
                state.voidTicks++;
                if (state.voidTicks > AntiFlyConstants.VOID_FALL_TICKS) {
                    Location target = state.lastSupport != null ? state.lastSupport : player.getWorld().getSpawnLocation();
                    rubberBand(player, state, target, "void_fall", 0.0, 0.0);
                    state.wasGliding = false;
                    return;
                }
            } else {
                state.voidTicks = 0;
            }
            // No generic "flight" rubberband; rely on hover/elytra checks only.
        }

        state.lastPos = to.clone();
        state.lastServerOnGround = serverOnGround;
        state.wasGliding = false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getAttemptTracker().reset(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        resetState(plugin.getState(event.getEntity()), null);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        resetState(plugin.getState(event.getPlayer()), event.getRespawnLocation());
    }

    private boolean isExempt(Player player) {
        if (plugin.isExempt(player)) {
            return true;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return true;
        }
        if (player.getAllowFlight()) {
            return true;
        }
        if (player.isRiptiding()) {
            return true;
        }
        if (player.isClimbing()) {
            return true;
        }
        return player.hasPotionEffect(PotionEffectType.LEVITATION)
            || player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }

    private double horizontalDistance(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double maxGroundSpeed(Player player) {
        double max = plugin.getSettings().groundMax;
        if (player.isSprinting()) {
            max *= 1.3;
        }
        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) {
            max *= 1.0 + (0.2 * (speed.getAmplifier() + 1));
        }
        return max;
    }

    private double maxWaterSpeed(Player player) {
        double max = plugin.getSettings().waterMax;
        PotionEffect dolphins = player.getPotionEffect(PotionEffectType.DOLPHINS_GRACE);
        if (dolphins != null) {
            max *= 1.0 + (0.3 * (dolphins.getAmplifier() + 1));
        }
        ItemStack boots = player.getInventory().getBoots();
        if (boots != null) {
            int depthStrider = boots.getEnchantmentLevel(Enchantment.DEPTH_STRIDER);
            if (depthStrider > 0) {
                max *= 1.0 + (0.15 * depthStrider);
            }
        }
        return max;
    }

    private double maxWaterVertical(Player player) {
        double max = plugin.getSettings().waterVerticalMax;
        PotionEffect dolphins = player.getPotionEffect(PotionEffectType.DOLPHINS_GRACE);
        if (dolphins != null) {
            max *= 1.0 + (0.15 * (dolphins.getAmplifier() + 1));
        }
        return max;
    }

    private double maxAirSpeed(Player player) {
        double max = plugin.getSettings().airMax;
        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) {
            max *= 1.0 + (0.2 * (speed.getAmplifier() + 1));
        }
        return max;
    }

    private double maxAirVertical(Player player) {
        double max = plugin.getSettings().airVerticalMax;
        PotionEffect jump = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
        if (jump != null) {
            max *= 1.0 + (0.1 * (jump.getAmplifier() + 1));
        }
        return max;
    }

    private boolean handleGlide(Player player, AntiFlyPlugin.PlayerState state, Location from, Location to,
                                boolean serverOnGround, boolean inFluid, boolean inVehicle) {
        AntiFlyPlugin.Settings settings = plugin.getSettings();
        if (!settings.elytraChecksEnabled) {
            state.glideStallTicks = 0;
            state.glideSlowdownGraceTicks = 0;
            state.lastGlideHorizontal = 0.0;
            return true;
        }
        if (inFluid || inVehicle || !hasElytraEquipped(player)) {
            return true;
        }
        if (!state.wasGliding) {
            state.glideSlowdownGraceTicks = settings.elytraSlowdownGraceTicks;
            state.glideStallTicks = 0;
            state.lastGlideHorizontal = 0.0;
        }

        Vector vel = player.getVelocity();
        double moveDeltaY = to.getY() - from.getY();
        double horizontal = horizontalDistance(from, to);
        double horizontalSpeed = Math.max(horizontal, Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ()));

        if (horizontalSpeed > settings.elytraMaxHorizontal) {
            Location target = state.lastSupport != null ? state.lastSupport : from;
            rubberBand(player, state, target, "elytra_speed", horizontalSpeed, settings.elytraMaxHorizontal);
            player.setGliding(false);
            return false;
        }
        if (moveDeltaY > settings.elytraMaxUp) {
            Location target = state.lastSupport != null ? state.lastSupport : from;
            rubberBand(player, state, target, "elytra_up", moveDeltaY, settings.elytraMaxUp);
            player.setGliding(false);
            return false;
        }
        if (moveDeltaY < -settings.elytraMaxDown) {
            Location target = state.lastSupport != null ? state.lastSupport : from;
            rubberBand(player, state, target, "elytra_down", moveDeltaY, -settings.elytraMaxDown);
            player.setGliding(false);
            return false;
        }

        if (horizontal <= settings.elytraStallHorizontalMax
            && Math.abs(moveDeltaY) <= settings.elytraStallVerticalMax) {
            state.glideStallTicks++;
            if (state.glideStallTicks > settings.elytraStallTicks) {
                Location target = state.lastSupport != null ? state.lastSupport : from;
                rubberBand(player, state, target, "elytra_stall", horizontal, settings.elytraStallHorizontalMax);
                player.setGliding(false);
                return false;
            }
        } else {
            state.glideStallTicks = 0;
        }

        if (state.glideSlowdownGraceTicks > 0) {
            state.glideSlowdownGraceTicks--;
        } else if (!serverOnGround
            && state.lastGlideHorizontal > settings.elytraSlowdownMinSpeed
            && horizontalSpeed < state.lastGlideHorizontal * settings.elytraSlowdownMinScale) {
            Location target = state.lastSupport != null ? state.lastSupport : from;
            rubberBand(player, state, target, "elytra_slowdown", horizontalSpeed, state.lastGlideHorizontal);
            player.setGliding(false);
            return false;
        }
        state.lastGlideHorizontal = horizontalSpeed;
        return true;
    }

    private boolean hasElytraEquipped(Player player) {
        ItemStack chest = player.getInventory().getChestplate();
        return chest != null && chest.getType() == Material.ELYTRA;
    }

    private boolean isFalling(Location from, Location to, Player player) {
        if (to.getY() < from.getY()) {
            return true;
        }
        return player.getVelocity().getY() < -0.05;
    }

    private boolean isVoidBelow(Player player, Location loc) {
        double voidY = player.getWorld().getMinHeight() - AntiFlyConstants.VOID_Y_OFFSET;
        return loc.getY() < voidY;
    }

    private boolean hasGroundSupport(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        BoundingBox box = player.getBoundingBox();
        double minX = box.getMinX() + AntiFlyConstants.SUPPORT_EPSILON;
        double maxX = box.getMaxX() - AntiFlyConstants.SUPPORT_EPSILON;
        double minZ = box.getMinZ() + AntiFlyConstants.SUPPORT_EPSILON;
        double maxZ = box.getMaxZ() - AntiFlyConstants.SUPPORT_EPSILON;
        double y = box.getMinY() - AntiFlyConstants.SUPPORT_EPSILON;
        int blockY = (int) Math.floor(y);
        if (blockY < loc.getWorld().getMinHeight()) {
            return false;
        }
        Block b1 = loc.getWorld().getBlockAt((int) Math.floor(minX), blockY, (int) Math.floor(minZ));
        if (!b1.isPassable()) {
            return true;
        }
        Block b2 = loc.getWorld().getBlockAt((int) Math.floor(maxX), blockY, (int) Math.floor(minZ));
        if (!b2.isPassable()) {
            return true;
        }
        Block b3 = loc.getWorld().getBlockAt((int) Math.floor(minX), blockY, (int) Math.floor(maxZ));
        if (!b3.isPassable()) {
            return true;
        }
        Block b4 = loc.getWorld().getBlockAt((int) Math.floor(maxX), blockY, (int) Math.floor(maxZ));
        return !b4.isPassable();
    }

    private boolean hasGroundSupportLoose(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        BoundingBox box = player.getBoundingBox();
        double minX = box.getMinX() + AntiFlyConstants.SUPPORT_EPSILON;
        double maxX = box.getMaxX() - AntiFlyConstants.SUPPORT_EPSILON;
        double minZ = box.getMinZ() + AntiFlyConstants.SUPPORT_EPSILON;
        double maxZ = box.getMaxZ() - AntiFlyConstants.SUPPORT_EPSILON;
        double y = box.getMinY() - AntiFlyConstants.SUPPORT_LOOSE_EPSILON;
        int blockY = (int) Math.floor(y);
        if (blockY < loc.getWorld().getMinHeight()) {
            return false;
        }
        Block b1 = loc.getWorld().getBlockAt((int) Math.floor(minX), blockY, (int) Math.floor(minZ));
        if (!b1.isPassable()) {
            return true;
        }
        Block b2 = loc.getWorld().getBlockAt((int) Math.floor(maxX), blockY, (int) Math.floor(minZ));
        if (!b2.isPassable()) {
            return true;
        }
        Block b3 = loc.getWorld().getBlockAt((int) Math.floor(minX), blockY, (int) Math.floor(maxZ));
        if (!b3.isPassable()) {
            return true;
        }
        Block b4 = loc.getWorld().getBlockAt((int) Math.floor(maxX), blockY, (int) Math.floor(maxZ));
        return !b4.isPassable();
    }

    private void rubberBandVehicle(Player player, AntiFlyPlugin.PlayerState state, Location target, String reason) {
        if (player.getVehicle() != null) {
            player.getVehicle().setVelocity(new Vector(0, 0, 0));
            player.getVehicle().teleport(target);
        }
        rubberBand(player, state, target, reason, 0.0, 0.0);
    }

    private void resetState(AntiFlyPlugin.PlayerState state, Location loc) {
        state.airTicks = 0;
        state.hoverTicks = 0;
        state.voidTicks = 0;
        state.glideStallTicks = 0;
        state.groundSpoofTicks = 0;
        state.glideGroundGraceTicks = 0;
        state.glideSlowdownGraceTicks = 0;
        state.wasGliding = false;
        state.lastServerOnGround = false;
        state.lastGlideHorizontal = 0.0;
        state.vehicleGraceTicks = 0;
        state.wasInVehicle = false;
        if (loc != null) {
            state.lastGround = loc.clone();
            state.lastSupport = loc.clone();
            state.lastPos = loc.clone();
            state.lastServerOnGround = true;
        }
    }

    private void updateSupport(AntiFlyPlugin.PlayerState state, boolean onGround, boolean inFluid, Location loc) {
        if (onGround) {
            state.lastGround = loc.clone();
            state.lastSupport = loc.clone();
            state.airTicks = 0;
            state.hoverTicks = 0;
            state.voidTicks = 0;
            state.groundSpoofTicks = 0;
            state.lastServerOnGround = true;
        } else if (inFluid) {
            state.lastSupport = loc.clone();
            state.airTicks = 0;
            state.hoverTicks = 0;
            state.voidTicks = 0;
            state.groundSpoofTicks = 0;
            state.lastServerOnGround = false;
        }
    }

    private void rubberBand(Player player, AntiFlyPlugin.PlayerState state, Location target, String reason,
                            double actual, double allowed) {
        long now = System.currentTimeMillis();
        if (now - state.lastRubberBandAtMs > LOG_COOLDOWN_MS) {
            int count = plugin.getAttemptTracker().record(player.getUniqueId());
            plugin.getLogger().info(String.format(
                "Blocked %s for %s (%s) count=%d loc=%.2f,%.2f,%.2f actual=%.3f allowed=%.3f",
                reason,
                player.getName(),
                player.getUniqueId(),
                count,
                target.getX(), target.getY(), target.getZ(),
                actual,
                allowed
            ));
            state.lastRubberBandAtMs = now;
        }

        player.teleport(target);
        player.setVelocity(new Vector(0, 0, 0));
        state.airTicks = 0;
        state.lastPos = target.clone();
        state.lastServerOnGround = hasGroundSupport(player, target);
    }
}
