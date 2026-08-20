package com.antifly.fabric;

import com.antifly.common.AntiFlyConstants;
import com.antifly.common.AttemptTracker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import java.util.EnumSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AntiFlyFabric implements ModInitializer {
    public static final String MOD_ID = "antifly";
    private static final long LOG_COOLDOWN_MS = 500;
    private static final Logger LOGGER = LoggerFactory.getLogger("AntiFly");
    private static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile("\"version_number\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DATE_PUBLISHED_PATTERN = Pattern.compile("\"date_published\"\\s*:\\s*\"([^\"]+)\"");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final AttemptTracker attemptTracker = new AttemptTracker();
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Set<UUID> exempt = ConcurrentHashMap.newKeySet();
    private final Set<UUID> debug = ConcurrentHashMap.newKeySet();
    private final AntiFlyConfig config = AntiFlyConfig.load();

    @Override
    public void onInitialize() {
        exempt.clear();
        for (String entry : config.exempt) {
            try {
                exempt.add(UUID.fromString(entry));
            } catch (IllegalArgumentException ignored) {
                LOGGER.warn("Invalid exempt UUID: {}", entry);
            }
        }

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                handlePlayerTick(player);
            }
        });
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            PlayerState state = states.computeIfAbsent(newPlayer.getUUID(), ignored -> new PlayerState());
            resetState(state, newPlayer.position());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUUID();
            states.remove(uuid);
            attemptTracker.reset(uuid);
            debug.remove(uuid);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("antifly")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                .then(Commands.literal("help").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly enable"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly disable"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly hungermode <on|off>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly status"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly alerts <off|game|console|both>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly debug <on|off>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly exempt <player>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly unexempt <player>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly disabledworlds <worldName> <true|false>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set <key> <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Primary keys: " + String.join(", ", SET_KEYS)), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Legacy aliases: " + String.join(", ", SET_ALIASES)), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly reset <player>"), false);
                    return 1;
                }))
                .then(Commands.literal("enable").executes(ctx -> {
                    config.enabled = true;
                    saveConfig();
                    ctx.getSource().sendSuccess(() -> Component.literal("AntiFly enabled."), false);
                    return 1;
                }))
                .then(Commands.literal("disable").executes(ctx -> {
                    config.enabled = false;
                    saveConfig();
                    ctx.getSource().sendSuccess(() -> Component.literal("AntiFly disabled."), false);
                    return 1;
                }))
                .then(Commands.literal("hungermode")
                    .then(Commands.literal("on").executes(ctx -> setHungerMode(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setHungerMode(ctx.getSource(), false)))
                    .executes(ctx -> {
                        ctx.getSource().sendSuccess(() -> Component.literal("Hunger Mode is " + (config.hungerModeEnabled ? "on" : "off")), false);
                        return 1;
                    }))
                .then(Commands.literal("status").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("AntiFly: " + (config.enabled ? "enabled" : "disabled")), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Hunger Mode: " + (config.hungerModeEnabled ? "enabled" : "disabled")), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Disabled worlds: "
                        + (config.disabledWorlds.isEmpty() ? "(none)" : String.join(", ", config.disabledWorlds))), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Alerts: mode=" + config.alertMode), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Ground/Fluid: groundWalkMax=" + config.groundWalkMax
                        + " groundMountedMax=" + config.groundMountedMax
                        + " waterMax=" + config.waterMax
                        + " waterVerticalMax=" + config.waterVerticalMax), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Air: maxAirHorizontal=" + config.airMax
                        + " maxAirVertical=" + config.airVerticalMax
                        + " noFallDetectionEnabled=" + config.noFallDetectionEnabled
                        + " airNonFallTicksLimit=" + config.airNonFallTicks
                        + " antiKickWindowTicks=" + config.antiKickWindowTicks
                        + " antiKickMinDescent=" + config.antiKickMinDescent), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Buffers/Setback: bufferDecay=" + config.bufferDecay
                        + " horizontalBufferLimit=" + config.horizontalBufferLimit
                        + " verticalBufferLimit=" + config.verticalBufferLimit
                        + " hoverBufferLimit=" + config.hoverBufferLimit
                        + " setbackCooldownMs=" + config.setbackCooldownMs), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Elytra: enabled=" + config.elytraChecksEnabled
                        + " boostGraceTicks=" + config.elytraBoostGraceTicks
                        + " stallTicks=" + config.elytraStallTicks
                        + " movementBufferLimit=" + config.elytraMovementBufferLimit
                        + " durabilityCheckEnabled=" + config.elytraDurabilityCheckEnabled), false);
                    checkModrinthVersion(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("alerts")
                    .then(Commands.literal("off").executes(ctx -> setAlertMode(ctx.getSource(), "off")))
                    .then(Commands.literal("game").executes(ctx -> setAlertMode(ctx.getSource(), "game")))
                    .then(Commands.literal("console").executes(ctx -> setAlertMode(ctx.getSource(), "console")))
                    .then(Commands.literal("both").executes(ctx -> setAlertMode(ctx.getSource(), "both")))
                    .executes(ctx -> {
                        ctx.getSource().sendSuccess(() -> Component.literal("Usage: /antifly alerts <off|game|console|both> (current=" + config.alertMode + ")"), false);
                        return 1;
                    }))
                .then(Commands.literal("debug")
                    .then(Commands.literal("on").executes(ctx -> setDebugMode(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setDebugMode(ctx.getSource(), false)))
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayer();
                        if (player == null) {
                            ctx.getSource().sendFailure(Component.literal("Usage: /antifly debug <on|off>"));
                            return 0;
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal("Debug is " + (isDebug(player) ? "on" : "off")), false);
                        ctx.getSource().sendSuccess(() -> Component.literal("Usage: /antifly debug <on|off>"), false);
                        return 1;
                    }))
                .then(Commands.literal("exempt")
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                            exempt.add(player.getUUID());
                            saveConfig();
                            ctx.getSource().sendSuccess(() -> Component.literal("Exempted " + player.getName().getString()), false);
                            return 1;
                        })))
                .then(Commands.literal("unexempt")
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                            exempt.remove(player.getUUID());
                            saveConfig();
                            ctx.getSource().sendSuccess(() -> Component.literal("Unexempted " + player.getName().getString()), false);
                            return 1;
                        })))
                .then(Commands.literal("disabledworlds")
                    .then(Commands.argument("worldName", StringArgumentType.word())
                        .then(Commands.literal("true").executes(ctx -> setDisabledWorld(ctx.getSource(),
                            StringArgumentType.getString(ctx, "worldName"), true)))
                        .then(Commands.literal("false").executes(ctx -> setDisabledWorld(ctx.getSource(),
                            StringArgumentType.getString(ctx, "worldName"), false))))
                    .executes(ctx -> {
                        ctx.getSource().sendFailure(Component.literal("Usage: /antifly disabledworlds <worldName> <true|false>"));
                        return 0;
                    }))
                .then(Commands.literal("reset")
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                            states.remove(player.getUUID());
                            attemptTracker.reset(player.getUUID());
                            ctx.getSource().sendSuccess(() -> Component.literal("Reset " + player.getName().getString()), false);
                            return 1;
                        })))
                .then(Commands.literal("set").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("AntiFly settings:"), false);
                    for (String key : SET_KEYS) {
                        String value = formatSettingValue(key);
                        if (value != null) {
                            ctx.getSource().sendSuccess(() -> Component.literal(key + "=" + value), false);
                        }
                    }
                    return 1;
                })                    .then(Commands.argument("key", StringArgumentType.word())
                        .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(SET_KEYS, builder))
                        .executes(ctx -> getValue(ctx.getSource(), StringArgumentType.getString(ctx, "key")))
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0))
                            .executes(ctx -> setValue(ctx.getSource(), StringArgumentType.getString(ctx, "key"), DoubleArgumentType.getDouble(ctx, "value"))))))
            );
        });

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (!modrinthCheckedOnStartup) {
                modrinthCheckedOnStartup = true;
                checkModrinthVersionAndAlertOps(server);
            }
        });
    }

    private volatile boolean modrinthCheckedOnStartup = false;

    private void applyHungerMode(ServerPlayer player, PlayerState state, Vec3 pos) {
        int fireworkUses = player.getStats().getValue(Stats.ITEM_USED.get(Items.FIREWORK_ROCKET));
        if (state.lastFireworkUses >= 0 && fireworkUses > state.lastFireworkUses && player.isFallFlying()) {
            state.rocketGraceTicks = config.hungerModeRocketGraceTicks;
        }
        state.lastFireworkUses = fireworkUses;

        double horizontal = state.lastPos == null ? 0.0 : horizontalDistance(state.lastPos, pos);
        double rawSpeedBps = horizontal * 20.0;
        // A vehicle only excuses the hover penalty when it is itself supported
        // (boat on water/ground, mount standing) - flying in a boat is
        // penalized like any other unsupported flight.
        boolean vehicleSupported = false;
        if (player.isPassenger()) {
            Entity vehicle = player.getVehicle();
            if (vehicle instanceof Boat boat) {
                vehicleSupported = isBoatInFluid(boat) || hasBoatGroundSupport(boat);
            } else {
                vehicleSupported = vehicle.onGround();
            }
        }
        boolean unsupported = !hasGroundSupport(player) && !isInFluid(player) && !vehicleSupported;
        double speedBlocksPerSecond = rawSpeedBps;
        if (unsupported) {
            speedBlocksPerSecond = Math.max(speedBlocksPerSecond, config.hungerModeAirborneMinimumBlocksPerSecond);
        }
        double normalizedSpeed = Math.min(1.0, speedBlocksPerSecond / config.hungerModeMaxBlocksPerSecond);
        double hungerLoss = config.hungerModeHungerPerSecondAtMaxSpeed * normalizedSpeed * normalizedSpeed / 20.0;

        boolean gliding = player.isFallFlying();
        boolean recentRocket = gliding && state.rocketGraceTicks > 0;
        if (recentRocket) {
            hungerLoss = 0.0;
        } else if (gliding && rawSpeedBps >= config.hungerModeAirborneMinimumBlocksPerSecond) {
            hungerLoss *= 0.5;
        }
        if (state.rocketGraceTicks > 0) {
            state.rocketGraceTicks--;
        }

        // Sustained unsupported flight deals real health damage after the
        // configured delay, so carrying stacks of food cannot sustain an
        // endless flight - food restores hunger, not the health being lost.
        if (config.hungerModeFlightDamageEnabled) {
            if (unsupported) {
                state.flightAirborneTicks++;
                int thresholdTicks = (int) Math.round(config.hungerModeFlightDamageAfterSeconds * 20.0);
                if (state.flightAirborneTicks > thresholdTicks) {
                    float dmg = (float) (config.hungerModeFlightDamagePerSecond / 20.0);
                    if (dmg > 0.0f) {
                        player.hurt(player.damageSources().starve(), dmg);
                    }
                }
            } else {
                state.flightAirborneTicks = 0;
            }
        } else {
            state.flightAirborneTicks = 0;
        }

        state.hungerDebt += hungerLoss;
        int wholeFoodPoints = (int) state.hungerDebt;
        if (wholeFoodPoints > 0) {
            player.getFoodData().setFoodLevel(Math.max(0, player.getFoodData().getFoodLevel() - wholeFoodPoints));
            state.hungerDebt -= wholeFoodPoints;
        }
    }

    private int setHungerMode(net.minecraft.commands.CommandSourceStack source, boolean enabled) {
        config.hungerModeEnabled = enabled;
        saveConfig();
        source.sendSuccess(() -> Component.literal("Hunger Mode " + (enabled ? "enabled: AntiFly checks are bypassed." : "disabled.")), false);
        return 1;
    }

    private void handlePlayerTick(ServerPlayer player) {
        PlayerState state = states.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        Vec3 pos = player.position();

        if (!player.isAlive()) {
            resetState(state, null);
            return;
        }

        // External teleports (commands, plugins, ender pearls, chorus fruit,
        // portals) look like instant flight to the checks below. A single jump
        // farther than any legitimate movement that the player's own velocity
        // cannot explain is re-baselined instead of rubber-banded. The
        // rebaseline NEVER moves the setback anchor to the destination unless
        // it is genuinely supported, so a flyer chaining huge jumps gets
        // corrected to real ground. A 20-tick cooldown (matches the vanilla
        // ender-pearl cooldown) means chained jumps are caught by the normal
        // checks instead of being forgiven forever.
        if (state.teleportGraceTicks > 0) {
            state.teleportGraceTicks--;
        }
        if (state.lastPos != null && state.teleportGraceTicks <= 0) {
            Vec3 delta = pos.subtract(state.lastPos);
            double distSq = delta.lengthSqr();
            if (distSq > 36.0) {
                Vec3 vel = player.getDeltaMovement();
                if (vel.lengthSqr() < distSq) {
                    state.teleportGraceTicks = 20;
                    rebaselineForTeleport(state, pos, hasGroundSupport(player) || isInFluid(player));
                    return;
                }
            }
        }

        if ((!config.enabled && !config.hungerModeEnabled) || isWorldDisabled(player.level())) {
            boolean inFluid = isInFluid(player);
            boolean serverOnGround = hasGroundSupport(player);
            resetTransientState(state);
            updateSupport(state, serverOnGround, inFluid, pos);
            state.lastPos = pos;
            state.lastServerOnGround = serverOnGround;
            state.wasGliding = false;
            state.hungerDebt = 0.0;
            state.rocketGraceTicks = 0;
            state.flightAirborneTicks = 0;
            return;
        }

        if (config.hungerModeEnabled) {
            applyHungerMode(player, state, pos);
            resetTransientState(state);
            state.lastPos = pos;
            state.lastServerOnGround = hasGroundSupport(player);
            state.wasGliding = player.isFallFlying();
            return;
        }

        // Hunger Mode is off: clear any accumulated hunger debt and rocket grace
        // so they cannot leak into a later Hunger Mode session. While Hunger Mode
        // is on, hungerDebt must persist across ticks so fractional food losses
        // accumulate into whole food points (otherwise hovering/slow flight
        // would never drain).
        state.hungerDebt = 0.0;
        state.rocketGraceTicks = 0;
        state.flightAirborneTicks = 0;

        boolean inFluid = isInFluid(player);
        boolean inVehicle = player.isPassenger();
        boolean clientOnGround = player.onGround();
        boolean serverOnGround = hasGroundSupport(player);
        boolean trustedGround = serverOnGround
            || (clientOnGround && hasGroundSupportLoose(player) && player.getDeltaMovement().y <= 0.05);
        boolean isGliding = player.isFallFlying();
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
        boolean boatOnSupport = false;
        if (inVehicle && player.getVehicle() instanceof Boat boat) {
            inBoatWater = boat.isInWater()
                || isBoatInFluid(boat)
                || boat.onGround()
                || !boat.level().getFluidState(boat.blockPosition()).isEmpty()
                || !boat.level().getFluidState(boat.blockPosition().below()).isEmpty();
            boatOnSupport = hasBoatGroundSupport(boat);
        }
        if (inBoatWater) {
            inFluid = true;
        }

        if (inVehicle && player.getVehicle() instanceof Boat boat && boatOnSupport) {
            inFluid = true;
        }

        if (inVehicle && !state.wasInVehicle) {
            state.vehicleGraceTicks = 20;
        }
        state.wasInVehicle = inVehicle;

        if (inVehicle && state.vehicleGraceTicks > 0) {
            state.vehicleGraceTicks--;
            state.vehicleAirTicks = 0;
            state.vehicleFallTicks = 0;
            state.vehicleFallHorizontalDistance = 0.0;
            state.airNonFallTicks = 0;
            state.airSessionTicks = 0;
            state.airSessionDescent = 0.0;
            state.lastPos = pos;
            return;
        }

        if (inVehicle && !serverOnGround && !inFluid) {
            Vec3 vel = player.getDeltaMovement();
            double deltaY = state.lastPos != null ? (pos.y - state.lastPos.y) : vel.y;
            double horizontal = state.lastPos != null ? horizontalDistance(state.lastPos, pos) : 0.0;
            horizontal = Math.max(horizontal, Math.sqrt(vel.x * vel.x + vel.z * vel.z));
            if (isVehicleNaturalFall(deltaY, vel.y, horizontal)) {
                state.vehicleFallTicks++;
                state.vehicleFallHorizontalDistance += horizontal;
                state.vehicleAirTicks = 0;
                double horizontalBudget = config.vehicleFallMaxHorizontal * Math.max(1, config.vehicleFallTicksMax);
                if (state.vehicleFallTicks <= config.vehicleFallTicksMax
                    && state.vehicleFallHorizontalDistance <= horizontalBudget) {
                    state.lastPos = pos;
                    state.lastServerOnGround = false;
                    return;
                }
            } else {
                state.vehicleFallTicks = 0;
                state.vehicleFallHorizontalDistance = 0.0;
            }

            state.vehicleAirTicks++;
            if (state.vehicleAirTicks > vehicleAirGraceTicks(player.getVehicle())) {
                Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                rubberBandVehicle(player, state, target, "vehicle_flight");
                state.lastPos = pos;
                state.wasGliding = false;
                return;
            }
        } else {
            state.vehicleAirTicks = 0;
            state.vehicleFallTicks = 0;
            state.vehicleFallHorizontalDistance = 0.0;
        }

        if (isExempt(player)) {
            state.airNonFallTicks = 0;
            state.airSessionTicks = 0;
            state.airSessionDescent = 0.0;
            state.sustainedAirTicks = 0;
            updateSupport(state, trustedGround, inFluid, pos);
            state.lastPos = pos;
            state.lastServerOnGround = serverOnGround;
            state.wasGliding = isGliding;
            return;
        }

        if (isGliding) {
            if (!handleGlide(player, state, pos, serverOnGround, inFluid, inVehicle)) {
                state.wasGliding = false;
                return;
            }
            state.airTicks++;
            state.airNonFallTicks = 0;
            state.airSessionTicks = 0;
            state.airSessionDescent = 0.0;
            state.sustainedAirTicks = 0;
            state.lastPos = pos;
            state.lastServerOnGround = serverOnGround;
            state.wasGliding = true;
            return;
        }

        if (trustedGround) {
            if (state.lastPos != null) {
                state.groundSpoofTicks = 0;
                Vec3 vel = player.getDeltaMovement();
                double deltaY = pos.y - state.lastPos.y;
                double horizontal = horizontalDistance(state.lastPos, pos);
                double maxAllowed = maxGroundSpeed(player);
                if (deltaY > 0.02 || vel.y > 0.05) {
                    maxAllowed *= 1.5;
                }
                sendDebugActionBar(player, state, "GROUND", horizontal, deltaY, maxAllowed, 0.0);
                // Reject fast movement that still reports airborne while collision support makes the position look grounded.
                if (!clientOnGround && horizontal > maxAllowed) {
                    Vec3 target = state.lastGroundPos != null ? state.lastGroundPos : pos;
                    rubberBand(player, state, target, "ground_spoof_speed", horizontal, maxAllowed);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
                if (state.glideGroundGraceTicks <= 0 && horizontal > maxAllowed) {
                    Vec3 target = state.lastGroundPos != null ? state.lastGroundPos : pos;
                    rubberBand(player, state, target, "ground_speed", horizontal, maxAllowed);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
            }
            updateSupport(state, true, false, pos);
        } else if (inFluid) {
            state.groundSpoofTicks = 0;
            Vec3 vel = player.getDeltaMovement();
            double horizontal = state.lastPos != null ? horizontalDistance(state.lastPos, pos) : 0.0;
            horizontal = Math.max(horizontal, Math.sqrt(vel.x * vel.x + vel.z * vel.z));
            double maxAllowed = maxWaterSpeed(player);
            double deltaY = state.lastPos != null ? (pos.y - state.lastPos.y) : 0.0;
            deltaY = Math.max(deltaY, vel.y);
            double maxUp = maxWaterVertical(player);
            sendDebugActionBar(player, state, "FLUID", horizontal, deltaY, maxAllowed, maxUp);
            if (horizontal > maxAllowed) {
                Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                rubberBand(player, state, target, "water_speed", horizontal, maxAllowed);
                state.lastPos = pos;
                state.wasGliding = false;
                return;
            }
            if (deltaY > maxUp) {
                Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                rubberBand(player, state, target, "water_vertical", deltaY, maxUp);
                state.lastPos = pos;
                state.wasGliding = false;
                return;
            }
            updateSupport(state, false, true, pos);
        } else if (!inFluid) {
            state.airTicks++;
            boolean graceAir = state.airTicks <= 4;
            double horizontal = 0.0;
            double maxAllowed = maxAirSpeed(player);
            if (state.lastPos != null) {
                Vec3 vel = player.getDeltaMovement();
                horizontal = Math.max(horizontalDistance(state.lastPos, pos), Math.sqrt(vel.x * vel.x + vel.z * vel.z));
                if (!graceAir && horizontal > maxAllowed) {
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                    rubberBand(player, state, target, "air_speed", horizontal, maxAllowed);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
            }
            Vec3 vel = player.getDeltaMovement();
            double deltaY = state.lastPos != null ? Math.max(pos.y - state.lastPos.y, vel.y) : vel.y;
            double hoverHorizontal = state.lastPos != null
                ? horizontalDistance(state.lastPos, pos)
                : Math.sqrt(vel.x * vel.x + vel.z * vel.z);
            double maxUp = maxAirVertical(player);
            sendDebugActionBar(player, state, "AIR", horizontal, deltaY, maxAllowed, maxUp);
            if (!graceAir && deltaY > maxUp) {
                Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                rubberBand(player, state, target, "air_vertical", deltaY, maxUp);
                state.lastPos = pos;
                state.wasGliding = false;
                return;
            }
            state.groundSpoofTicks = 0;
            // Ignore mid-air jump detection; we only care about prolonged hovering.
            boolean hoveringStill = !serverOnGround
                && state.airTicks > AntiFlyConstants.MAX_AIR_TICKS
                && Math.abs(deltaY) <= AntiFlyConstants.HOVER_DELTA_Y_EPSILON;
            if (hoveringStill) {
                state.hoverTicks++;
                if (state.hoverTicks > AntiFlyConstants.HOVER_TICKS) {
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                    rubberBand(player, state, target, "air_hover", deltaY, 0.0);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
            } else {
                state.hoverTicks = 0;
            }
            if (config.noFallDetectionEnabled && !serverOnGround && deltaY >= AntiFlyConstants.AIR_DESCENT_EPSILON) {
                state.airNonFallTicks++;
                if (state.airNonFallTicks > config.airNonFallTicks) {
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                    rubberBand(player, state, target, "air_time", state.airNonFallTicks, config.airNonFallTicks);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
            } else {
                state.airNonFallTicks = 0;
            }
            state.airSessionTicks++;
            if (deltaY < 0.0) {
                state.airSessionDescent += -deltaY;
            }
            boolean sustainedAirPlane = config.noFallDetectionEnabled
                && state.airSessionTicks >= 12
                && state.airSessionDescent < Math.max(0.15, config.antiKickMinDescent)
                && horizontal >= 0.45;
            if (sustainedAirPlane) {
                Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                rubberBand(player, state, target, "air_plane", state.airSessionDescent, config.antiKickMinDescent);
                state.lastPos = pos;
                state.wasGliding = false;
                return;
            }
            if (config.noFallDetectionEnabled && state.airSessionTicks >= config.antiKickWindowTicks) {
                if (state.airSessionDescent < config.antiKickMinDescent) {
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                    rubberBand(player, state, target, "air_antikick", state.airSessionDescent, config.antiKickMinDescent);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
                state.airSessionTicks = 0;
                state.airSessionDescent = 0.0;
            }
            if (!config.noFallDetectionEnabled) {
                state.airSessionTicks = 0;
                state.airSessionDescent = 0.0;
            }
            if (isVoidBelow(player) && !serverOnGround && !inFluid
                && deltaY >= AntiFlyConstants.AIR_DESCENT_EPSILON) {
                state.voidTicks++;
                if (state.voidTicks > AntiFlyConstants.VOID_FALL_TICKS) {
                    Vec3 fallback = Vec3.atCenterOf(player.blockPosition());
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : fallback;
                    rubberBand(player, state, target, "void_fall", 0.0, 0.0);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
            } else {
                state.voidTicks = 0;
            }
            state.sustainedAirTicks++;
            // Sustained air time catches level "bobbing" flight that evades the
            // speed, vertical, hover and no-fall checks by cruising under the
            // caps and resetting the descent counter on each bob. Only landing
            // (or fluid/vehicle support) resets this counter.
            if (state.sustainedAirTicks > config.sustainedAirTicksLimit) {
                Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                rubberBand(player, state, target, "air_sustained", state.sustainedAirTicks, config.sustainedAirTicksLimit);
                state.lastPos = pos;
                state.wasGliding = false;
                return;
            }
            // No generic "flight" rubberband; rely on hover/elytra checks only.
        }

        state.lastPos = pos;
        state.lastServerOnGround = serverOnGround;
        state.wasGliding = false;
    }

    private boolean isExempt(ServerPlayer player) {
        if (exempt.contains(player.getUUID())) {
            return true;
        }
        if (player.isCreative() || player.isSpectator()) {
            return true;
        }
        if (player.onClimbable()) {
            return true;
        }
        return player.hasEffect(MobEffects.LEVITATION)
            || player.hasEffect(MobEffects.SLOW_FALLING);
    }

    private double horizontalDistance(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double maxGroundSpeed(ServerPlayer player) {
        double max = player.isPassenger() ? config.groundMountedMax : config.groundWalkMax;
        if (player.isSprinting()) {
            max *= 1.3;
        }
        MobEffectInstance speed = player.getEffect(MobEffects.SPEED);
        if (speed != null) {
            max *= 1.0 + (0.2 * (speed.getAmplifier() + 1));
        }
        return max;
    }

    private double maxWaterSpeed(ServerPlayer player) {
        double max = config.waterMax;
        MobEffectInstance dolphins = player.getEffect(MobEffects.DOLPHINS_GRACE);
        if (dolphins != null) {
            max *= 1.0 + (0.3 * (dolphins.getAmplifier() + 1));
        }
        var depthStriderHolder = player.level().registryAccess()
            .lookupOrThrow(Registries.ENCHANTMENT)
            .getOrThrow(Enchantments.DEPTH_STRIDER);
        int depthStrider = EnchantmentHelper.getItemEnchantmentLevel(
            depthStriderHolder, player.getItemBySlot(EquipmentSlot.FEET));
        if (depthStrider > 0) {
            max *= 1.0 + (0.15 * depthStrider);
        }
        return max;
    }

    private double maxWaterVertical(ServerPlayer player) {
        double max = config.waterVerticalMax;
        MobEffectInstance dolphins = player.getEffect(MobEffects.DOLPHINS_GRACE);
        if (dolphins != null) {
            max *= 1.0 + (0.15 * (dolphins.getAmplifier() + 1));
        }
        return max;
    }

    private double maxAirSpeed(ServerPlayer player) {
        double max = config.airMax;
        MobEffectInstance speed = player.getEffect(MobEffects.SPEED);
        if (speed != null) {
            max *= 1.0 + (0.2 * (speed.getAmplifier() + 1));
        }
        return max;
    }

    private double maxAirVertical(ServerPlayer player) {
        double max = config.airVerticalMax;
        MobEffectInstance jump = player.getEffect(MobEffects.JUMP_BOOST);
        if (jump != null) {
            max *= 1.0 + (0.1 * (jump.getAmplifier() + 1));
        }
        return max;
    }

    private boolean handleGlide(ServerPlayer player, PlayerState state, Vec3 pos,
                                boolean serverOnGround, boolean inFluid, boolean inVehicle) {
        if (!config.elytraChecksEnabled) {
            state.glideStallTicks = 0;
            state.glideSlowdownGraceTicks = 0;
            state.lastGlideHorizontal = 0.0;
            return true;
        }
        if (inFluid || inVehicle || !hasElytraEquipped(player)) {
            return true;
        }
        if (!state.wasGliding) {
            state.glideSlowdownGraceTicks = config.elytraSlowdownGraceTicks;
            state.glideStallTicks = 0;
            state.lastGlideHorizontal = 0.0;
        }

        Vec3 vel = player.getDeltaMovement();
        double moveDeltaY = state.lastPos != null ? (pos.y - state.lastPos.y) : 0.0;
        double horizontal = state.lastPos != null ? horizontalDistance(state.lastPos, pos) : 0.0;
        double horizontalSpeed = Math.max(horizontal, Math.sqrt(vel.x * vel.x + vel.z * vel.z));

        if (horizontalSpeed > config.elytraMaxHorizontal) {
            Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
            rubberBand(player, state, target, "elytra_speed", horizontalSpeed, config.elytraMaxHorizontal);
            player.stopFallFlying();
            return false;
        }
        if (moveDeltaY > config.elytraMaxUp) {
            Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
            rubberBand(player, state, target, "elytra_up", moveDeltaY, config.elytraMaxUp);
            player.stopFallFlying();
            return false;
        }
        if (moveDeltaY < -config.elytraMaxDown) {
            Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
            rubberBand(player, state, target, "elytra_down", moveDeltaY, -config.elytraMaxDown);
            player.stopFallFlying();
            return false;
        }

        if (horizontal <= config.elytraStallHorizontalMax
            && Math.abs(moveDeltaY) <= config.elytraStallVerticalMax) {
            state.glideStallTicks++;
            if (state.glideStallTicks > config.elytraStallTicks) {
                Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                rubberBand(player, state, target, "elytra_stall", horizontal, config.elytraStallHorizontalMax);
                player.stopFallFlying();
                return false;
            }
        } else {
            state.glideStallTicks = 0;
        }

        if (state.glideSlowdownGraceTicks > 0) {
            state.glideSlowdownGraceTicks--;
        } else if (!serverOnGround
            && state.lastGlideHorizontal > config.elytraSlowdownMinSpeed
            && horizontalSpeed < state.lastGlideHorizontal * config.elytraSlowdownMinScale) {
            Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
            rubberBand(player, state, target, "elytra_slowdown", horizontalSpeed, state.lastGlideHorizontal);
            player.stopFallFlying();
            return false;
        }
        state.lastGlideHorizontal = horizontalSpeed;
        return true;
    }

    private boolean hasElytraEquipped(ServerPlayer player) {
        return player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private boolean isVoidBelow(ServerPlayer player) {
        ServerLevel level = getServerLevel(player);
        double voidY = minBuildHeight(level) - AntiFlyConstants.VOID_Y_OFFSET;
        return player.position().y < voidY;
    }

    private boolean hasGroundSupport(ServerPlayer player) {
        ServerLevel level = getServerLevel(player);
        AABB box = player.getBoundingBox();
        double minX = box.minX + AntiFlyConstants.SUPPORT_EPSILON;
        double maxX = box.maxX - AntiFlyConstants.SUPPORT_EPSILON;
        double minZ = box.minZ + AntiFlyConstants.SUPPORT_EPSILON;
        double maxZ = box.maxZ - AntiFlyConstants.SUPPORT_EPSILON;
        double y = box.minY - AntiFlyConstants.SUPPORT_EPSILON;
        int blockY = Mth.floor(y);
        if (blockY < minBuildHeight(level)) {
            return false;
        }
        if (hasSolidSupportAtY(level, minX, maxX, minZ, maxZ, blockY)) {
            return true;
        }
        int deepBlockY = Mth.floor(box.minY - AntiFlyConstants.SUPPORT_TALL_BLOCK_DEPTH);
        return deepBlockY != blockY && deepBlockY >= minBuildHeight(level)
            && hasSolidSupportAtY(level, minX, maxX, minZ, maxZ, deepBlockY);
    }

    private boolean hasBoatGroundSupport(Boat boat) {
        ServerLevel level = (ServerLevel) boat.level();
        AABB box = boat.getBoundingBox();
        double minX = box.minX + AntiFlyConstants.SUPPORT_EPSILON;
        double maxX = box.maxX - AntiFlyConstants.SUPPORT_EPSILON;
        double minZ = box.minZ + AntiFlyConstants.SUPPORT_EPSILON;
        double maxZ = box.maxZ - AntiFlyConstants.SUPPORT_EPSILON;
        int blockY = Mth.floor(box.minY - AntiFlyConstants.SUPPORT_EPSILON);
        return blockY >= minBuildHeight(level)
            && hasSolidSupportAtY(level, minX, maxX, minZ, maxZ, blockY);
    }

    private boolean hasGroundSupportLoose(ServerPlayer player) {
        ServerLevel level = getServerLevel(player);
        AABB box = player.getBoundingBox();
        double minX = box.minX + AntiFlyConstants.SUPPORT_EPSILON;
        double maxX = box.maxX - AntiFlyConstants.SUPPORT_EPSILON;
        double minZ = box.minZ + AntiFlyConstants.SUPPORT_EPSILON;
        double maxZ = box.maxZ - AntiFlyConstants.SUPPORT_EPSILON;
        double y = box.minY - AntiFlyConstants.SUPPORT_LOOSE_EPSILON;
        int blockY = Mth.floor(y);
        if (blockY < minBuildHeight(level)) {
            return false;
        }
        if (hasSolidSupportAtY(level, minX, maxX, minZ, maxZ, blockY)) {
            return true;
        }
        int deepBlockY = Mth.floor(box.minY - AntiFlyConstants.SUPPORT_TALL_BLOCK_DEPTH);
        return deepBlockY != blockY && deepBlockY >= minBuildHeight(level)
            && hasSolidSupportAtY(level, minX, maxX, minZ, maxZ, deepBlockY);
    }

    private boolean hasSolidSupportAtY(ServerLevel level, double minX, double maxX, double minZ, double maxZ, int blockY) {
        if (hasSolidAt(level, minX, blockY, minZ)) {
            return true;
        }
        if (hasSolidAt(level, maxX, blockY, minZ)) {
            return true;
        }
        if (hasSolidAt(level, minX, blockY, maxZ)) {
            return true;
        }
        return hasSolidAt(level, maxX, blockY, maxZ);
    }

    private static final Set<net.minecraft.world.level.block.Block> PARTIAL_GROUND_BLOCKS = Set.of(
        net.minecraft.world.level.block.Blocks.WHITE_BED, net.minecraft.world.level.block.Blocks.ORANGE_BED,
        net.minecraft.world.level.block.Blocks.MAGENTA_BED, net.minecraft.world.level.block.Blocks.LIGHT_BLUE_BED,
        net.minecraft.world.level.block.Blocks.YELLOW_BED, net.minecraft.world.level.block.Blocks.LIME_BED,
        net.minecraft.world.level.block.Blocks.PINK_BED, net.minecraft.world.level.block.Blocks.GRAY_BED,
        net.minecraft.world.level.block.Blocks.LIGHT_GRAY_BED, net.minecraft.world.level.block.Blocks.CYAN_BED,
        net.minecraft.world.level.block.Blocks.PURPLE_BED, net.minecraft.world.level.block.Blocks.BLUE_BED,
        net.minecraft.world.level.block.Blocks.BROWN_BED, net.minecraft.world.level.block.Blocks.GREEN_BED,
        net.minecraft.world.level.block.Blocks.RED_BED, net.minecraft.world.level.block.Blocks.BLACK_BED,
        net.minecraft.world.level.block.Blocks.DAYLIGHT_DETECTOR, net.minecraft.world.level.block.Blocks.LECTERN,
        net.minecraft.world.level.block.Blocks.ENCHANTING_TABLE, net.minecraft.world.level.block.Blocks.STONECUTTER,
        net.minecraft.world.level.block.Blocks.CONDUIT, net.minecraft.world.level.block.Blocks.CAKE
    );

    private boolean hasSolidAt(ServerLevel level, double x, int y, double z) {
        BlockPos pos = new BlockPos(Mth.floor(x), y, Mth.floor(z));
        BlockState state = level.getBlockState(pos);
        if (!state.getCollisionShape(level, pos).isEmpty()) {
            return true;
        }
        return PARTIAL_GROUND_BLOCKS.contains(state.getBlock());
    }

    private void rubberBand(ServerPlayer player, PlayerState state, Vec3 target, String reason,
                            double actual, double allowed) {
        long now = System.currentTimeMillis();
        if (now - state.lastRubberBandAtMs > LOG_COOLDOWN_MS) {
            int count = attemptTracker.record(player.getUUID());
            String line = String.format(
                "Blocked %s for %s (%s) count=%d loc=%s,%s,%s actual=%s allowed=%s",
                reason,
                player.getName().getString(),
                player.getUUID(),
                count,
                String.format("%.2f", target.x), String.format("%.2f", target.y), String.format("%.2f", target.z),
                String.format("%.3f", actual),
                String.format("%.3f", allowed)
            );
            if (isAlertConsole()) {
                LOGGER.info(line);
            }
            if (isAlertGame()) {
                broadcastFabricAlert(player, reason, actual, allowed);
            }
            state.lastRubberBandAtMs = now;
        }

        player.teleportTo(
            getServerLevel(player),
            target.x, target.y, target.z,
            java.util.EnumSet.noneOf(net.minecraft.world.entity.Relative.class),
            player.getYRot(),
            player.getXRot(),
            false
        );
        player.setDeltaMovement(0.0, 0.0, 0.0);
        state.airTicks = 0;
        state.lastPos = target;
        state.lastServerOnGround = hasGroundSupport(player);
    }

    private void rubberBandVehicle(ServerPlayer player, PlayerState state, Vec3 target, String reason) {
        if (player.getVehicle() != null) {
            player.getVehicle().setDeltaMovement(0.0, 0.0, 0.0);
            player.getVehicle().teleportTo(target.x, target.y, target.z);
        }
        rubberBand(player, state, target, reason, 0.0, 0.0);
    }

    private ServerLevel getServerLevel(ServerPlayer player) {
        return (ServerLevel) player.level();
    }

    private boolean isInFluid(ServerPlayer player) {
        Level level = player.level();
        BlockPos pos = player.blockPosition();
        return isFluidAt(level, pos) || isFluidAt(level, pos.above());
    }

    private boolean isBoatInFluid(Boat boat) {
        Level level = boat.level();
        BlockPos pos = boat.blockPosition();
        return isFluidAt(level, pos) || isFluidAt(level, pos.below());
    }

    private int vehicleAirGraceTicks(Entity vehicle) {
        EntityType<?> type = vehicle.getType();
        if (type == EntityType.BOAT) {
            return 12;
        }
        if (type == EntityType.HORSE
            || type == EntityType.DONKEY
            || type == EntityType.MULE
            || type == EntityType.SKELETON_HORSE
            || type == EntityType.ZOMBIE_HORSE
            || type == EntityType.CAMEL
            || type == EntityType.LLAMA
            || type == EntityType.TRADER_LLAMA) {
            return AntiFlyConstants.VEHICLE_AIR_GRACE_TICKS_HORSE;
        }
        return AntiFlyConstants.VEHICLE_AIR_GRACE_TICKS;
    }

    private int setAlertMode(net.minecraft.commands.CommandSourceStack source, String mode) {
        config.alertMode = mode;
        saveConfig();
        source.sendSuccess(() -> Component.literal("Alert mode set to " + mode), false);
        return 1;
    }

    private int setDisabledWorld(net.minecraft.commands.CommandSourceStack source, String worldName, boolean disabled) {
        String normalized = worldName == null ? "" : worldName.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            source.sendFailure(Component.literal("World name cannot be empty."));
            return 0;
        }
        java.util.LinkedHashSet<String> worlds = new java.util.LinkedHashSet<>(config.disabledWorlds);
        if (disabled) {
            worlds.add(normalized);
        } else {
            worlds.remove(normalized);
        }
        config.disabledWorlds = worlds.stream().sorted().toList();
        saveConfig();
        source.sendSuccess(() -> Component.literal("World " + worldName + " is now "
            + (disabled ? "disabled" : "enabled") + " for AntiFly."), false);
        return 1;
    }

    private int setDebugMode(net.minecraft.commands.CommandSourceStack source, boolean enabled) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Usage: /antifly debug <on|off>"));
            return 0;
        }
        if (enabled) {
            debug.add(player.getUUID());
            source.sendSuccess(() -> Component.literal("AntiFly action-bar debug enabled."), false);
        } else {
            debug.remove(player.getUUID());
            source.sendSuccess(() -> Component.literal("AntiFly action-bar debug disabled."), false);
        }
        return 1;
    }

    private boolean isDebug(ServerPlayer player) {
        return debug.contains(player.getUUID());
    }

    private void sendDebugActionBar(ServerPlayer player, PlayerState state, String mode,
                                    double horizontal, double deltaY, double hLimit, double vLimit) {
        if (!isDebug(player)) {
            return;
        }
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
            config.noFallDetectionEnabled,
            state.airSessionTicks,
            config.antiKickWindowTicks
        );
        player.sendSystemMessage(Component.literal(text));
    }

    private boolean isAlertConsole() {
        return "console".equalsIgnoreCase(config.alertMode) || "both".equalsIgnoreCase(config.alertMode);
    }

    private boolean isAlertGame() {
        return "game".equalsIgnoreCase(config.alertMode) || "both".equalsIgnoreCase(config.alertMode);
    }

    private void broadcastFabricAlert(ServerPlayer violator, String reason, double actual, double allowed) {
        String msg = String.format("[AntiFly] %s blocked %s (actual=%.3f allowed=%.3f)",
            violator.getName().getString(), reason, actual, allowed);
        net.minecraft.server.MinecraftServer server = violator.level().getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (viewer.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_MODERATOR)) {
                viewer.sendSystemMessage(Component.literal(msg));
            }
        }
    }

    private boolean isVehicleNaturalFall(double deltaY, double velocityY, double horizontal) {
        return deltaY <= config.vehicleFallMinDescent
            && horizontal <= config.vehicleFallMaxHorizontal;
    }

    private boolean isFluidAt(Level level, BlockPos pos) {
        FluidState fluidState = level.getFluidState(pos);
        return fluidState.is(FluidTags.WATER) || fluidState.is(FluidTags.LAVA);
    }

    private int minBuildHeight(Level level) {
        return ((LevelHeightAccessor) level).getMinY();
    }

    private void resetState(PlayerState state, Vec3 pos) {
        state.airTicks = 0;
        state.airNonFallTicks = 0;
        state.airSessionTicks = 0;
        state.airSessionDescent = 0.0;
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
        state.vehicleAirTicks = 0;
        state.vehicleFallTicks = 0;
        state.vehicleFallHorizontalDistance = 0.0;
        state.wasInVehicle = false;
        state.sustainedAirTicks = 0;
        if (pos != null) {
            state.lastGroundPos = pos;
            state.lastSupportPos = pos;
            state.lastPos = pos;
            state.lastServerOnGround = true;
        }
    }

    /**
     * Lightweight rebaseline for legitimate teleports. Resets the movement
     * counters and baseline so the displacement is not treated as flight, but
     * NEVER moves the setback anchors (lastGroundPos/lastSupportPos) to an
     * unsupported destination. Only genuinely supported destinations update
     * the anchors, so a flyer chaining teleport-sized jumps is always
     * corrected back to real ground.
     */
    private void rebaselineForTeleport(PlayerState state, Vec3 pos, boolean supported) {
        state.lastPos = pos;
        state.airTicks = 0;
        state.sustainedAirTicks = 0;
        state.airNonFallTicks = 0;
        state.airSessionTicks = 0;
        state.airSessionDescent = 0.0;
        state.hoverTicks = 0;
        state.voidTicks = 0;
        state.groundSpoofTicks = 0;
        state.glideGroundGraceTicks = 0;
        state.glideSlowdownGraceTicks = 0;
        state.lastGlideHorizontal = 0.0;
        state.wasGliding = false;
        state.vehicleFallTicks = 0;
        state.vehicleFallHorizontalDistance = 0.0;
        state.lastServerOnGround = supported;
        if (supported) {
            state.lastGroundPos = pos;
            state.lastSupportPos = pos;
        }
    }

    private void updateSupport(PlayerState state, boolean onGround, boolean inFluid, Vec3 pos) {
        if (onGround) {
            state.lastGroundPos = pos;
            state.lastSupportPos = pos;
            state.airTicks = 0;
            state.airNonFallTicks = 0;
            state.airSessionTicks = 0;
            state.airSessionDescent = 0.0;
            state.hoverTicks = 0;
            state.voidTicks = 0;
            state.groundSpoofTicks = 0;
            state.vehicleAirTicks = 0;
            state.vehicleFallTicks = 0;
            state.vehicleFallHorizontalDistance = 0.0;
            state.lastServerOnGround = true;
            state.sustainedAirTicks = 0;
        } else if (inFluid) {
            state.lastSupportPos = pos;
            state.sustainedAirTicks = 0;
            state.airTicks = 0;
            state.airNonFallTicks = 0;
            state.airSessionTicks = 0;
            state.airSessionDescent = 0.0;
            state.hoverTicks = 0;
            state.voidTicks = 0;
            state.groundSpoofTicks = 0;
            state.vehicleAirTicks = 0;
            state.vehicleFallTicks = 0;
            state.vehicleFallHorizontalDistance = 0.0;
            state.lastServerOnGround = false;
        }
    }

    private int setValue(net.minecraft.commands.CommandSourceStack source, String key, double value) {
        switch (normalizeSettingKey(key)) {
            case "groundSpeed", "groundSpeedWalking" -> config.groundWalkMax = value;
            case "groundSpeedMounted" -> config.groundMountedMax = value;
            case "vehicleFallMinDescent" -> config.vehicleFallMinDescent = value;
            case "vehicleFallMaxHorizontal" -> config.vehicleFallMaxHorizontal = value;
            case "vehicleFallTicksMax" -> config.vehicleFallTicksMax = (int) Math.round(value);
            case "airSpeed" -> config.airMax = value;
            case "airVertical" -> config.airVerticalMax = value;
            case "bufferDecay" -> config.bufferDecay = value;
            case "horizontalBufferLimit" -> config.horizontalBufferLimit = value;
            case "verticalBufferLimit" -> config.verticalBufferLimit = value;
            case "hoverBufferLimit" -> config.hoverBufferLimit = value;
            case "setbackCooldownMs" -> config.setbackCooldownMs = (long) Math.round(value);
            case "noFallDetectionEnabled" -> config.noFallDetectionEnabled = value > 0.5;
            case "airNonFallTicks" -> config.airNonFallTicks = (int) Math.round(value);
            case "antiKickWindowTicks" -> config.antiKickWindowTicks = (int) Math.round(value);
            case "antiKickMinDescent" -> config.antiKickMinDescent = value;
            case "waterSpeed" -> config.waterMax = value;
            case "waterVertical" -> config.waterVerticalMax = value;
            case "elytraEnabled" -> config.elytraChecksEnabled = value > 0.5;
            case "elytraMaxHorizontal" -> config.elytraMaxHorizontal = value;
            case "elytraMaxUp" -> config.elytraMaxUp = value;
            case "elytraMaxDown" -> config.elytraMaxDown = value;
            case "elytraStallHorizontalMax" -> config.elytraStallHorizontalMax = value;
            case "elytraStallVerticalMax" -> config.elytraStallVerticalMax = value;
            case "elytraStallTicks" -> config.elytraStallTicks = (int) Math.round(value);
            case "elytraBoostGraceTicks" -> config.elytraBoostGraceTicks = (int) Math.round(value);
            case "elytraMovementBufferLimit" -> config.elytraMovementBufferLimit = value;
            case "elytraDurabilityCheckEnabled" -> config.elytraDurabilityCheckEnabled = value > 0.5;
            case "elytraSlowdownMinSpeed" -> config.elytraSlowdownMinSpeed = value;
            case "elytraSlowdownMinScale" -> config.elytraSlowdownMinScale = value;
            case "elytraSlowdownGraceTicks" -> config.elytraSlowdownGraceTicks = (int) Math.round(value);
            case "hungerModeMaxBlocksPerSecond" -> config.hungerModeMaxBlocksPerSecond = Math.max(1.0, value);
            case "hungerModeHungerPerSecondAtMaxSpeed" -> config.hungerModeHungerPerSecondAtMaxSpeed = Math.max(0.0, value);
            case "hungerModeRocketGraceTicks" -> config.hungerModeRocketGraceTicks = Math.max(0, (int) Math.round(value));
            case "hungerModeAirborneMinimumBlocksPerSecond" -> config.hungerModeAirborneMinimumBlocksPerSecond = Math.max(0.0, value);
            case "hungerModeFlightDamageEnabled" -> config.hungerModeFlightDamageEnabled = value > 0.5;
            case "hungerModeFlightDamageAfterSeconds" -> config.hungerModeFlightDamageAfterSeconds = Math.max(0.0, value);
            case "hungerModeFlightDamagePerSecond" -> config.hungerModeFlightDamagePerSecond = Math.max(0.0, value);
            case "sustainedAirTicksLimit" -> config.sustainedAirTicksLimit = (int) Math.round(value);
            default -> {
                source.sendFailure(Component.literal("Unknown key."));
                return 0;
            }
        }
        saveConfig();
        source.sendSuccess(() -> Component.literal("Set " + key + " to " + value), false);
        return 1;
    }

    private int getValue(net.minecraft.commands.CommandSourceStack source, String key) {
        String value = formatSettingValue(normalizeSettingKey(key));
        if (value == null) {
            source.sendFailure(Component.literal("Unknown key."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(key + "=" + value), false);
        return 1;
    }

    private com.mojang.brigadier.builder.ArgumentBuilder<net.minecraft.commands.CommandSourceStack, ?> settingNode(String key) {
        return Commands.literal(key)
            .executes(ctx -> getValue(ctx.getSource(), key))
            .then(Commands.argument("value", DoubleArgumentType.doubleArg(0))
                .executes(ctx -> setValue(ctx.getSource(), key, DoubleArgumentType.getDouble(ctx, "value"))));
    }

    private String formatSettingValue(String key) {
        return switch (key) {
            case "groundWalkMax" -> String.valueOf(config.groundWalkMax);
            case "groundMountedMax" -> String.valueOf(config.groundMountedMax);
            case "waterMax" -> String.valueOf(config.waterMax);
            case "waterVerticalMax" -> String.valueOf(config.waterVerticalMax);
            case "maxAirHorizontal" -> String.valueOf(config.airMax);
            case "maxAirVertical" -> String.valueOf(config.airVerticalMax);
            case "bufferDecay" -> String.valueOf(config.bufferDecay);
            case "horizontalBufferLimit" -> String.valueOf(config.horizontalBufferLimit);
            case "verticalBufferLimit" -> String.valueOf(config.verticalBufferLimit);
            case "hoverBufferLimit" -> String.valueOf(config.hoverBufferLimit);
            case "noFallDetectionEnabled" -> String.valueOf(config.noFallDetectionEnabled);
            case "airNonFallTicksLimit" -> String.valueOf(config.airNonFallTicks);
            case "setbackCooldownMs" -> String.valueOf(config.setbackCooldownMs);
            case "elytraBoostGraceTicks" -> String.valueOf(config.elytraBoostGraceTicks);
            case "elytraMovementBufferLimit" -> String.valueOf(config.elytraMovementBufferLimit);
            case "elytraDurabilityCheckEnabled" -> String.valueOf(config.elytraDurabilityCheckEnabled);
            case "groundSpeed", "groundSpeedWalking" -> String.valueOf(config.groundWalkMax);
            case "groundSpeedMounted" -> String.valueOf(config.groundMountedMax);
            case "vehicleFallMinDescent" -> String.valueOf(config.vehicleFallMinDescent);
            case "vehicleFallMaxHorizontal" -> String.valueOf(config.vehicleFallMaxHorizontal);
            case "vehicleFallTicksMax" -> String.valueOf(config.vehicleFallTicksMax);
            case "airSpeed" -> String.valueOf(config.airMax);
            case "airVertical" -> String.valueOf(config.airVerticalMax);
            case "airNonFallTicks" -> String.valueOf(config.airNonFallTicks);
            case "antiKickWindowTicks" -> String.valueOf(config.antiKickWindowTicks);
            case "antiKickMinDescent" -> String.valueOf(config.antiKickMinDescent);
            case "waterSpeed" -> String.valueOf(config.waterMax);
            case "waterVertical" -> String.valueOf(config.waterVerticalMax);
            case "elytraEnabled" -> String.valueOf(config.elytraChecksEnabled);
            case "elytraMaxHorizontal" -> String.valueOf(config.elytraMaxHorizontal);
            case "elytraMaxUp" -> String.valueOf(config.elytraMaxUp);
            case "elytraMaxDown" -> String.valueOf(config.elytraMaxDown);
            case "elytraStallHorizontalMax" -> String.valueOf(config.elytraStallHorizontalMax);
            case "elytraStallVerticalMax" -> String.valueOf(config.elytraStallVerticalMax);
            case "elytraStallTicks" -> String.valueOf(config.elytraStallTicks);
            case "elytraSlowdownMinSpeed" -> String.valueOf(config.elytraSlowdownMinSpeed);
            case "elytraSlowdownMinScale" -> String.valueOf(config.elytraSlowdownMinScale);
            case "elytraSlowdownGraceTicks" -> String.valueOf(config.elytraSlowdownGraceTicks);
            case "hungerModeMaxBlocksPerSecond" -> String.valueOf(config.hungerModeMaxBlocksPerSecond);
            case "hungerModeHungerPerSecondAtMaxSpeed" -> String.valueOf(config.hungerModeHungerPerSecondAtMaxSpeed);
            case "hungerModeRocketGraceTicks" -> String.valueOf(config.hungerModeRocketGraceTicks);
            case "hungerModeAirborneMinimumBlocksPerSecond" -> String.valueOf(config.hungerModeAirborneMinimumBlocksPerSecond);
            case "hungerModeFlightDamageEnabled" -> String.valueOf(config.hungerModeFlightDamageEnabled);
            case "hungerModeFlightDamageAfterSeconds" -> String.valueOf(config.hungerModeFlightDamageAfterSeconds);
            case "hungerModeFlightDamagePerSecond" -> String.valueOf(config.hungerModeFlightDamagePerSecond);
            case "sustainedAirTicksLimit" -> String.valueOf(config.sustainedAirTicksLimit);
            default -> null;
        };
    }

    private static final java.util.List<String> SET_KEYS = java.util.List.of(
        "groundWalkMax",
        "groundMountedMax",
        "waterMax",
        "waterVerticalMax",
        "maxAirHorizontal",
        "maxAirVertical",
        "bufferDecay",
        "horizontalBufferLimit",
        "verticalBufferLimit",
        "hoverBufferLimit",
        "noFallDetectionEnabled",
        "airNonFallTicksLimit",
        "setbackCooldownMs",
        "elytraBoostGraceTicks",
        "elytraMovementBufferLimit",
        "elytraDurabilityCheckEnabled",
        "antiKickWindowTicks",
        "antiKickMinDescent",
        "vehicleFallMinDescent",
        "vehicleFallMaxHorizontal",
        "vehicleFallTicksMax",
        "elytraEnabled",
        "elytraMaxHorizontal",
        "elytraMaxUp",
        "elytraMaxDown",
        "elytraStallHorizontalMax",
        "elytraStallVerticalMax",
        "elytraStallTicks",
        "elytraSlowdownMinSpeed",
        "elytraSlowdownMinScale",
        "elytraSlowdownGraceTicks",
        "hungerModeMaxBlocksPerSecond",
        "hungerModeHungerPerSecondAtMaxSpeed",
        "hungerModeRocketGraceTicks",
        "hungerModeAirborneMinimumBlocksPerSecond",
        "hungerModeFlightDamageEnabled",
        "hungerModeFlightDamageAfterSeconds",
        "hungerModeFlightDamagePerSecond",
        "sustainedAirTicksLimit"
    );
    private static final java.util.List<String> SET_ALIASES = java.util.List.of(
        "groundSpeed", "groundSpeedWalking", "groundSpeedMounted",
        "waterSpeed", "waterVertical",
        "airSpeed", "airVertical", "airNonFallTicks", "elytraMovementLimit"
    );

    private String normalizeSettingKey(String key) {
        return switch (key) {
            case "groundWalkMax" -> "groundSpeedWalking";
            case "groundMountedMax" -> "groundSpeedMounted";
            case "waterMax" -> "waterSpeed";
            case "waterVerticalMax" -> "waterVertical";
            case "maxAirHorizontal" -> "airSpeed";
            case "maxAirVertical" -> "airVertical";
            case "airNonFallTicksLimit" -> "airNonFallTicks";
            case "elytraMovementLimit" -> "elytraMovementBufferLimit";
            case "hunger_mode_max_blocks_per_second", "hungerModeMaxBps" -> "hungerModeMaxBlocksPerSecond";
            case "hunger_mode_hunger_per_second_at_max_speed", "hungerModeHungerPerSecond" -> "hungerModeHungerPerSecondAtMaxSpeed";
            case "hunger_mode_rocket_grace_ticks" -> "hungerModeRocketGraceTicks";
            case "hunger_mode_airborne_minimum_blocks_per_second", "hungerModeAirborneMinBps" -> "hungerModeAirborneMinimumBlocksPerSecond";
            case "hunger_mode_flight_damage_enabled" -> "hungerModeFlightDamageEnabled";
            case "hunger_mode_flight_damage_after_seconds" -> "hungerModeFlightDamageAfterSeconds";
            case "hunger_mode_flight_damage_per_second" -> "hungerModeFlightDamagePerSecond";
            case "sustained_air_ticks_limit" -> "sustainedAirTicksLimit";
            default -> key;
        };
    }

    private boolean isWorldDisabled(ServerLevel level) {
        return level != null && isWorldDisabled(level.dimension().identifier().getPath());
    }

    private boolean isWorldDisabled(String worldName) {
        if (worldName == null) {
            return false;
        }
        return config.disabledWorlds.contains(worldName.trim().toLowerCase(Locale.ROOT));
    }

    private void resetTransientState(PlayerState state) {
        state.airHorizontalBuffer = 0.0;
        state.airVerticalBuffer = 0.0;
        state.hoverBuffer = 0.0;
        state.antiKickBuffer = 0.0;
        state.elytraMovementBuffer = 0.0;
        state.airTicks = 0;
        state.sustainedAirTicks = 0;
        state.airNonFallTicks = 0;
        state.airSessionTicks = 0;
        state.airSessionDescent = 0.0;
        state.hoverTicks = 0;
        state.voidTicks = 0;
        state.glideStallTicks = 0;
        state.groundSpoofTicks = 0;
        state.glideGroundGraceTicks = 0;
        state.glideSlowdownGraceTicks = 0;
        state.lastGlideHorizontal = 0.0;
        state.vehicleAirTicks = 0;
        state.vehicleFallTicks = 0;
        state.vehicleFallHorizontalDistance = 0.0;
    }

    private void saveConfig() {
        config.exempt = exempt.stream().map(UUID::toString).sorted().toList();
        config.disabledWorlds = config.disabledWorlds.stream()
            .map(name -> name == null ? "" : name.trim().toLowerCase(Locale.ROOT))
            .filter(name -> !name.isEmpty())
            .distinct()
            .sorted()
            .toList();
        config.save();
    }

    private void checkModrinthVersion(net.minecraft.commands.CommandSourceStack source) {
        String currentVersion = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(mc -> mc.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
        source.sendSuccess(() -> Component.literal("Checking Modrinth for " + config.modrinthProjectSlug + " ..."), false);
        fetchLatestModrinthVersion(config.modrinthProjectSlug).thenAccept(result -> {
            Runnable send = () -> {
                if (!result.ok) {
                    source.sendFailure(Component.literal("Modrinth check failed: " + result.error));
                    return;
                }
                int cmp = compareVersions(currentVersion, result.latestVersion);
                if (cmp < 0) {
                    source.sendSuccess(() -> Component.literal("Outdated: running " + currentVersion + ", Modrinth has " + result.latestVersion), false);
                } else if (cmp > 0) {
                    source.sendSuccess(() -> Component.literal("Ahead of Modrinth: running " + currentVersion + ", latest hosted is " + result.latestVersion), false);
                } else {
                    source.sendSuccess(() -> Component.literal("Up to date with Modrinth: " + currentVersion), false);
                }
            };
            if (source.getServer() != null) {
                source.getServer().execute(send);
            } else {
                send.run();
            }
        });
    }

    private void checkModrinthVersionAndAlertOps(net.minecraft.server.MinecraftServer server) {
        String currentVersion = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(mc -> mc.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
        fetchLatestModrinthVersion(config.modrinthProjectSlug).thenAccept(result -> server.execute(() -> {
            if (!result.ok) {
                LOGGER.warn("Modrinth version check failed: {}", result.error);
                return;
            }
            int cmp = compareVersions(currentVersion, result.latestVersion);
            if (cmp >= 0) {
                return;
            }
            String msg = "AntiFly is outdated: running " + currentVersion + ", Modrinth has " + result.latestVersion;
            LOGGER.warn(msg);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_MODERATOR)) {
                    player.sendSystemMessage(Component.literal(msg));
                }
            }
        }));
    }

    private CompletableFuture<VersionResult> fetchLatestModrinthVersion(String projectSlug) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String encodedSlug = URLEncoder.encode(projectSlug, StandardCharsets.UTF_8);
                URI uri = URI.create("https://api.modrinth.com/v2/project/" + encodedSlug + "/version?featured=true&include_changelog=false");
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "AntiFly/" + MOD_ID + " (version-check)")
                    .build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    return VersionResult.error("HTTP " + response.statusCode());
                }
                return parseLatestVersion(response.body());
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                return VersionResult.error(error);
            } catch (RuntimeException ex) {
                String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                return VersionResult.error(error);
            }
        });
    }

    private VersionResult parseLatestVersion(String body) {
        Matcher versionMatcher = VERSION_NUMBER_PATTERN.matcher(body);
        Matcher dateMatcher = DATE_PUBLISHED_PATTERN.matcher(body);
        String latestVersion = null;
        String latestDate = "";
        while (versionMatcher.find()) {
            String version = versionMatcher.group(1);
            String date = dateMatcher.find() ? dateMatcher.group(1) : "";
            if (latestVersion == null || date.compareTo(latestDate) > 0) {
                latestVersion = version;
                latestDate = date;
            }
        }
        if (latestVersion == null) {
            return VersionResult.error("No versions found on Modrinth");
        }
        return VersionResult.ok(latestVersion);
    }

    private int compareVersions(String local, String remote) {
        String[] localParts = local.split("[^A-Za-z0-9]+");
        String[] remoteParts = remote.split("[^A-Za-z0-9]+");
        int len = Math.max(localParts.length, remoteParts.length);
        for (int i = 0; i < len; i++) {
            String a = i < localParts.length ? localParts[i] : "0";
            String b = i < remoteParts.length ? remoteParts[i] : "0";
            int cmp;
            if (isDigits(a) && isDigits(b)) {
                cmp = Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            } else {
                cmp = a.compareToIgnoreCase(b);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    private static final class VersionResult {
        final boolean ok;
        final String latestVersion;
        final String error;

        private VersionResult(boolean ok, String latestVersion, String error) {
            this.ok = ok;
            this.latestVersion = latestVersion;
            this.error = error;
        }

        static VersionResult ok(String latestVersion) {
            return new VersionResult(true, latestVersion, null);
        }

        static VersionResult error(String error) {
            return new VersionResult(false, null, error);
        }
    }

    private static final class AntiFlyConfig {
        boolean enabled = true;
        boolean hungerModeEnabled = false;
        double hungerModeMaxBlocksPerSecond = 200.0;
        double hungerModeHungerPerSecondAtMaxSpeed = 10.0;
        int hungerModeRocketGraceTicks = 80;
        double hungerModeAirborneMinimumBlocksPerSecond = 20.0;
        boolean hungerModeFlightDamageEnabled = true;
        double hungerModeFlightDamageAfterSeconds = 30.0;
        double hungerModeFlightDamagePerSecond = 1.0;
        double groundWalkMax = AntiFlyConstants.DEFAULT_GROUND_WALK_MAX;
        double groundMountedMax = AntiFlyConstants.DEFAULT_GROUND_MOUNT_MAX;
        double vehicleFallMinDescent = AntiFlyConstants.VEHICLE_FALL_MIN_DESCENT;
        double vehicleFallMaxHorizontal = AntiFlyConstants.VEHICLE_FALL_MAX_HORIZONTAL;
        int vehicleFallTicksMax = AntiFlyConstants.VEHICLE_FALL_TICKS_MAX;
        String modrinthProjectSlug = "antiflight";
        double airMax = AntiFlyConstants.DEFAULT_AIR_MAX;
        double airVerticalMax = AntiFlyConstants.DEFAULT_AIR_VERTICAL_MAX;
        double bufferDecay = 0.25;
        double horizontalBufferLimit = 3.0;
        double verticalBufferLimit = 2.0;
        double hoverBufferLimit = 3.0;
        boolean noFallDetectionEnabled = true;
        String alertMode = "both";
        long setbackCooldownMs = 500L;
        int airNonFallTicks = AntiFlyConstants.AIR_NON_FALL_TICKS;
        int sustainedAirTicksLimit = 150;
        int antiKickWindowTicks = AntiFlyConstants.ANTI_KICK_WINDOW_TICKS;
        double antiKickMinDescent = AntiFlyConstants.ANTI_KICK_MIN_DESCENT;
        double waterMax = AntiFlyConstants.DEFAULT_WATER_MAX;
        double waterVerticalMax = AntiFlyConstants.DEFAULT_WATER_VERTICAL_MAX;
        boolean elytraChecksEnabled = true;
        double elytraMaxHorizontal = AntiFlyConstants.ELYTRA_MAX_HORIZONTAL;
        double elytraMaxUp = AntiFlyConstants.ELYTRA_MAX_UP;
        double elytraMaxDown = AntiFlyConstants.ELYTRA_MAX_DOWN;
        double elytraStallHorizontalMax = AntiFlyConstants.ELYTRA_STALL_HORIZONTAL_MAX;
        double elytraStallVerticalMax = AntiFlyConstants.ELYTRA_STALL_VERTICAL_MAX;
        int elytraStallTicks = AntiFlyConstants.ELYTRA_STALL_TICKS;
        int elytraBoostGraceTicks = 80;
        double elytraMovementBufferLimit = 6.0;
        boolean elytraDurabilityCheckEnabled = true;
        double elytraSlowdownMinSpeed = AntiFlyConstants.ELYTRA_SLOWDOWN_MIN_SPEED;
        double elytraSlowdownMinScale = AntiFlyConstants.ELYTRA_SLOWDOWN_MIN_SCALE;
        int elytraSlowdownGraceTicks = AntiFlyConstants.ELYTRA_SLOWDOWN_GRACE_TICKS;
        java.util.List<String> disabledWorlds = java.util.List.of();
        java.util.List<String> exempt = java.util.List.of();

        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

        static AntiFlyConfig load() {
            Path path = getPath();
            if (Files.exists(path)) {
                try {
                    String json = Files.readString(path);
                    AntiFlyConfig cfg = GSON.fromJson(json, AntiFlyConfig.class);
                    if (cfg != null) {
                        cfg.disabledWorlds = cfg.disabledWorlds == null ? java.util.List.of()
                            : cfg.disabledWorlds.stream()
                                .map(name -> name == null ? "" : name.trim().toLowerCase(Locale.ROOT))
                                .filter(name -> !name.isEmpty())
                                .distinct()
                                .sorted()
                                .toList();
                        return cfg;
                    }
                } catch (IOException ignored) {
                }
            }
            AntiFlyConfig cfg = new AntiFlyConfig();
            cfg.save();
            return cfg;
        }

        void save() {
            Path path = getPath();
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(this));
            } catch (IOException ignored) {
            }
        }

        private static Path getPath() {
            return FabricLoader.getInstance().getConfigDir().resolve("antifly.json");
        }
    }

    private static final class PlayerState {
        Vec3 lastGroundPos;
        Vec3 lastSupportPos;
        Vec3 lastPos;
        double airHorizontalBuffer;
        double airVerticalBuffer;
        double hoverBuffer;
        double antiKickBuffer;
        double elytraMovementBuffer;
        int airTicks;
        int airNonFallTicks;
        int airSessionTicks;
        double airSessionDescent;
        int hoverTicks;
        int voidTicks;
        int glideStallTicks;
        int groundSpoofTicks;
        int glideGroundGraceTicks;
        int glideSlowdownGraceTicks;
        double lastGlideHorizontal;
        boolean wasGliding;
        boolean lastServerOnGround;
        int vehicleGraceTicks;
        int vehicleAirTicks;
        int vehicleFallTicks;
        double vehicleFallHorizontalDistance;
        boolean wasInVehicle;
        long lastRubberBandAtMs;
        int lastFireworkUses = -1;
        int rocketGraceTicks;
        double hungerDebt;
        int flightAirborneTicks;
        int teleportGraceTicks;
        int sustainedAirTicks;
    }
}
