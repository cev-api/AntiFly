package com.antifly.paper;

import com.antifly.common.AntiFlyConstants;
import java.util.EnumSet;
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
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public final class AntiFlyListener implements Listener {
    private static final long LOG_COOLDOWN_MS = 500;
    private static final int NO_ROCKET_FLAT_TICKS_LIMIT = 20;
    private static final double NO_ROCKET_FLAT_DELTA_Y_MAX = 0.03;
    private static final double NO_ROCKET_FLAT_MIN_HORIZONTAL = 0.45;
    private static final int NO_ROCKET_UP_CONTROL_TICKS_LIMIT = 6;
    private static final double NO_ROCKET_UP_CONTROL_MIN_DELTA_Y = 1.0;
    private static final int FLUID_EXIT_UP_GRACE_TICKS = 12;
    private static final double ELYTRA_HOVER_MAX_BPS = 2.0;
    private static final double SUPPORT_ENTITY_DEPTH = 0.5;
    private static final double SUPPORT_ENTITY_SLACK = 0.05;
    private static final EnumSet<Material> COLLISION_SUPPORT = EnumSet.of(
        Material.SLIME_BLOCK, Material.HONEY_BLOCK, Material.COBWEB
    );
    private final AntiFlyPlugin plugin;

    public AntiFlyListener(AntiFlyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerCommandSend(PlayerCommandSendEvent event) {
        if (!event.getPlayer().isOp()) {
            event.getCommands().remove("antifly");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoidMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        AntiFlyPlugin.PlayerState state = plugin.getState(event.getPlayer());
        state.voidRedirectedMove = redirectVoidMove(event, state);
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

        if (state.voidRedirectedMove) {
            state.voidRedirectedMove = false;
            state.teleportGraceTicks = 20;
            rebaselineTeleport(state, to, hasTeleportSupport(player, to));
            return;
        }

        if (player.isDead()) {
            resetState(state, null);
            return;
        }

        // PlayerTeleportEvent establishes grace for commands, pearls, chorus
        // fruit and portals. A large ordinary move packet with no matching
        // server velocity or teleport event is an unsupported position jump.
        if (state.teleportGraceTicks > 0) {
            state.teleportGraceTicks--;
        }
        if (from == null || from.getWorld() == null || to.getWorld() == null
            || !from.getWorld().equals(to.getWorld())) {
            rebaselineTeleport(state, to, to.getWorld() != null && hasTeleportSupport(player, to));
            return;
        }
        double distSq = from.distanceSquared(to);
        // Hunger Mode replaces the movement blocks with a hunger/health penalty.
        // A teleport-sized jump must not be rubber-banded there, otherwise this
        // setback fires first every tick and the hunger sampler below never runs
        // - the player just gets yanked back and alerted instead of charged.
        boolean hungerPenaltyActive = plugin.isHungerModeEnabled()
            && plugin.isWorldEnabled(player.getWorld().getName());
        if (distSq > 36.0 && state.teleportGraceTicks <= 0
            && state.impulseGraceTicks <= 0 && !player.isGliding()
            && !hungerPenaltyActive && !isExempt(player)) {
            Vector vel = player.getVelocity();
            if (vel.lengthSquared() < distSq * 0.25) {
                fail(player, state, "air_teleport", Math.sqrt(distSq), 6.0);
                return;
            }
        }

        updateFlightAuthorization(player, state);

        detectExternalImpulse(player, state, settings);

        boolean inFluid = player.isInWater() || PlatformCompat.isInLava(player) || player.isSwimming();
        boolean inVehicle = player.isInsideVehicle();
        boolean inBoatWater = inVehicle && player.getVehicle() instanceof Boat boat && isBoatInFluid(boat);
        boolean boatOnSupport = inVehicle && player.getVehicle() instanceof Boat boat && hasBoatGroundSupport(boat);
        boolean clientOnGround = player.isOnGround();
        boolean serverOnGround = hasGroundSupport(player, to);
        boolean nearGround = isNearGround(player, to);
        boolean wasServerOnGround = state.lastServerOnGround;

        state.lastClientOnGround = clientOnGround;
        state.lastServerOnGround = serverOnGround;
        if (serverOnGround && !wasServerOnGround && state.wasGliding) {
            state.glideLandingGraceTicks = Math.max(state.glideLandingGraceTicks, settings.elytraLandingGraceTicks);
        }
        if (state.glideLandingGraceTicks > 0) {
            state.glideLandingGraceTicks--;
        }
        if (inFluid) {
            state.lastInFluid = true;
            state.fluidExitGraceTicks = 0;
        } else if (state.lastInFluid) {
            state.fluidExitGraceTicks = FLUID_EXIT_UP_GRACE_TICKS;
            state.lastInFluid = false;
        } else if (state.fluidExitGraceTicks > 0) {
            state.fluidExitGraceTicks--;
        }

        // Wind charges, TNT, mace smashes and mob knockback are not the
        // player's doing. While the impulse is still carrying them, treat the
        // movement exactly like a legitimate exemption instead of flagging
        // speed the client never asked for.
        if (state.impulseGraceTicks > 0) {
            state.impulseGraceTicks--;
            // An external launch is legitimate propulsion, so it must not feed
            // the no-rocket altitude accumulator.
            state.elytraNetAltitude = 0.0;
            state.glideSustainedClimbTicks = 0;
            resetAirFlags(state);
            resetElytraBuffers(state);
            updateSupport(state, serverOnGround, inFluid, to);
            state.lastPos = to.clone();
            state.wasGliding = player.isGliding();
            return;
        }

        if (plugin.isHungerModeEnabled() && plugin.isWorldEnabled(player.getWorld().getName())) {
            // Accumulate accepted PlayerMoveEvent displacement. This is the
            // server's authoritative movement path; the periodic sampler below
            // remains responsible for stationary hovering.
            boolean vehicleSupported = inVehicle && (inBoatWater || boatOnSupport
                || (!(player.getVehicle() instanceof Boat) && player.getVehicle().isOnGround()));
            boolean movementUnsupported = !serverOnGround && !isSupportedByCollisionLikeBlock(to)
                && !inFluid && !vehicleSupported;
            if (player.isGliding() && !isExempt(player)) {
                long tick = to.getWorld().getGameTime();
                if (tick != state.lastHungerElytraPhysicsTick) {
                    Location previous = state.lastHungerElytraPhysicsSample;
                    state.lastHungerElytraPhysicsSample = to.clone();
                    state.lastHungerElytraPhysicsTick = tick;
                    if (previous != null && previous.getWorld() == to.getWorld()) {
                        boolean rocket = System.currentTimeMillis() - state.lastRocketUseMs
                            <= settings.hungerModeRocketGraceTicks * 50L;
                        state.hungerElytraInput.set(to.getX() - previous.getX(), to.getY() - previous.getY(),
                            to.getZ() - previous.getZ(), to.getPitch(), to.getYaw(), to.getY(), tick, rocket,
                            !movementUnsupported || state.impulseGraceTicks > 0
                                || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)
                                || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING));
                        state.hungerElytraExploit = state.hungerElytraVerifier.observe(
                            state.hungerElytraInput, settings.elytraVerifierTuning)
                            .actionable(settings.elytraVerifierTuning);
                    }
                }
            } else {
                state.hungerElytraVerifier.reset();
                state.hungerElytraExploit = false;
                state.lastHungerElytraPhysicsSample = null;
            }
            synchronized (state) {
                state.hungerAcceptedHorizontal += horizontalDistance(from, to);
                state.hungerAcceptedUnsupported |= movementUnsupported;
                state.hungerAcceptedSupported |= !movementUnsupported;
            }
            resetAirFlags(state);
            resetElytraBuffers(state);
            updateSupport(state, serverOnGround, inFluid, to);
            state.lastPos = to.clone();
            state.wasGliding = player.isGliding();
            return;
        }

        state.lastHungerSampleMs = 0L;
        state.lastHungerSamplePos = null;
        state.hungerDebt = 0.0;

        if (!plugin.isAntiFlyEnabled() || !plugin.isWorldEnabled(player.getWorld().getName())) {
            resetAirFlags(state);
            resetElytraBuffers(state);
            updateSupport(state, serverOnGround, inFluid, to);
            state.lastPos = to.clone();
            state.wasGliding = false;
            return;
        }

        if (handleVehicleMovement(player, state, from, to, serverOnGround, inFluid, inBoatWater, boatOnSupport)) {
            state.lastPos = to.clone();
            return;
        }

        if (isExempt(player)) {
            resetAirFlags(state);
            updateSupport(state, serverOnGround, inFluid, to);
            state.lastPos = to.clone();
            return;
        }

        if (state.glideSuppressTicks > 0) {
            state.glideSuppressTicks--;
            if (player.isGliding()) {
                // The client may have re-sent START_FALL_FLYING since the last
                // tick; keep refusing it for the whole cooldown.
                player.setGliding(false);
                resetElytraBuffers(state);
                resetAirFlags(state);
                updateSupport(state, serverOnGround, inFluid, to);
                state.lastPos = to.clone();
                state.wasGliding = false;
                return;
            }
        }

        if (player.isGliding()) {
            if (!handleElytraMovement(player, state, from, to, nearGround, inFluid, inVehicle)) {
                return;
            }
            state.sustainedAirTicks = 0;
            if (plugin.isDebug(player)) {
                sendDebugActionBar(player, state, "ELYTRA", 0.0, 0.0, plugin.getSettings().elytraNoRocketSustainableHorizontal,
                    plugin.getSettings().elytraMaxNoRocketUp);
            }
            state.lastPos = to.clone();
            return;
        }

        if (serverOnGround || isSupportedByCollisionLikeBlock(to)) {
            handleGroundMovement(player, state, from, to);
        } else if (inFluid || inBoatWater) {
            handleFluidMovement(player, state, from, to);
        } else {
            handleNormalAirMovement(player, state, from, to, nearGround);
        }

        if (state.wasGliding) {
            // Just left a glide: drop the accumulated trajectory so the next
            // flight cannot be scored against the previous one.
            state.elytraVerifier.reset();
        }
        state.wasGliding = false;
        state.lastPos = to.clone();
    }

    /**
     * Blocks a glide re-deploy during the suppression window. A hacked client
     * that re-sends START_FALL_FLYING every tick would otherwise undo the
     * stopFallFlying() from the previous violation before the next check runs,
     * which turns detection into a cosmetic message rather than a penalty.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onToggleGlideSuppressed(EntityToggleGlideEvent event) {
        if (!event.isGliding() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (plugin.isExempt(player)) {
            return;
        }
        AntiFlyPlugin.PlayerState state = plugin.getState(player);
        if (state.glideSuppressTicks > 0) {
            event.setCancelled(true);
        }
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        // Re-baseline the anti-cheat state at the teleport destination so the
        // displacement is never treated as a flight violation. The setback
        // anchor only moves if the destination is genuinely supported.
        AntiFlyPlugin.PlayerState state = plugin.getState(player);
        state.teleportGraceTicks = 20;
        rebaselineTeleport(state, to, to.getWorld() != null && hasTeleportSupport(player, to));
    }

    /**
     * True when the teleport destination is genuinely supported (solid ground,
     * collision-like block, or liquid), so the setback anchor may move there.
     */
    private boolean hasTeleportSupport(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        return hasGroundSupport(player, loc)
            || isSupportedByCollisionLikeBlock(loc)
            || loc.getBlock().isLiquid()
            || loc.clone().add(0.0, 1.0, 0.0).getBlock().isLiquid();
    }

    /**
     * Lightweight rebaseline for legitimate teleports. Resets the movement
     * counters and baseline so the displacement is not treated as flight, but
     * NEVER moves the setback anchors (lastGround/lastSupport) to an
     * unsupported destination. Only genuinely supported destinations update
     * the anchors, so a flyer chaining teleport-sized jumps is always
     * corrected back to real ground.
     */
    private void rebaselineTeleport(AntiFlyPlugin.PlayerState state, Location loc, boolean supported) {
        state.lastPos = loc.clone();
        state.hungerElytraVerifier.reset();
        state.hungerElytraExploit = false;
        state.lastHungerElytraPhysicsSample = null;
        state.lastHungerElytraPhysicsTick = Long.MIN_VALUE;
        // Hunger Mode samples position independently of the move checks. Clear
        // that sampler's previous position as well, otherwise the next sample
        // treats the legitimate teleport distance as flight speed and drains
        // hunger (including for ender pearls and other PlayerTeleportEvents).
        state.lastHungerSampleMs = 0L;
        state.lastHungerSamplePos = null;
        state.groundSpoofTicks = 0;
        state.lastServerOnGround = supported;
        state.lastClientOnGround = supported;
        resetAirFlags(state);
        resetElytraBuffers(state);
        if (supported) {
            state.lastGround = loc.clone();
            state.lastSupport = loc.clone();
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
        plugin.resetPlayer(event.getPlayer().getUniqueId());
        plugin.setDebug(event.getPlayer(), false);
    }

    /**
     * A vanilla survival client cannot enable flight at all, so a toggle that
     * arrives without the server granting flight permission is a hacked client
     * asking to fly. Refusing it here stops the exploit before a single packet
     * of movement has to be corrected. Permission-based flight hooks are left
     * alone because they set allowFlight before the toggle arrives.
     */
    @EventHandler(ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!event.isFlying() || isExempt(player)) {
            return;
        }
        if (!plugin.isAntiFlyEnabled() || !plugin.isWorldEnabled(player.getWorld().getName())) {
            return;
        }
        event.setCancelled(true);
        player.setFlying(false);
    }

    /**
     * Flags a velocity change that no amount of legitimate input can produce.
     * A vanilla jump is worth 0.42 blocks per tick, and landing only ever
     * removes velocity, so a large upwards gain or a sudden fast movement can
     * only come from an external push.
     */
    private void detectExternalImpulse(Player player, AntiFlyPlugin.PlayerState state, AntiFlyPlugin.Settings settings) {
        Vector current = player.getVelocity();
        Vector previous = state.lastVelocity;
        state.lastVelocity = current.clone();
        if (previous == null || settings.impulseGraceTicks <= 0) {
            return;
        }
        // Gliding has its own physics model and its own checks. A large
        // velocity jump here is far more likely to be a velocity-writing hack
        // than a rocket, so granting grace would hand the cheat a free pass.
        if (player.isGliding()) {
            return;
        }
        double gain = current.length() - previous.length();
        if (gain > AntiFlyConstants.IMPULSE_MIN_VELOCITY_GAIN
            && current.length() > AntiFlyConstants.IMPULSE_MIN_RESULTING_VELOCITY) {
            state.impulseGraceTicks = settings.impulseGraceTicks;
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        resetState(plugin.getState(event.getEntity()), null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoidTeleport(PlayerTeleportEvent event) {
        AntiFlyPlugin.PlayerState state = plugin.getState(event.getPlayer());
        if (isForbiddenVoid(event.getTo()) && !isForbiddenVoid(event.getFrom())) {
            state.lastVoidSafe = event.getFrom().clone();
        }
        if (isForbiddenVoid(event.getTo())) {
            event.setTo(voidReturnPoint(event.getPlayer(), state));
        }
    }

    /**
     * Pure zone test for the Nether roof (Y >= 128), the Nether void and the
     * Overworld void. Whether that zone is actually enforced depends on the
     * void-access toggle and on Hunger Mode.
     */
    private boolean isVoidZone(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        org.bukkit.World world = loc.getWorld();
        if (world.getEnvironment() == org.bukkit.World.Environment.NETHER) {
            return loc.getY() >= 128.0 || loc.getY() < world.getMinHeight();
        }
        return world.getEnvironment() == org.bukkit.World.Environment.NORMAL
            && loc.getY() < world.getMinHeight();
    }

    /**
     * A denied void zone the player is physically returned from: void access is
     * off and Hunger Mode is off. While Hunger Mode is on the zone is instead
     * allowed and penalized with health damage (see applyVoidDamage), so it is
     * deliberately not "forbidden" for redirect purposes then.
     */
    private boolean isForbiddenVoid(Location loc) {
        return !plugin.getSettings().voidAccess && !plugin.isHungerModeEnabled() && isVoidZone(loc);
    }

    private Location voidReturnPoint(Player player, AntiFlyPlugin.PlayerState state) {
        if (state.lastVoidSafe != null && !isVoidZone(state.lastVoidSafe)) return state.lastVoidSafe.clone();
        if (state.lastSupport != null && !isVoidZone(state.lastSupport)) return state.lastSupport.clone();
        Location spawn = player.getWorld().getSpawnLocation();
        if (!isVoidZone(spawn)) return spawn;
        org.bukkit.World overworld = org.bukkit.Bukkit.getWorlds().stream()
            .filter(world -> world.getEnvironment() == org.bukkit.World.Environment.NORMAL)
            .findFirst().orElse(player.getWorld());
        return overworld.getSpawnLocation();
    }

    private boolean redirectVoidMove(PlayerMoveEvent event, AntiFlyPlugin.PlayerState state) {
        Location to = event.getTo();
        if (to == null) return false;
        if (!isForbiddenVoid(to)) {
            if (!plugin.getSettings().voidAccess) state.lastVoidSafe = to.clone();
            return false;
        }
        Location target = voidReturnPoint(event.getPlayer(), state);
        if (event.getPlayer().getVehicle() != null) {
            Entity vehicle = event.getPlayer().getVehicle();
            event.getPlayer().leaveVehicle();
            vehicle.teleport(target);
        }
        event.setTo(target);
        return true;
    }

    /** Server authored knockback, explosions and plugin impulses are legitimate. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        AntiFlyPlugin.PlayerState state = plugin.getState(player);
        state.lastExternalImpulseTick = player.getWorld().getGameTime();
        state.impulseGraceTicks = Math.max(state.impulseGraceTicks, plugin.getSettings().impulseGraceTicks);
        state.elytraVerifier.reset();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        resetState(plugin.getState(event.getPlayer()), event.getRespawnLocation());
    }

    /**
     * Periodic server-side Hunger Mode sampler. Runs even when the player sends
     * no move packets, so stationary hovering is always penalized. Speed is
     * measured purely from horizontal displacement between server-side position
     * samples (never the client onGround flag), so a diagonal flight pays
     * exactly once for its true horizontal speed.
     */
    void hungerModeTick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // The sampler is driven from the global region scheduler, so on
            // Folia each player's state still has to be touched from the thread
            // that owns them. Everywhere else this runs inline as before.
            PlatformCompat.runForPlayer(plugin, player, () -> hungerModeTickPlayer(player));
        }
    }

    private void hungerModeTickPlayer(Player player) {
        AntiFlyPlugin.Settings settings = plugin.getSettings();
        boolean enabled = plugin.isHungerModeEnabled();
        long nowMs = System.currentTimeMillis();
        AntiFlyPlugin.PlayerState state = plugin.getState(player);
        // Void access denied. With Hunger Mode off the player is returned to
        // safety; with Hunger Mode on they may stay in the zone but pay health
        // directly, in place of the normal hunger-then-health chain.
        if (!player.isDead() && !settings.voidAccess && isVoidZone(player.getLocation())) {
            if (enabled) {
                if (!isExempt(player)) {
                    applyVoidDamage(player, state, settings, nowMs);
                }
                // No hunger in the void: the zone is charged as health only, so
                // drop the food sampler baseline and any pending debt.
                state.lastHungerSampleMs = 0L;
                state.lastHungerSamplePos = null;
                state.hungerDebt = 0.0;
                synchronized (state) {
                    state.hungerAcceptedHorizontal = 0.0;
                    state.hungerAcceptedUnsupported = false;
                    state.hungerAcceptedSupported = false;
                }
                return;
            }
            Location target = voidReturnPoint(player, state);
            if (player.getVehicle() != null) {
                Entity vehicle = player.getVehicle();
                player.leaveVehicle();
                vehicle.teleport(target);
            }
            player.teleport(target);
            return;
        }
        // Outside a denied void zone: restart the damage clock so re-entry costs
        // health immediately and time spent outside never counts toward it.
        state.lastVoidDamageMs = 0L;
        state.voidDamageSeconds = 0.0;
        if (!settings.voidAccess) state.lastVoidSafe = player.getLocation().clone();
        // Hunger Mode replaces the movement blocks, so anything the movement
        // checks would have excused has to be excused here too - otherwise an
        // explicitly exempt player is still drained and damaged.
        if (!enabled || player.isDead() || !plugin.isWorldEnabled(player.getWorld().getName())
            || isExempt(player)) {
            state.lastHungerSampleMs = 0L;
            state.lastHungerSamplePos = null;
            state.hungerDebt = 0.0;
            state.flightAirborneSeconds = 0.0;
            synchronized (state) {
                state.hungerAcceptedHorizontal = 0.0;
                state.hungerAcceptedUnsupported = false;
                state.hungerAcceptedSupported = false;
            }
            return;
        }

        Location pos = player.getLocation();
        Location prev = state.lastHungerSamplePos;
        if (prev == null || state.lastHungerSampleMs == 0L) {
            state.lastHungerSampleMs = nowMs;
            state.lastHungerSamplePos = pos.clone();
            return;
        }

        // The sampler observes wall-clock time, so a lag spike could hide
        // several seconds of travel. Under-charging that interval is exactly
        // when a lag flyer benefits, so allow a wider window.
        double elapsedSeconds = Math.min(5.0, Math.max(0.05, (nowMs - state.lastHungerSampleMs) / 1000.0));
        state.lastHungerSampleMs = nowMs;
        state.lastHungerSamplePos = pos.clone();

        boolean inFluid = player.isInWater() || PlatformCompat.isInLava(player) || player.isSwimming();
        // A vehicle only excuses the hover penalty when it is itself supported
        // (boat on water/ground, mount standing) - flying in a boat is penalized
        // like any other unsupported flight.
        boolean vehicleSupported = false;
        if (player.isInsideVehicle()) {
            Entity vehicle = player.getVehicle();
            if (vehicle instanceof Boat boat) {
                vehicleSupported = isBoatInFluid(boat) || hasBoatGroundSupport(boat);
            } else {
                vehicleSupported = vehicle.isOnGround();
            }
        }
        boolean unsupported = !hasGroundSupport(player, pos)
            && !isSupportedByCollisionLikeBlock(pos)
            && !inFluid && !vehicleSupported;
        boolean gliding = player.isGliding();

        // Consume server-accepted movement accumulated by PlayerMoveEvent. This
        // prevents high-speed packet streams from evading the slower periodic
        // Location sample while preserving a sampler for hovering.
        double acceptedHorizontal;
        boolean acceptedUnsupported;
        boolean acceptedSupported;
        synchronized (state) {
            acceptedHorizontal = state.hungerAcceptedHorizontal;
            acceptedUnsupported = state.hungerAcceptedUnsupported;
            acceptedSupported = state.hungerAcceptedSupported;
            state.hungerAcceptedHorizontal = 0.0;
            state.hungerAcceptedUnsupported = false;
            state.hungerAcceptedSupported = false;
        }

        // Horizontal-only speed: diagonal flight counts once at its real
        // horizontal rate, never compounded with a vertical component.
        double sampledSpeedBps = horizontalDistance(prev, pos) / elapsedSeconds;
        double rawSpeedBps = Math.max(sampledSpeedBps, acceptedHorizontal / elapsedSeconds);
        // A supported sample (or landing move) wins over earlier airborne
        // movement in the same interval: landing cancels pending debt.
        if (!unsupported || acceptedSupported) {
            unsupported = false;
            rawSpeedBps = 0.0;
            state.hungerDebt = 0.0;
        } else {
            unsupported |= acceptedUnsupported;
        }

        // Rocket grace only covers recent firework boosts.
        boolean recentRocket = gliding
            && nowMs - state.lastRocketUseMs <= settings.hungerModeRocketGraceTicks * 50L;

        // Elytra exploit detection: abnormal speed (> threshold), hover-hack
        // (no horizontal movement), gliding for a long time without any rocket,
        // or movement the glide physics verifier cannot explain. Vanilla gliding
        // with rockets is never an exploit.
        boolean glidingExploit = false;
        if (gliding) {
            if (recentRocket) {
                state.glideNoRocketSeconds = 0.0;
            } else {
                state.glideNoRocketSeconds += elapsedSeconds;
            }
            boolean hovering = unsupported && rawSpeedBps < ELYTRA_HOVER_MAX_BPS;
            glidingExploit = rawSpeedBps > settings.hungerModeElytraSpeedThresholdBps
                || hovering
                || state.glideNoRocketSeconds > settings.hungerModeElytraNoRocketAfterSeconds
                || state.hungerElytraExploit;
        } else {
            state.glideNoRocketSeconds = 0.0;
        }

        double hungerLoss;
        if (recentRocket) {
            hungerLoss = 0.0;
        } else if (gliding) {
            if (!settings.hungerModeElytraFoodEnabled) {
                // Elytra fully exempt from food drain.
                hungerLoss = 0.0;
            } else if (unsupported && rawSpeedBps < ELYTRA_HOVER_MAX_BPS) {
                // Elytra hover-hack: hover penalty, same as hovering without an
                // elytra.
                double hoverNormalized = Math.min(1.0, settings.hungerModeAirborneMinimumBlocksPerSecond
                    / settings.hungerModeMaxBlocksPerSecond);
                hungerLoss = settings.hungerModeHungerPerSecondAtMaxSpeed
                    * hoverNormalized * elapsedSeconds;
            } else if (glidingExploit) {
                // Abnormal elytra speed or no-rocket exploit: speed-based drain
                // (with elytra multiplier).
                double normalizedSpeed = Math.min(1.0, rawSpeedBps / settings.hungerModeMaxBlocksPerSecond);
                hungerLoss = settings.hungerModeHungerPerSecondAtMaxSpeed
                    * normalizedSpeed * normalizedSpeed * elapsedSeconds
                    * settings.hungerModeElytraFoodMultiplier;
            } else {
                // Normal elytra gliding: no food drain.
                hungerLoss = 0.0;
            }
        } else {
            // Normal (non-elytra) movement: speed-based drain with the airborne
            // minimum floor for unsupported players.
            double speedBlocksPerSecond = rawSpeedBps;
            if (unsupported) {
                speedBlocksPerSecond = Math.max(speedBlocksPerSecond, settings.hungerModeAirborneMinimumBlocksPerSecond);
            }
            double normalizedSpeed = Math.min(1.0, speedBlocksPerSecond / settings.hungerModeMaxBlocksPerSecond);
            // The airborne minimum is a baseline cost, not a squared 1%-of-max
            // penalty. This keeps idle hovering visible.
            if (unsupported && rawSpeedBps < settings.hungerModeAirborneMinimumBlocksPerSecond) {
                hungerLoss = settings.hungerModeHungerPerSecondAtMaxSpeed
                    * normalizedSpeed * elapsedSeconds;
            } else {
                hungerLoss = settings.hungerModeHungerPerSecondAtMaxSpeed
                    * normalizedSpeed * normalizedSpeed * elapsedSeconds;
            }
        }

        // Sustained unsupported flight deals real health damage after the
        // configured delay, so carrying stacks of food cannot sustain an endless
        // flight - food restores hunger, not the health being lost. Health
        // damage only follows hunger accumulation: for elytra the timer counts
        // only while hunger is ACTIVELY draining, so the hunger phase always
        // comes first and physical damage only starts if the exploit keeps
        // going. Damage ticks are slow (every 2s, every 4s while descending) so
        // fast hacks don't make it hectic, and landing is always survivable.
        if (settings.hungerModeFlightDamageEnabled) {
            boolean damageCounted = unsupported
                && (!gliding || (settings.hungerModeElytraDamageEnabled && glidingExploit));
            if (damageCounted) {
                if (gliding && recentRocket && settings.hungerModeRocketResetsDamage) {
                    state.flightAirborneSeconds = 0.0;
                    state.lastFlightDamageAtSeconds = 0.0;
                    state.hungerDrainSeconds = 0.0;
                } else {
                    if (gliding) {
                        // Elytra: only count time while hunger is actually
                        // draining (hungerLoss > 0), so health damage can never
                        // skip the hunger phase.
                        if (hungerLoss > 0.0) {
                            state.hungerDrainSeconds += elapsedSeconds;
                        } else {
                            state.hungerDrainSeconds = 0.0;
                        }
                        state.flightAirborneSeconds = state.hungerDrainSeconds;
                    } else {
                        state.flightAirborneSeconds += elapsedSeconds;
                    }
                    double gate = gliding
                        ? settings.hungerModeFlightDamageAfterHungerSeconds
                        : settings.hungerModeFlightDamageAfterSeconds;
                    boolean starving = player.getFoodLevel() <= 0;
                    // Reaching zero food is an immediate escalation: do not wait
                    // out the normal flight timer before health damage.
                    if (starving || state.flightAirborneSeconds > gate) {
                        // While descending, only tick damage at half frequency so
                        // landing attempts are survivable.
                        double descent = pos.getY() - prev.getY();
                        boolean descending = descent < -0.5;
                        double interval = descending ? 4.0 : 2.0;
                        double next = starving && state.lastFlightDamageAtSeconds == 0.0
                            ? state.flightAirborneSeconds
                            : state.lastFlightDamageAtSeconds + interval;
                        if (state.flightAirborneSeconds >= next) {
                            state.lastFlightDamageAtSeconds = state.flightAirborneSeconds;
                            double dmg = settings.hungerModeFlightDamagePerSecond;
                            if (starving) {
                                // Health units are half-hearts: double the configured
                                // penalty at starvation so 1.0 deals one full heart.
                                dmg *= 2.0;
                            }
                            if (dmg > 0.0) {
                                // Use a separate custom damage event so the flight
                                // penalty stacks with vanilla starvation at zero food.
                                double flightDamage = dmg;
                                PlatformCompat.runForPlayer(plugin, player, () -> player.damage(flightDamage));
                            }
                        }
                    }
                }
            } else {
                // Recovering from a flight attempt takes time. The timer decays
                // instead of resetting, so a flyer who touches down for a single
                // tick cannot erase everything that came before and restart the
                // clock from zero.
                double recovery = elapsedSeconds * AntiFlyConstants.DAMAGE_TIMER_RECOVERY_MULTIPLIER;
                state.flightAirborneSeconds = Math.max(0.0, state.flightAirborneSeconds - recovery);
                state.hungerDrainSeconds = Math.max(0.0, state.hungerDrainSeconds - recovery);
            }
        } else {
            state.flightAirborneSeconds = 0.0;
            state.lastFlightDamageAtSeconds = 0.0;
            state.hungerDrainSeconds = 0.0;
        }

        state.hungerDebt += hungerLoss;
        // Apply at most one food point per tick. Larger speed-derived debt
        // remains queued so the client sees a continuous drain rather than one
        // delayed bulk update after the player stops moving.
        if (state.hungerDebt >= 1.0 && player.getFoodLevel() > 0) {
            int foodAfter = player.getFoodLevel() - 1;
            // Entity state changes are only permitted on the owning region
            // thread on Folia; elsewhere this runs inline.
            PlatformCompat.runForPlayer(plugin, player, () -> player.setFoodLevel(foodAfter));
            state.hungerDebt -= 1.0;

            // The final food point was removed by this flight tick. Apply the
            // first starvation flight hit now, rather than waiting for the next
            // sampler pass to observe food level zero.
            boolean flightDamageEligible = settings.hungerModeFlightDamageEnabled
                && unsupported
                && (!gliding || (settings.hungerModeElytraDamageEnabled && glidingExploit))
                && !(gliding && recentRocket && settings.hungerModeRocketResetsDamage);
            if (foodAfter == 0 && flightDamageEligible) {
                double dmg = settings.hungerModeFlightDamagePerSecond * 2.0;
                if (dmg > 0.0) {
                    // Vanilla starvation may have just applied hurt immunity.
                    // This is an explicit zero-food flight escalation, so deduct
                    // the health directly rather than letting that immunity or
                    // another damage listener defer the required penalty.
                    double healthAfter = Math.max(0.0, player.getHealth() - dmg);
                    PlatformCompat.runForPlayer(plugin, player, () -> player.setHealth(healthAfter));
                    state.lastFlightDamageAtSeconds = state.flightAirborneSeconds;
                }
            }
        }
    }

    /**
     * Health penalty for a player who stays in a denied void zone while Hunger
     * Mode is on. The first tick in the zone deals damage at once, then the
     * configured per-second amount keeps applying for as long as they remain.
     * The rate reuses hungerModeFlightDamagePerSecond so it stays tunable.
     */
    private void applyVoidDamage(Player player, AntiFlyPlugin.PlayerState state,
                                 AntiFlyPlugin.Settings settings, long nowMs) {
        if (state.lastVoidDamageMs == 0L) {
            state.lastVoidDamageMs = nowMs;
            state.voidDamageSeconds = 0.0;
            damageVoid(player, settings);
            return;
        }
        double elapsed = Math.min(5.0, Math.max(0.0, (nowMs - state.lastVoidDamageMs) / 1000.0));
        state.lastVoidDamageMs = nowMs;
        state.voidDamageSeconds += elapsed;
        while (state.voidDamageSeconds >= 1.0) {
            state.voidDamageSeconds -= 1.0;
            damageVoid(player, settings);
        }
    }

    private void damageVoid(Player player, AntiFlyPlugin.Settings settings) {
        double dmg = settings.hungerModeFlightDamagePerSecond;
        if (dmg > 0.0 && player.isValid()) {
            player.damage(dmg);
        }
    }

    private void handleGroundMovement(Player player, AntiFlyPlugin.PlayerState state, Location from, Location to) {
        double horizontal = horizontalDistance(from, to);
        Vector serverVelocity = player.getVelocity();
        double carriedMomentum = Math.hypot(serverVelocity.getX(), serverVelocity.getZ());
        double maxAllowed = Math.max(maxGroundSpeed(player), carriedMomentum + 0.05);
        double groundTolerance = 0.03;
        if (plugin.isDebug(player)) {
            sendDebugActionBar(player, state, "GROUND", horizontal, to.getY() - from.getY(), maxAllowed, 0.0);
        }
        // Reject fast movement that still reports airborne while collision support makes the position look grounded.
        // Landing out of a glide keeps elytra momentum for a tick or two, and the
        // client often still reports airborne across that boundary, so this has to
        // respect the same landing grace the ground-speed check below already does.
        // Without it, every normal glide landing reads as ground spoofing.
        if (state.glideLandingGraceTicks <= 0
            && !player.isOnGround() && horizontal > (maxAllowed + groundTolerance)) {
            fail(player, state, "ground_spoof_speed", horizontal, maxAllowed);
            return;
        }
        if (state.glideLandingGraceTicks <= 0 && horizontal > (maxAllowed + groundTolerance)) {
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
        double maxHorizontal = Math.max(maxWaterSpeed(player), Math.hypot(vel.getX(), vel.getZ()) + 0.05);
        double maxVertical = Math.max(maxWaterVerticalSpeed(player), vel.getY() + 0.05);
        if (plugin.isDebug(player)) {
            sendDebugActionBar(player, state, "FLUID", horizontal, deltaY, maxHorizontal, maxVertical);
        }
        if (horizontal > maxHorizontal) {
            fail(player, state, "water_speed", horizontal, maxHorizontal);
            return;
        }
        if (deltaY > maxVertical) {
            fail(player, state, "water_vertical", deltaY, maxVertical);
            return;
        }
        updateSupport(state, false, true, to);
        resetAirFlags(state);
    }

    private void handleNormalAirMovement(Player player, AntiFlyPlugin.PlayerState state, Location from, Location to, boolean nearGround) {
        AntiFlyPlugin.Settings settings = plugin.getSettings();
        Vector vel = player.getVelocity();

        state.airTicks++;
        // Sustained air time catches level "bobbing" flight that evades the
        // speed, vertical, hover and no-fall checks by cruising under the caps
        // and resetting the descent counter on each bob. Only landing (or
        // fluid/vehicle support) resets this counter.
        if (state.sustainedAirTicks == 0) state.sustainedAirStartY = from.getY();
        state.sustainedAirTicks++;
        if (state.sustainedAirTicks > settings.sustainedAirTicksLimit) {
            if (state.sustainedAirStartY - to.getY() >= settings.sustainedAirMinDescent) {
                state.sustainedAirTicks = 0;
                state.sustainedAirStartY = to.getY();
            } else {
                fail(player, state, "air_sustained", state.sustainedAirStartY - to.getY(), settings.sustainedAirMinDescent);
                return;
            }
        }
        if (state.flightRevokeGraceTicks > 0) {
            state.flightRevokeGraceTicks--;
        }

        double horizontal = Math.max(horizontalDistance(from, to), Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ()));
        // Use actual displacement for airborne vertical checks so stale velocity snapshots
        // do not turn normal cliff jumps/falls into false upward-flight violations.
        double deltaY = to.getY() - from.getY();
        boolean graceAir = state.airTicks <= settings.airGraceTicks || state.flightRevokeGraceTicks > 0
            || state.glideLandingGraceTicks > 0;
        double allowedHorizontal = Math.max(settings.maxAirHorizontal,
            Math.hypot(vel.getX(), vel.getZ()) + 0.05);
        double allowedVertical = Math.max(settings.maxAirVertical, vel.getY() + 0.05);
        if (plugin.isDebug(player)) {
            sendDebugActionBar(player, state, "AIR", horizontal, deltaY, settings.maxAirHorizontal, settings.maxAirVertical);
        }

        if (player.isOnGround() && !state.lastServerOnGround) {
            // Client claims ground but server disagrees - only penalize if
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

        // A client that keeps claiming solid ground, and keeps moving faster or
        // climbing higher than that ground would allow, is reporting support the
        // server cannot find. Sustained, that is a no-fall style spoof rather
        // than a one-off desync, so it is enforced instead of only buffered.
        if (state.groundSpoofTicks > settings.groundSpoofTicksLimit) {
            fail(player, state, "ground_spoof", state.groundSpoofTicks, settings.groundSpoofTicksLimit);
            return;
        }

        if (!graceAir && horizontal > allowedHorizontal) {
            state.airHorizontalBuffer += horizontal - allowedHorizontal;
        } else {
            state.airHorizontalBuffer = decay(state.airHorizontalBuffer, settings.bufferDecay);
        }

        if (!graceAir && deltaY > allowedVertical) {
            state.airVerticalBuffer += deltaY - allowedVertical;
        } else {
            state.airVerticalBuffer = decay(state.airVerticalBuffer, settings.bufferDecay);
        }

        boolean barelyMovingY = Math.abs(deltaY) <= settings.hoverDeltaY;
        boolean nearlyZeroVerticalVelocity = Math.abs(vel.getY()) <= Math.max(settings.hoverDeltaY * 4.0, 0.03);
        boolean barelyMovingXZ = horizontal <= settings.hoverHorizontal;
        if (state.airTicks > settings.hoverStartTicks
            && barelyMovingY
            && nearlyZeroVerticalVelocity
            && !isHoverNearGround(player, to)) {
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

        boolean sustainedFlatAir = settings.noFallDetectionEnabled
            && state.antiKickWindowTicks >= flatAirWindowTicks(settings)
            && state.airWindowDescent < 0.15
            && state.airWindowAscent < 0.15
            && horizontal >= 0.45;
        if (sustainedFlatAir) {
            fail(player, state, "air_flat_cruise", horizontal, 0.45);
            return;
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

    /**
     * The flat-cruise window used to be a hardcoded 12 ticks, so lowering
     * antiKickWindowTicks below that silently disabled the check entirely
     * (the counter reset before it could ever reach the threshold). Scaling
     * the window keeps the 12-tick default and stays consistent at any
     * configured antiKickWindowTicks.
     */
    private int flatAirWindowTicks(AntiFlyPlugin.Settings settings) {
        return Math.max(1, (int) Math.round(settings.antiKickWindowTicks * 0.3));
    }

    private boolean handleVehicleMovement(Player player, AntiFlyPlugin.PlayerState state, Location from, Location to,
                                          boolean serverOnGround, boolean inFluid, boolean inBoatWater,
                                          boolean boatOnSupport) {
        if (!player.isInsideVehicle()) {
            return false;
        }

        AntiFlyPlugin.Settings settings = plugin.getSettings();
        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return false;
        }

        if (state.vehicleAirTicks == 0 && !(vehicle instanceof Boat)) {
            state.flightRevokeGraceTicks = 20;
        }

        if (vehicle instanceof Boat && (inBoatWater || boatOnSupport)) {
            double horizontal = horizontalDistance(from, to);
            Vector boatVelocity = vehicle.getVelocity();
            horizontal = Math.max(horizontal, Math.sqrt(boatVelocity.getX() * boatVelocity.getX() + boatVelocity.getZ() * boatVelocity.getZ()));
            double maxAllowed = settings.boatMaxHorizontal;
            if (plugin.isDebug(player)) {
                sendDebugActionBar(player, state, "BOAT", horizontal, to.getY() - from.getY(), maxAllowed, 0.0);
            }
            if (horizontal > maxAllowed) {
                rubberBandVehicle(player, state, "boat_speed", horizontal, maxAllowed);
                return true;
            }
            updateSupport(state, false, true, to);
            resetAirFlags(state);
            state.vehicleFallTicks = 0;
            state.vehicleFallHorizontalDistance = 0.0;
            return true;
        }

        if (serverOnGround || inFluid) {
            state.vehicleAirTicks = 0;
            state.vehicleFallTicks = 0;
            state.vehicleFallHorizontalDistance = 0.0;
            return true;
        }

        double deltaY = to.getY() - from.getY();
        double horizontal = horizontalDistance(from, to);
        if (vehicle instanceof Boat boat) {
            Vector velocity = boat.getVelocity();
            double horizontalVelocity = Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
            horizontal = Math.max(horizontal, horizontalVelocity);
            if (horizontal > settings.boatMaxHorizontal) {
                rubberBandVehicle(player, state, "boat_speed", horizontal, settings.boatMaxHorizontal);
                return true;
            }
            if (deltaY > 0.02 || velocity.getY() > 0.05) {
                rubberBandVehicle(player, state, "vehicle_flight", Math.max(deltaY, velocity.getY()), 0.0);
                return true;
            }
            if (Math.abs(deltaY) <= 0.001 && Math.abs(velocity.getY()) <= 0.05) {
                boat.setVelocity(new Vector(0.0, -0.08, 0.0));
                state.vehicleAirTicks = 0;
                return true;
            }
        }
        double naturalFallHorizontal = vehicle instanceof Boat ? settings.boatMaxHorizontal : settings.vehicleFallMaxHorizontal;
        boolean naturalFall = deltaY <= settings.vehicleFallMinDescent && horizontal <= naturalFallHorizontal;

        if (naturalFall) {
            // A long fall still has to be a fall: a vehicle that "descends"
            // while covering enormous horizontal distance is gliding, not
            // falling, so the cumulative horizontal budget bounds the whole
            // window rather than each individual tick.
            state.vehicleFallTicks++;
            state.vehicleFallHorizontalDistance += horizontal;
            double horizontalBudget = settings.vehicleFallMaxHorizontal * Math.max(1, settings.vehicleFallTicksMax);
            if (state.vehicleFallTicks <= settings.vehicleFallTicksMax
                && state.vehicleFallHorizontalDistance <= horizontalBudget) {
                state.vehicleAirTicks = 0;
                return true;
            }
            rubberBandVehicle(player, state, "vehicle_flight", state.vehicleFallHorizontalDistance, horizontalBudget);
            return true;
        }
        state.vehicleFallTicks = 0;
        state.vehicleFallHorizontalDistance = 0.0;

        int grace = vehicle instanceof AbstractHorse ? settings.horseAirGraceTicks
            : (vehicle instanceof Boat ? settings.boatAirGraceTicks : settings.vehicleAirGraceTicks);
        state.vehicleAirTicks++;
        if (state.vehicleAirTicks <= grace) {
            return true;
        }

        rubberBandVehicle(player, state, "vehicle_flight", state.vehicleAirTicks, grace);
        return true;
    }

    private boolean isBoatInFluid(Boat boat) {
        Location loc = boat.getLocation();
        return boat.isInWater()
            || loc.getBlock().isLiquid()
            || loc.clone().subtract(0.0, 1.0, 0.0).getBlock().isLiquid()
            || loc.clone().add(0.0, 1.0, 0.0).getBlock().isLiquid();
    }

    private boolean hasBoatGroundSupport(Boat boat) {
        BoundingBox box = boat.getBoundingBox();
        double minX = box.getMinX() + 0.03;
        double maxX = box.getMaxX() - 0.03;
        double minZ = box.getMinZ() + 0.03;
        double maxZ = box.getMaxZ() - 0.03;
        int y = (int) Math.floor(box.getMinY() - 0.03);
        Location loc = boat.getLocation();
        return hasSolidSupportAtY(loc, minX, maxX, minZ, maxZ, y);
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
        if (recentRocket) {
            state.elytraMovementBuffer = decay(state.elytraMovementBuffer, settings.bufferDecay * 1.5);
        } else if (horizontal > maxHorizontal) {
            // Suspicion score, not distance accumulation.
            double excess = horizontal - maxHorizontal;
            double increment = Math.min(2.0, 0.6 + (excess * 0.35));
            state.elytraMovementBuffer += increment;
        } else {
            state.elytraMovementBuffer = decay(state.elytraMovementBuffer, postRocketBleed ? (settings.bufferDecay * 2.0) : settings.bufferDecay);
        }

        if (deltaY > maxUp) {
            if (!recentRocket) {
                boolean hasRecentDiveEnergy = state.glideWindowDescent >= settings.elytraRequiredDescentForPullup;
                if (!recentRocket && !recentRiptide && !hasRecentDiveEnergy) {
                    state.elytraMovementBuffer += 1.0;
                }
            }
        }

        // Legacy altitude diagnostic. Real pull-ups overlap with hacked climbs,
        // so this counter is never used as a standalone enforcement signal.
        if (recentRocket || recentRiptide) {
            state.elytraNetAltitude = 0.0;
        } else {
            state.elytraNetAltitude += deltaY;
        }

        // ---- Shadow physics verifier -------------------------------------------
        // Score a tick-sampled trajectory against the unpowered vanilla glide
        // step. Known propulsion and special movement reset the baseline.
        com.antifly.common.ElytraPhysics.Mode verifierMode =
            com.antifly.common.ElytraPhysics.Mode.parse(settings.elytraVerifierMode);
        if (verifierMode != com.antifly.common.ElytraPhysics.Mode.OFF) {
            long sampleTick = to.getWorld().getGameTime();
            if (state.lastElytraSampleTick != sampleTick) {
                Location previousSample = state.lastElytraSample;
                state.lastElytraSample = to.clone();
                state.lastElytraSampleTick = sampleTick;
                if (previousSample != null && previousSample.getWorld() == to.getWorld()) {
                    state.elytraInput.set(
                        to.getX() - previousSample.getX(), to.getY() - previousSample.getY(),
                        to.getZ() - previousSample.getZ(), to.getPitch(), to.getYaw(), to.getY(),
                        sampleTick, recentRocket || recentRiptide,
                        nearGround || inFluid || state.impulseGraceTicks > 0
                            || state.lastExternalImpulseTick != Long.MIN_VALUE
                                && sampleTick - state.lastExternalImpulseTick <= settings.impulseGraceTicks
                            || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)
                            || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING));
                    com.antifly.common.ElytraPhysics.Verdict verdict =
                        state.elytraVerifier.observe(state.elytraInput, settings.elytraVerifierTuning);
                    if (verdict.actionable(settings.elytraVerifierTuning)) {
                if (verifierMode == com.antifly.common.ElytraPhysics.Mode.ENFORCE) {
                    failElytra(player, state, "elytra_physics_" + verdict.reason,
                        verdict.evidence, verdict.confidence);
                    return false;
                }
                if (plugin.isDebug(player)) {
                    sendDebugActionBar(player, state,
                        "VERIFY#" + verdict.reason, verdict.evidence, verdict.confidence, 0.0, 0.0);
                }
                    }
                } else {
                    state.elytraVerifier.reset();
                }
            }
        }

        // A net-altitude threshold alone cannot separate a hack from a genuine
        // momentum pull-up, because both can gain a similar amount. The shape
        // differs though: vanilla converts momentum, so its climb rate decays
        // within a few ticks, while a hack holds a constant rate indefinitely.
        boolean sustainedClimb = !recentRocket && !recentRiptide
            && deltaY > AntiFlyConstants.ELYTRA_SUSTAINED_CLIMB_MIN_DELTA_Y;
        if (sustainedClimb) {
            state.glideSustainedClimbTicks++;
        } else {
            state.glideSustainedClimbTicks = 0;
        }

        if (!recentRocket && !recentRiptide && !nearGround && !postRocketBleed) {
            state.glideNoRocketWindowTicks++;
            state.glideWindowHorizontal += horizontal;
            if (deltaY < 0) {
                state.glideWindowDescent += -deltaY;
            }

            if (state.glideNoRocketWindowTicks >= settings.elytraNoRocketWindowTicks) {
                boolean highHorizontal = horizontal > settings.elytraNoRocketSustainableHorizontal;
                boolean tooLittleDescent = state.glideWindowDescent < settings.elytraNoRocketMinDescent;
                if (highHorizontal && tooLittleDescent) {
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
            } else {
                state.glideHoverTicks = Math.max(0, state.glideHoverTicks - 1);
            }

            // Explicitly detect controlled upward Elytra motion without a valid energy source.
            boolean allowUpControlGrace = nearGround
                || state.glideToggleGraceTicks > 0
                || state.fluidExitGraceTicks > 0;
            if (!allowUpControlGrace && deltaY > NO_ROCKET_UP_CONTROL_MIN_DELTA_Y && horizontal > 0.02) {
                state.glideControlTicks++;
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
        }

        if (settings.elytraDurabilityCheckEnabled) {
            trackElytraDurability(player, state, settings);
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
        return hasSolidSupportAtY(loc, minX, maxX, minZ, maxZ, y)
            || hasEntitySupport(player);
    }

    /**
     * True when the player is standing on a vehicle or mob (a boat, minecart or
     * similar) rather than on blocks. The client treats that collision box as a
     * floor, but the block probes cannot see it, so without this every step onto
     * a boat looks like a client claiming ground the server cannot find - which
     * lands the player in the airborne checks and sets them back off the boat.
     */
    private boolean hasEntitySupport(Player player) {
        BoundingBox box = player.getBoundingBox();
        double feet = box.getMinY();
        for (Entity entity : player.getNearbyEntities(1.0, 1.0, 1.0)) {
            if (entity instanceof Player) {
                continue;
            }
            BoundingBox other = entity.getBoundingBox();
            if (other.getMaxX() <= box.getMinX() || other.getMinX() >= box.getMaxX()
                || other.getMaxZ() <= box.getMinZ() || other.getMinZ() >= box.getMaxZ()) {
                continue;
            }
            double otherTop = other.getMaxY();
            if (otherTop >= feet - SUPPORT_ENTITY_DEPTH && otherTop <= feet + SUPPORT_ENTITY_SLACK) {
                return true;
            }
        }
        return false;
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

    /**
     * Hovering only stops counting when the player is genuinely about to touch
     * down. isNearGround probes 0.30 blocks down, which left a band where a
     * stationary player was airborne yet never registered as hovering; this
     * shallow probe keeps the immediate landing case suppressed and closes it.
     */
    private boolean isHoverNearGround(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        BoundingBox box = player.getBoundingBox();
        double minX = box.getMinX() + 0.03;
        double maxX = box.getMaxX() - 0.03;
        double minZ = box.getMinZ() + 0.03;
        double maxZ = box.getMaxZ() - 0.03;
        int y = (int) Math.floor(box.getMinY() - AntiFlyConstants.HOVER_GROUND_SUPPRESSION_DEPTH);
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

    private double maxWaterSpeed(Player player) {
        return plugin.getSettings().waterMax * waterSpeedFactor(player);
    }

    private double maxWaterVerticalSpeed(Player player) {
        return plugin.getSettings().waterVerticalMax * waterSpeedFactor(player);
    }

    /**
     * Water movement is faster with Depth Strider, Dolphin's Grace and plain
     * swimming. Without this the flat waterMax flagged ordinary vanilla
     * swimming gear as a speed hack.
     */
    private double waterSpeedFactor(Player player) {
        double factor = 1.0;
        if (player.isSwimming()) {
            factor *= 1.15;
        }
        ItemStack boots = player.getInventory().getBoots();
        if (boots != null) {
            int depthStrider = boots.getEnchantmentLevel(Enchantment.DEPTH_STRIDER);
            if (depthStrider > 0) {
                factor *= 1.0 + (depthStrider * AntiFlyConstants.WATER_SPEED_DEPTH_STRIDER_PER_LEVEL);
            }
        }
        if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
            factor *= 1.0 + AntiFlyConstants.WATER_SPEED_DOLPHINS_GRACE;
        }
        return factor;
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
        state.vehicleAirTicks = 0;
        state.airNonFallTicks = 0;
        state.hoverTicks = 0;
        state.sustainedAirTicks = 0;
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
    }

    private void resetElytraBuffers(AntiFlyPlugin.PlayerState state) {
        state.glideStallTicks = 0;
        state.glideHoverTicks = 0;
        state.glideControlTicks = 0;
        state.elytraMovementBuffer = 0.0;
        state.elytraBuffer = 0.0;
        state.elytraNetAltitude = 0.0;
        state.glideSustainedClimbTicks = 0;
        state.elytraVerifier.reset();
        state.lastElytraSample = null;
        state.lastElytraSampleTick = Long.MIN_VALUE;
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

    private void rubberBandVehicle(Player player, AntiFlyPlugin.PlayerState state, String reason,
                                   double actual, double allowed) {
        if (player.getVehicle() != null) {
            player.getVehicle().setVelocity(new Vector(0, 0, 0));
        }
        fail(player, state, reason, actual, allowed);
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
        // A glide violation must suppress gliding, not merely end it once: the
        // hack's auto-deploy re-sends START_FALL_FLYING every tick, so a plain
        // stopFallFlying() would be undone before the next check runs.
        state.glideSuppressTicks = plugin.getSettings().elytraGlideSuppressionTicks;
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
        // The running count is deliberately NOT cleared by a setback: a player
        // who is corrected and immediately carries on should look different
        // from someone who tripped a check once and stopped.
        if (now - state.lastFailMs > AntiFlyConstants.REPEAT_OFFENDER_WINDOW_MS) {
            state.failCount = 0;
        }
        state.lastFailMs = now;
        state.failCount++;
        if (state.failCount >= plugin.getSettings().repeatOffenderAlertCount
            && now - state.lastRepeatAlertMs > AntiFlyConstants.REPEAT_OFFENDER_COOLDOWN_MS) {
            state.lastRepeatAlertMs = now;
            broadcastRepeatOffender(player, state);
        }
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
            case "boat_speed" -> "boatMaxHorizontal";
            case "ground_spoof" -> "groundSpoofTicksLimit";
            case "air_offset_horizontal" -> "horizontalBufferLimit/maxAirHorizontal";
            case "air_offset_vertical" -> "verticalBufferLimit/maxAirVertical";
            case "air_hover" -> "hoverBufferLimit";
            case "air_nonfall" -> "airNonFallTicksLimit";
            case "air_sustained" -> "sustainedAirTicksLimit";
            case "air_antikick" -> "antiKickWindowTicks/antiKickMinDescent";
            case "vehicle_flight" -> "vehicleAirGraceTicks/boatAirGraceTicks/horseAirGraceTicks/vehicleFallTicksMax";
            case "air_flat_cruise" -> "antiKickWindowTicks";
            case "elytra_stall" -> "elytraStallTicks";
            case "elytra_rocket_speed" -> "elytraMaxRocketHorizontal";
            case "elytra_rocket_climb" -> "elytraMaxRocketUp";
            case "elytra_no_rocket_flat_cruise" -> "elytraNoRocketSustainableHorizontal";
            case "elytra_no_rocket_climb" -> "elytraNoRocketMaxAscent";
            case "elytra_no_rocket_climb_sustained" -> "elytraSustainedClimbTicksLimit";
            case "elytra_no_rocket_control_up" -> "elytraMaxNoRocketUp";
            case "elytra_motion", "elytra_durability_plus_motion" -> "elytraMovementBufferLimit";
            case "elytra_no_item" -> "elytraEnabled";
            default -> "-";
        };
    }

    private void broadcastRepeatOffender(Player violator, AntiFlyPlugin.PlayerState state) {
        String msg = ChatColor.RED + "[AntiFly] "
            + ChatColor.YELLOW + violator.getName()
            + ChatColor.GRAY + " has tripped "
            + ChatColor.WHITE + state.failCount + " flight checks"
            + ChatColor.GRAY + " in the last "
            + ChatColor.WHITE + (AntiFlyConstants.REPEAT_OFFENDER_WINDOW_MS / 60000L) + " minutes"
            + ChatColor.DARK_GRAY + " - inspect rather than assume one lag spike";
        plugin.getLogger().warning(violator.getName() + " tripped " + state.failCount
            + " flight checks within the repeat-offender window");
        if (plugin.getSettings().alertMode != AntiFlyPlugin.AlertMode.GAME
            && plugin.getSettings().alertMode != AntiFlyPlugin.AlertMode.BOTH) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(violator.getUniqueId())) {
                continue;
            }
            PlatformCompat.runForPlayer(plugin, viewer, () -> {
                if (viewer.isOp() || viewer.hasPermission("antifly.alerts")) {
                    viewer.sendMessage(msg);
                }
            });
        }
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
            // On Folia each viewer has to be messaged from the thread that owns
            // them; on Paper and Spigot this runs inline exactly as before.
            PlatformCompat.runForPlayer(plugin, viewer, () -> {
                if (viewer.isOp() || viewer.hasPermission("antifly.alerts")) {
                    viewer.sendMessage(msg);
                }
            });
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
        state.vehicleFallTicks = 0;
        state.vehicleFallHorizontalDistance = 0.0;
        state.impulseGraceTicks = 0;
        state.lastVelocity = null;
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
        PlatformCompat.sendActionBar(player, text);
    }

}
