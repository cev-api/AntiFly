package com.antifly.paper;

import java.util.EnumSet;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public final class AntiFlyListener implements Listener {
    private static final long LOG_COOLDOWN_MS = 500;
    private static final int NO_ROCKET_FLAT_TICKS_LIMIT = 12;
    private static final double NO_ROCKET_FLAT_DELTA_Y_MAX = 0.03;
    private static final double NO_ROCKET_FLAT_MIN_HORIZONTAL = 0.12;
    private static final int NO_ROCKET_UP_CONTROL_TICKS_LIMIT = 6;
    private static final double NO_ROCKET_UP_CONTROL_MIN_DELTA_Y = 0.06;
    private static final int FLUID_EXIT_UP_GRACE_TICKS = 12;
    private static final EnumSet<Material> COLLISION_SUPPORT = EnumSet.of(
        Material.SLIME_BLOCK, Material.HONEY_BLOCK, Material.COBWEB
    );
    private final AntiFlyPlugin plugin;

    public AntiFlyListener(AntiFlyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        AntiFlyPlugin.PlayerState state = plugin.getState(player);
        AntiFlyPlugin.Settings settings = plugin.getSettings();
        state.tick++;

        if (player.isDead()) {
            resetState(state, null);
            return;
        }

        updateFlightAuthorization(player, state);

        boolean inFluid = player.isInWater() || player.isInLava() || player.isSwimming();
        boolean inVehicle = player.isInsideVehicle();
        boolean clientOnGround = player.isOnGround();
        boolean serverOnGround = hasGroundSupport(player, to);
        boolean nearGround = isNearGround(player, to);

        state.lastClientOnGround = clientOnGround;
        state.lastServerOnGround = serverOnGround;
        if (inFluid) {
            state.lastInFluid = true;
            state.fluidExitGraceTicks = 0;
        } else if (state.lastInFluid) {
            state.fluidExitGraceTicks = FLUID_EXIT_UP_GRACE_TICKS;
            state.lastInFluid = false;
        } else if (state.fluidExitGraceTicks > 0) {
            state.fluidExitGraceTicks--;
        }

        if (!plugin.isAntiFlyEnabled() || !plugin.isWorldEnabled(player.getWorld().getName())) {
            resetAirFlags(state);
            resetElytraBuffers(state);
            updateSupport(state, serverOnGround, inFluid, to);
            state.lastPos = to.clone();
            state.wasGliding = false;
            return;
        }

        if (handleVehicleMovement(player, state, from, to, serverOnGround, inFluid)) {
            state.lastPos = to.clone();
            return;
        }

        if (isExempt(player)) {
            resetAirFlags(state);
            updateSupport(state, serverOnGround, inFluid, to);
            state.lastPos = to.clone();
            return;
        }

        if (player.isGliding()) {
            if (!handleElytraMovement(player, state, from, to, nearGround, inFluid, inVehicle)) {
                return;
            }
            if (plugin.isDebug(player)) {
                sendDebugActionBar(player, state, "ELYTRA", 0.0, 0.0, plugin.getSettings().elytraNoRocketSustainableHorizontal,
                    plugin.getSettings().elytraMaxNoRocketUp);
            }
            state.lastPos = to.clone();
            return;
        }

        if (serverOnGround || isSupportedByCollisionLikeBlock(to)) {
            handleGroundMovement(player, state, from, to);
        } else if (inFluid) {
            handleFluidMovement(player, state, from, to);
        } else {
            handleNormalAirMovement(player, state, from, to, nearGround);
        }

        state.wasGliding = false;
        state.lastPos = to.clone();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        AntiFlyPlugin.PlayerState state = plugin.getState(player);
        AntiFlyPlugin.Settings settings = plugin.getSettings();
        if (event.isGliding()) {
            state.wasGliding = true;
            state.glideTicks = 0;
            state.glideToggleGraceTicks = settings.elytraToggleGraceTicks;
            resetElytraWindows(state);
        } else {
            state.wasGliding = false;
            state.glideLandingGraceTicks = settings.elytraLandingGraceTicks;
            resetElytraBuffers(state);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFireworkUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!player.isGliding()) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.FIREWORK_ROCKET) {
            Material main = player.getInventory().getItemInMainHand().getType();
            Material off = player.getInventory().getItemInOffHand().getType();
            if (main != Material.FIREWORK_ROCKET && off != Material.FIREWORK_ROCKET) {
                return;
            }
        }
        AntiFlyPlugin.PlayerState state = plugin.getState(player);
        state.lastRocketTick = state.tick;
        state.lastRocketUseMs = System.currentTimeMillis();
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

    private void handleGroundMovement(Player player, AntiFlyPlugin.PlayerState state, Location from, Location to) {
        double horizontal = horizontalDistance(from, to);
        double maxAllowed = maxGroundSpeed(player);
        double groundTolerance = 0.03;
        if (plugin.isDebug(player)) {
            sendDebugActionBar(player, state, "GROUND", horizontal, to.getY() - from.getY(), maxAllowed, 0.0);
        }
        if (horizontal > (maxAllowed + groundTolerance)) {
            fail(player, state, "ground_speed", horizontal, maxAllowed);
            return;
        }
        updateSupport(state, true, false, to);
        resetAirFlags(state);
    }

    private void handleFluidMovement(Player player, AntiFlyPlugin.PlayerState state, Location from, Location to) {
        Vector vel = player.getVelocity();
        double horizontal = Math.max(horizontalDistance(from, to), Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ()));
        double deltaY = Math.max(to.getY() - from.getY(), vel.getY());
        if (plugin.isDebug(player)) {
            sendDebugActionBar(player, state, "FLUID", horizontal, deltaY, plugin.getSettings().waterMax, plugin.getSettings().waterVerticalMax);
        }
        if (horizontal > plugin.getSettings().waterMax) {
            fail(player, state, "water_speed", horizontal, plugin.getSettings().waterMax);
            return;
        }
        if (deltaY > plugin.getSettings().waterVerticalMax) {
            fail(player, state, "water_vertical", deltaY, plugin.getSettings().waterVerticalMax);
            return;
        }
        updateSupport(state, false, true, to);
        resetAirFlags(state);
    }

    private void handleNormalAirMovement(Player player, AntiFlyPlugin.PlayerState state, Location from, Location to, boolean nearGround) {
        AntiFlyPlugin.Settings settings = plugin.getSettings();
        Vector vel = player.getVelocity();

        state.airTicks++;
        if (state.flightRevokeGraceTicks > 0) {
            state.flightRevokeGraceTicks--;
        }

        double horizontal = Math.max(horizontalDistance(from, to), Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ()));
        // Use actual displacement for airborne vertical checks so stale velocity snapshots
        // do not turn normal cliff jumps/falls into false upward-flight violations.
        double deltaY = to.getY() - from.getY();
        boolean graceAir = state.airTicks <= settings.airGraceTicks || state.flightRevokeGraceTicks > 0;
        if (plugin.isDebug(player)) {
            sendDebugActionBar(player, state, "AIR", horizontal, deltaY, settings.maxAirHorizontal, settings.maxAirVertical);
        }

        if (player.isOnGround() && !state.lastServerOnGround) {
            // Client claims ground but server disagrees — only penalize if
            // the player is actually exceeding legitimate ground speeds.
            // This handles reduced-height blocks (beds, daylight detectors,
            // lecterns, enchanting tables, stonecutters, cakes, campfires,
            // brewing stands, hoppers, cauldrons, etc.) automatically.
            double maxGround = maxGroundSpeed(player) + 0.05;
            if (horizontal > maxGround || deltaY > 0.08) {
                state.groundSpoofTicks++;
                state.groundSpoofBuffer += 0.5;
            }
        } else {
            state.groundSpoofTicks = Math.max(0, state.groundSpoofTicks - 1);
            state.groundSpoofBuffer = decay(state.groundSpoofBuffer, settings.bufferDecay);
        }

        if (!graceAir && horizontal > settings.maxAirHorizontal) {
            state.airHorizontalBuffer += horizontal - settings.maxAirHorizontal;
        } else {
            state.airHorizontalBuffer = decay(state.airHorizontalBuffer, settings.bufferDecay);
        }

        if (!graceAir && deltaY > settings.maxAirVertical) {
            state.airVerticalBuffer += deltaY - settings.maxAirVertical;
        } else {
            state.airVerticalBuffer = decay(state.airVerticalBuffer, settings.bufferDecay);
        }

        boolean barelyMovingY = Math.abs(deltaY) <= settings.hoverDeltaY;
        boolean nearlyZeroVerticalVelocity = Math.abs(vel.getY()) <= Math.max(settings.hoverDeltaY * 4.0, 0.03);
        boolean barelyMovingXZ = horizontal <= settings.hoverHorizontal;
        if (state.airTicks > settings.hoverStartTicks
            && barelyMovingY
            && nearlyZeroVerticalVelocity
            && barelyMovingXZ
            && !nearGround) {
            state.hoverTicks++;
            state.hoverBuffer += 1.0;
        } else {
            state.hoverTicks = Math.max(0, state.hoverTicks - 1);
            state.hoverBuffer = decay(state.hoverBuffer, settings.bufferDecay);
        }

        if (settings.noFallDetectionEnabled) {
            boolean notFalling = deltaY >= -0.02;
            if (notFalling) {
                state.airNonFallTicks++;
            } else {
                state.airNonFallTicks = 0;
            }
            state.antiKickWindowTicks++;
            if (deltaY < 0) {
                state.airWindowDescent += -deltaY;
            }
            if (deltaY > 0) {
                state.airWindowAscent += deltaY;
            }
            if (state.antiKickWindowTicks >= settings.antiKickWindowTicks) {
                if (state.airWindowDescent < settings.antiKickMinDescent) {
                    state.antiKickBuffer += 1.0;
                } else {
                    state.antiKickBuffer = decay(state.antiKickBuffer, settings.bufferDecay);
                }
                state.antiKickWindowTicks = 0;
                state.airWindowDescent = 0.0;
                state.airWindowAscent = 0.0;
            }
        } else {
            state.airNonFallTicks = 0;
            state.antiKickBuffer = 0.0;
            state.antiKickWindowTicks = 0;
            state.airWindowDescent = 0.0;
            state.airWindowAscent = 0.0;
        }

        double effectiveHorizontalBuffer = state.airHorizontalBuffer + state.groundSpoofBuffer;
        if (effectiveHorizontalBuffer > settings.horizontalBufferLimit) {
            fail(player, state, "air_offset_horizontal", effectiveHorizontalBuffer, settings.horizontalBufferLimit);
            return;
        }
        if (state.airVerticalBuffer > settings.verticalBufferLimit) {
            fail(player, state, "air_offset_vertical", state.airVerticalBuffer, settings.verticalBufferLimit);
            return;
        }
        if (state.hoverBuffer > settings.hoverBufferLimit || state.hoverTicks > settings.hoverTicksLimit) {
            fail(player, state, "air_hover", state.hoverBuffer, settings.hoverBufferLimit);
            return;
        }
        if (settings.noFallDetectionEnabled && state.airNonFallTicks > settings.airNonFallTicksLimit) {
            fail(player, state, "air_nonfall", state.airNonFallTicks, settings.airNonFallTicksLimit);
            return;
        }
        if (settings.noFallDetectionEnabled && state.antiKickBuffer > 2.0) {
            fail(player, state, "air_antikick", state.antiKickBuffer, 2.0);
        }
    }

    private boolean handleVehicleMovement(Player player, AntiFlyPlugin.PlayerState state, Location from, Location to,
                                          boolean serverOnGround, boolean inFluid) {
        if (!player.isInsideVehicle()) {
            return false;
        }

        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return false;
        }

        if (state.airTicks == 0) {
            state.flightRevokeGraceTicks = 20;
        }

        if (serverOnGround || inFluid) {
            return true;
        }

        double deltaY = to.getY() - from.getY();
        double horizontal = horizontalDistance(from, to);
        boolean naturalFall = deltaY <= -0.04 && horizontal <= 0.4;

        if (naturalFall) {
            return true;
        }

        int grace = vehicle instanceof AbstractHorse ? 24 : (vehicle instanceof Boat ? 12 : 10);
        state.airTicks++;
        if (state.airTicks <= grace) {
            return true;
        }

        rubberBandVehicle(player, state, "vehicle_flight");
        return true;
    }

    private boolean handleElytraMovement(Player player, AntiFlyPlugin.PlayerState state, Location from, Location to,
                                         boolean nearGround, boolean inFluid, boolean inVehicle) {
        AntiFlyPlugin.Settings settings = plugin.getSettings();
        if (!settings.elytraEnabled || inFluid || inVehicle) {
            return true;
        }

        state.glideTicks++;
        double deltaY = to.getY() - from.getY();
        Vector vel = player.getVelocity();
        double horizontal = Math.max(horizontalDistance(from, to), Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ()));
        long nowMs = System.currentTimeMillis();
        long rocketAgeMs = nowMs - state.lastRocketUseMs;
        boolean recentRocket = rocketAgeMs <= (settings.elytraBoostGraceTicks * 50L);
        boolean postRocketBleed = !recentRocket && rocketAgeMs <= (settings.elytraBoostGraceTicks * 100L);
        boolean recentRiptide = player.isRiptiding();

        if (!hasElytraEquipped(player)) {
            failElytra(player, state, "elytra_no_item", 1.0, 0.0);
            return false;
        }

        double maxHorizontal = recentRocket ? settings.elytraMaxRocketHorizontal : settings.elytraNoRocketSustainableHorizontal;
        double maxUp = recentRocket ? settings.elytraMaxRocketUp : settings.elytraMaxNoRocketUp;
        if (plugin.isDebug(player)) {
            String mode = recentRocket ? "ELYTRA+ROCKET" : "ELYTRA";
            sendDebugActionBar(player, state, mode, horizontal, deltaY, maxHorizontal, maxUp);
        }
        // During firework grace, enforce only hard impossible caps.
        double rocketAbsurdHorizontal = settings.elytraMaxRocketHorizontal * 1.15;
        double rocketAbsurdUp = settings.elytraMaxRocketUp * 1.20;

        if (recentRocket) {
            if (horizontal > rocketAbsurdHorizontal) {
                failElytra(player, state, "elytra_rocket_speed", horizontal, rocketAbsurdHorizontal);
                return false;
            } else {
                state.elytraMovementBuffer = decay(state.elytraMovementBuffer, settings.bufferDecay * 1.5);
            }
        } else if (horizontal > maxHorizontal) {
            // Suspicion score, not distance accumulation.
            double excess = horizontal - maxHorizontal;
            double increment = Math.min(2.0, 0.6 + (excess * 0.35));
            state.elytraMovementBuffer += increment;
        } else {
            state.elytraMovementBuffer = decay(state.elytraMovementBuffer, postRocketBleed ? (settings.bufferDecay * 2.0) : settings.bufferDecay);
        }

        if (deltaY > maxUp) {
            if (recentRocket && deltaY <= rocketAbsurdUp) {
                // Legit rocket pull-up inside a relaxed grace envelope.
            } else {
                boolean hasRecentDiveEnergy = state.glideWindowDescent >= settings.elytraRequiredDescentForPullup;
                if (!recentRocket && !recentRiptide && !hasRecentDiveEnergy) {
                    state.elytraMovementBuffer += 1.0;
                } else if (recentRocket && deltaY > rocketAbsurdUp) {
                    failElytra(player, state, "elytra_rocket_climb", deltaY, rocketAbsurdUp);
                    return false;
                }
            }
        }

        if (!recentRocket && !recentRiptide && !nearGround && !postRocketBleed) {
            state.glideNoRocketWindowTicks++;
            state.glideWindowHorizontal += horizontal;
            if (deltaY < 0) {
                state.glideWindowDescent += -deltaY;
            }
            if (deltaY > 0) {
                state.glideWindowAscent += deltaY;
            }

            if (state.glideNoRocketWindowTicks >= settings.elytraNoRocketWindowTicks) {
                boolean highHorizontal = horizontal > settings.elytraNoRocketSustainableHorizontal;
                boolean tooLittleDescent = state.glideWindowDescent < settings.elytraNoRocketMinDescent;
                boolean tooMuchAscent = state.glideWindowAscent > settings.elytraNoRocketMaxAscent;
                if ((highHorizontal && tooLittleDescent) || tooMuchAscent) {
                    state.elytraMovementBuffer += 2.0;
                } else {
                    state.elytraMovementBuffer = decay(state.elytraMovementBuffer, settings.bufferDecay);
                }
                resetElytraWindows(state);
            }

            // Catch controlled cruise/hover without a valid energy source.
            boolean nearlyFlat = Math.abs(deltaY) <= NO_ROCKET_FLAT_DELTA_Y_MAX;
            boolean cruising = horizontal >= NO_ROCKET_FLAT_MIN_HORIZONTAL;
            if (nearlyFlat && cruising) {
                state.glideHoverTicks++;
                if (state.glideHoverTicks > NO_ROCKET_FLAT_TICKS_LIMIT) {
                    failElytra(player, state, "elytra_no_rocket_flat_cruise", horizontal, NO_ROCKET_FLAT_MIN_HORIZONTAL);
                    return false;
                }
            } else {
                state.glideHoverTicks = Math.max(0, state.glideHoverTicks - 1);
            }

            // Explicitly detect controlled upward Elytra motion without a valid energy source.
            boolean allowUpControlGrace = nearGround
                || state.glideToggleGraceTicks > 0
                || state.fluidExitGraceTicks > 0;
            if (!allowUpControlGrace && deltaY > NO_ROCKET_UP_CONTROL_MIN_DELTA_Y && horizontal > 0.02) {
                state.glideControlTicks++;
                if (state.glideControlTicks > NO_ROCKET_UP_CONTROL_TICKS_LIMIT) {
                    failElytra(player, state, "elytra_no_rocket_control_up", deltaY, NO_ROCKET_UP_CONTROL_MIN_DELTA_Y);
                    return false;
                }
            } else {
                state.glideControlTicks = Math.max(0, state.glideControlTicks - 1);
            }
        } else {
            state.glideHoverTicks = 0;
            state.glideControlTicks = 0;
        }

        if (!nearGround && !recentRocket && !inFluid) {
            if (horizontal <= settings.elytraStallHorizontalMax && Math.abs(deltaY) <= settings.elytraStallVerticalMax) {
                state.glideStallTicks++;
            } else {
                state.glideStallTicks = 0;
            }
            if (state.glideStallTicks > settings.elytraStallTicks) {
                failElytra(player, state, "elytra_stall", horizontal, settings.elytraStallHorizontalMax);
                return false;
            }
        }

        if (settings.elytraDurabilityCheckEnabled) {
            trackElytraDurability(player, state, settings);
            if (state.durabilitySuspicious
                && state.elytraNoDurabilityDropWindows >= settings.elytraDurabilitySuspicionLimit
                && (!settings.elytraRequireMovementSuspicionForDurabilityPunish
                    || state.elytraMovementBuffer > settings.elytraMovementBufferLimit / 2.0)) {
                failElytra(player, state, "elytra_durability_plus_motion", state.elytraMovementBuffer,
                    settings.elytraMovementBufferLimit / 2.0);
                return false;
            }
        }

        if (state.elytraMovementBuffer > settings.elytraMovementBufferLimit + 0.35) {
            failElytra(player, state, "elytra_motion", state.elytraMovementBuffer, settings.elytraMovementBufferLimit);
            return false;
        }

        updateSupport(state, false, false, to);
        return true;
    }

    private void trackElytraDurability(Player player, AntiFlyPlugin.PlayerState state, AntiFlyPlugin.Settings settings) {
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA) {
            state.durabilitySuspicious = true;
            return;
        }
        if (!(chest.getItemMeta() instanceof Damageable damageable)) {
            return;
        }

        int damage = damageable.getDamage();
        int unbreaking = chest.getEnchantmentLevel(Enchantment.UNBREAKING);
        int requiredWindow = (int) Math.ceil(settings.elytraDurabilityBaseWindowTicks
            * Math.max(1.0, 1.0 + unbreaking * settings.elytraDurabilityUnbreakingMultiplier));

        if (state.lastElytraDurability >= 0 && damage > state.lastElytraDurability) {
            state.lastElytraDurabilityDropTick = state.tick;
            state.elytraNoDurabilityDropWindows = 0;
            state.durabilitySuspicious = false;
        }

        state.elytraDurabilityTicks++;
        if (state.elytraDurabilityTicks > requiredWindow) {
            if (state.tick - state.lastElytraDurabilityDropTick > requiredWindow) {
                state.elytraNoDurabilityDropWindows++;
                state.durabilitySuspicious = true;
            }
            state.elytraDurabilityTicks = 0;
        }

        state.lastElytraDurability = damage;
    }

    private void updateFlightAuthorization(Player player, AntiFlyPlugin.PlayerState state) {
        boolean serverAllowed = player.getAllowFlight()
            || player.getGameMode() == GameMode.CREATIVE
            || player.getGameMode() == GameMode.SPECTATOR;
        if (serverAllowed != state.serverAllowedFlight) {
            state.serverAllowedFlight = serverAllowed;
            state.lastAllowFlightChangeMs = System.currentTimeMillis();
            if (!serverAllowed) {
                state.flightRevokeGraceTicks = 8;
            }
        }
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
        if (player.isRiptiding() || player.isClimbing()) {
            return true;
        }
        return player.hasPotionEffect(PotionEffectType.LEVITATION)
            || player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }

    private boolean hasGroundSupport(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        BoundingBox box = player.getBoundingBox();
        double minX = box.getMinX() + 0.03;
        double maxX = box.getMaxX() - 0.03;
        double minZ = box.getMinZ() + 0.03;
        double maxZ = box.getMaxZ() - 0.03;
        int y = (int) Math.floor(box.getMinY() - 0.03);
        return hasSolidSupportAtY(loc, minX, maxX, minZ, maxZ, y);
    }

    private boolean isNearGround(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        BoundingBox box = player.getBoundingBox();
        double minX = box.getMinX() + 0.03;
        double maxX = box.getMaxX() - 0.03;
        double minZ = box.getMinZ() + 0.03;
        double maxZ = box.getMaxZ() - 0.03;
        int y = (int) Math.floor(box.getMinY() - 0.30);
        return hasSolidSupportAtY(loc, minX, maxX, minZ, maxZ, y);
    }

    private boolean hasSolidSupportAtY(Location loc, double minX, double maxX, double minZ, double maxZ, int blockY) {
        Block b1 = loc.getWorld().getBlockAt((int) Math.floor(minX), blockY, (int) Math.floor(minZ));
        if (!b1.isPassable()) return true;
        Block b2 = loc.getWorld().getBlockAt((int) Math.floor(maxX), blockY, (int) Math.floor(minZ));
        if (!b2.isPassable()) return true;
        Block b3 = loc.getWorld().getBlockAt((int) Math.floor(minX), blockY, (int) Math.floor(maxZ));
        if (!b3.isPassable()) return true;
        Block b4 = loc.getWorld().getBlockAt((int) Math.floor(maxX), blockY, (int) Math.floor(maxZ));
        return !b4.isPassable();
    }

    private boolean isSupportedByCollisionLikeBlock(Location loc) {
        Block block = loc.getBlock().getRelative(0, -1, 0);
        return COLLISION_SUPPORT.contains(block.getType());
    }

    private double horizontalDistance(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double maxGroundSpeed(Player player) {
        double max = player.isInsideVehicle() ? plugin.getSettings().groundMountedMax : plugin.getSettings().groundWalkMax;
        if (player.isSprinting()) {
            max *= 1.3;
        }
        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) {
            max *= 1.0 + (0.2 * (speed.getAmplifier() + 1));
        }
        return max;
    }

    private void updateSupport(AntiFlyPlugin.PlayerState state, boolean onGround, boolean inFluid, Location loc) {
        if (onGround) {
            state.lastGround = loc.clone();
            state.lastSupport = loc.clone();
        } else if (inFluid) {
            state.lastSupport = loc.clone();
        }
    }

    private void resetAirFlags(AntiFlyPlugin.PlayerState state) {
        state.airTicks = 0;
        state.airNonFallTicks = 0;
        state.hoverTicks = 0;
        state.antiKickWindowTicks = 0;
        state.airWindowDescent = 0.0;
        state.airWindowAscent = 0.0;
        state.airHorizontalBuffer = 0.0;
        state.airVerticalBuffer = 0.0;
        state.hoverBuffer = 0.0;
        state.antiKickBuffer = 0.0;
        state.groundSpoofBuffer = 0.0;
    }

    private void resetElytraWindows(AntiFlyPlugin.PlayerState state) {
        state.glideNoRocketWindowTicks = 0;
        state.glideWindowHorizontal = 0.0;
        state.glideWindowDescent = 0.0;
        state.glideWindowAscent = 0.0;
    }

    private void resetElytraBuffers(AntiFlyPlugin.PlayerState state) {
        state.glideStallTicks = 0;
        state.glideHoverTicks = 0;
        state.glideControlTicks = 0;
        state.elytraMovementBuffer = 0.0;
        state.elytraBuffer = 0.0;
        state.lastGlideHorizontal = 0.0;
        state.peakGlideHorizontal = 0.0;
        state.durabilitySuspicious = false;
        state.elytraNoDurabilityDropWindows = 0;
        state.elytraDurabilityTicks = 0;
        resetElytraWindows(state);
    }

    private double decay(double value, double by) {
        return Math.max(0.0, value - by);
    }

    private void rubberBandVehicle(Player player, AntiFlyPlugin.PlayerState state, String reason) {
        if (player.getVehicle() != null) {
            player.getVehicle().setVelocity(new Vector(0, 0, 0));
        }
        fail(player, state, reason, 0.0, 0.0);
    }

    private void fail(Player player, AntiFlyPlugin.PlayerState state, String reason, double actual, double allowed) {
        if (isSetbackCoolingDown(state)) {
            return;
        }

        Location target = state.lastSupport != null ? state.lastSupport
            : state.lastGround != null ? state.lastGround
            : state.lastPos != null ? state.lastPos
            : player.getLocation();

        player.teleport(target);
        player.setVelocity(new Vector(0, 0, 0));
        logViolation(player, state, reason, actual, allowed, target);
        resetAirFlags(state);
    }

    private void failElytra(Player player, AntiFlyPlugin.PlayerState state, String reason, double actual, double allowed) {
        if (isSetbackCoolingDown(state)) {
            return;
        }
        player.setGliding(false);

        Location target = state.lastSupport != null ? state.lastSupport
            : state.lastGround != null ? state.lastGround
            : state.lastPos != null ? state.lastPos
            : player.getLocation();

        player.teleport(target);
        player.setVelocity(new Vector(0, 0, 0));
        logViolation(player, state, reason, actual, allowed, target);
        resetElytraBuffers(state);
        resetAirFlags(state);
    }

    private boolean isSetbackCoolingDown(AntiFlyPlugin.PlayerState state) {
        long now = System.currentTimeMillis();
        return now - state.lastSetbackAtMs < plugin.getSettings().setbackCooldownMs;
    }

    private void logViolation(Player player, AntiFlyPlugin.PlayerState state, String reason,
                              double actual, double allowed, Location location) {
        long now = System.currentTimeMillis();
        state.lastSetbackAtMs = now;
        if (now - state.lastRubberBandAtMs <= LOG_COOLDOWN_MS) {
            return;
        }
        state.lastRubberBandAtMs = now;

        int count = plugin.getAttemptTracker().record(player.getUniqueId());
        String line = String.format(
            "Blocked %s for %s (%s) count=%d tune=%s loc=%.2f,%.2f,%.2f actual=%.3f allowed=%.3f buffers[h=%.2f,v=%.2f,hover=%.2f,antikick=%.2f,ground=%.2f,elytra=%.2f]",
            reason,
            player.getName(),
            player.getUniqueId(),
            count,
            settingKeyForReason(reason),
            location.getX(), location.getY(), location.getZ(),
            actual, allowed,
            state.airHorizontalBuffer,
            state.airVerticalBuffer,
            state.hoverBuffer,
            state.antiKickBuffer,
            state.groundSpoofBuffer,
            state.elytraMovementBuffer
        );
        if (plugin.getSettings().alertMode == AntiFlyPlugin.AlertMode.CONSOLE
            || plugin.getSettings().alertMode == AntiFlyPlugin.AlertMode.BOTH) {
            plugin.getLogger().info(line);
        }
        if (plugin.getSettings().alertMode == AntiFlyPlugin.AlertMode.GAME
            || plugin.getSettings().alertMode == AntiFlyPlugin.AlertMode.BOTH) {
            broadcastViolationToOps(player, reason, settingKeyForReason(reason), actual, allowed);
        }
    }

    private String settingKeyForReason(String reason) {
        return switch (reason) {
            case "ground_speed" -> "groundWalkMax";
            case "water_speed" -> "waterMax";
            case "water_vertical" -> "waterVerticalMax";
            case "air_offset_horizontal" -> "horizontalBufferLimit/maxAirHorizontal";
            case "air_offset_vertical" -> "verticalBufferLimit/maxAirVertical";
            case "air_hover" -> "hoverBufferLimit";
            case "air_nonfall" -> "airNonFallTicksLimit";
            case "air_antikick" -> "antiKickWindowTicks/antiKickMinDescent";
            case "elytra_stall" -> "elytraStallTicks";
            case "elytra_rocket_speed" -> "elytraMaxRocketHorizontal";
            case "elytra_rocket_climb" -> "elytraMaxRocketUp";
            case "elytra_no_rocket_flat_cruise" -> "elytraNoRocketSustainableHorizontal";
            case "elytra_no_rocket_control_up" -> "elytraMaxNoRocketUp";
            case "elytra_motion", "elytra_durability_plus_motion" -> "elytraMovementBufferLimit";
            case "elytra_no_item" -> "elytraEnabled";
            default -> "-";
        };
    }

    private void broadcastViolationToOps(Player violator, String reason, String tune, double actual, double allowed) {
        String msg = ChatColor.RED + "[AntiFly] "
            + ChatColor.YELLOW + violator.getName()
            + ChatColor.GRAY + " blocked "
            + ChatColor.WHITE + reason
            + ChatColor.DARK_GRAY + " ("
            + ChatColor.AQUA + "tune=" + tune
            + ChatColor.DARK_GRAY + ", "
            + ChatColor.AQUA + String.format("actual=%.3f allowed=%.3f", actual, allowed)
            + ChatColor.DARK_GRAY + ")";
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.isOp() || viewer.hasPermission("antifly.alerts")) {
                viewer.sendMessage(msg);
            }
        }
    }

    private void resetState(AntiFlyPlugin.PlayerState state, Location loc) {
        resetAirFlags(state);
        resetElytraBuffers(state);
        state.serverAllowedFlight = false;
        state.lastAllowFlightChangeMs = 0;
        state.flightRevokeGraceTicks = 0;
        state.groundSpoofTicks = 0;
        state.lastServerOnGround = false;
        state.lastClientOnGround = false;
        if (loc != null) {
            state.lastGround = loc.clone();
            state.lastSupport = loc.clone();
            state.lastPos = loc.clone();
        }
    }

    private boolean hasElytraEquipped(Player player) {
        ItemStack chest = player.getInventory().getChestplate();
        return chest != null && chest.getType() == Material.ELYTRA;
    }

    private void sendDebugActionBar(Player player, AntiFlyPlugin.PlayerState state, String mode,
                                    double horizontal, double deltaY, double hLimit, double vLimit) {
        String text = String.format(
            "%s | h=%.3f/%.3f dy=%.3f/%.3f | buf[h=%.2f v=%.2f hov=%.2f ak=%.2f e=%.2f] | noFall=%s antiKick=%d/%d",
            mode,
            horizontal, hLimit,
            deltaY, vLimit,
            state.airHorizontalBuffer,
            state.airVerticalBuffer,
            state.hoverBuffer,
            state.antiKickBuffer,
            state.elytraMovementBuffer,
            plugin.getSettings().noFallDetectionEnabled,
            state.antiKickWindowTicks,
            plugin.getSettings().antiKickWindowTicks
        );
        player.sendActionBar(Component.text(text));
    }

}
