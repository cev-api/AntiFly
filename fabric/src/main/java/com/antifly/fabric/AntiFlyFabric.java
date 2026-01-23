package com.antifly.fabric;

import com.antifly.common.AntiFlyConstants;
import com.antifly.common.AttemptTracker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AntiFlyFabric implements ModInitializer {
    public static final String MOD_ID = "antifly";
    private static final long LOG_COOLDOWN_MS = 500;
    private static final Logger LOGGER = LoggerFactory.getLogger("AntiFly");

    private final AttemptTracker attemptTracker = new AttemptTracker();
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Set<UUID> exempt = ConcurrentHashMap.newKeySet();
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
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("antifly")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("help").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly enable"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly disable"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly status"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly exempt <player>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly unexempt <player>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set airSpeed <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set airVertical <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set waterSpeed <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set waterVertical <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set groundSpeed <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set elytraEnabled <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set elytraMaxHorizontal <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set elytraMaxUp <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set elytraMaxDown <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set elytraStallHorizontalMax <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set elytraStallVerticalMax <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set elytraStallTicks <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set elytraSlowdownMinSpeed <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set elytraSlowdownMinScale <value>"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("/antifly set elytraSlowdownGraceTicks <value>"), false);
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
                .then(Commands.literal("status").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("AntiFly: " + (config.enabled ? "enabled" : "disabled")), false);
                    ctx.getSource().sendSuccess(() -> Component.literal(
                        "Limits: ground=" + config.groundMax
                            + " air=" + config.airMax
                            + " airVertical=" + config.airVerticalMax
                            + " water=" + config.waterMax
                            + " waterVertical=" + config.waterVerticalMax
                    ), false);
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
                })
                    .then(settingNode("airSpeed"))
                    .then(settingNode("airVertical"))
                    .then(settingNode("waterSpeed"))
                    .then(settingNode("waterVertical"))
                    .then(settingNode("groundSpeed"))
                    .then(settingNode("elytraEnabled"))
                    .then(settingNode("elytraMaxHorizontal"))
                    .then(settingNode("elytraMaxUp"))
                    .then(settingNode("elytraMaxDown"))
                    .then(settingNode("elytraStallHorizontalMax"))
                    .then(settingNode("elytraStallVerticalMax"))
                    .then(settingNode("elytraStallTicks"))
                    .then(settingNode("elytraSlowdownMinSpeed"))
                    .then(settingNode("elytraSlowdownMinScale"))
                    .then(settingNode("elytraSlowdownGraceTicks")))
            );
        });
    }

    private void handlePlayerTick(ServerPlayer player) {
        PlayerState state = states.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        Vec3 pos = player.position();

        if (!player.isAlive()) {
            resetState(state, null);
            return;
        }

        if (!config.enabled) {
            boolean inFluid = player.isInWaterOrBubble() || player.isInLava() || player.isSwimming();
            boolean serverOnGround = hasGroundSupport(player);
            updateSupport(state, serverOnGround, inFluid, pos);
            state.lastPos = pos;
            state.lastServerOnGround = serverOnGround;
            state.wasGliding = player.isFallFlying();
            return;
        }

        boolean inFluid = player.isInWaterOrBubble() || player.isInLava() || player.isSwimming();
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
        if (inVehicle && player.getVehicle() instanceof Boat boat) {
            inBoatWater = boat.isInWaterOrBubble()
                || boat.onGround()
                || !boat.level().getFluidState(boat.blockPosition()).isEmpty()
                || !boat.level().getFluidState(boat.blockPosition().below()).isEmpty();
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
            state.lastPos = pos;
            return;
        }

        if (inVehicle && !serverOnGround && !inFluid) {
            Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
            rubberBandVehicle(player, state, target, "vehicle_flight");
            state.lastPos = pos;
            state.wasGliding = false;
            return;
        }

        if (isExempt(player)) {
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
            if (state.lastPos != null) {
                Vec3 vel = player.getDeltaMovement();
                double horizontal = Math.max(horizontalDistance(state.lastPos, pos), Math.sqrt(vel.x * vel.x + vel.z * vel.z));
                double maxAllowed = maxAirSpeed(player);
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
            double maxUp = maxAirVertical(player);
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
            if (isVoidBelow(player) && !serverOnGround && !inFluid
                && deltaY >= AntiFlyConstants.AIR_DESCENT_EPSILON) {
                state.voidTicks++;
                if (state.voidTicks > AntiFlyConstants.VOID_FALL_TICKS) {
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : player.serverLevel().getSharedSpawnPos().getCenter();
                    rubberBand(player, state, target, "void_fall", 0.0, 0.0);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
            } else {
                state.voidTicks = 0;
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
        double max = config.groundMax;
        if (player.isSprinting()) {
            max *= 1.3;
        }
        MobEffectInstance speed = player.getEffect(MobEffects.MOVEMENT_SPEED);
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
            depthStriderHolder, player.getInventory().getArmor(0));
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
        MobEffectInstance speed = player.getEffect(MobEffects.MOVEMENT_SPEED);
        if (speed != null) {
            max *= 1.0 + (0.2 * (speed.getAmplifier() + 1));
        }
        return max;
    }

    private double maxAirVertical(ServerPlayer player) {
        double max = config.airVerticalMax;
        MobEffectInstance jump = player.getEffect(MobEffects.JUMP);
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
        return player.getInventory().getArmor(2).is(Items.ELYTRA);
    }

    private boolean isVoidBelow(ServerPlayer player) {
        double voidY = player.serverLevel().getMinBuildHeight() - AntiFlyConstants.VOID_Y_OFFSET;
        return player.position().y < voidY;
    }

    private boolean hasGroundSupport(ServerPlayer player) {
        AABB box = player.getBoundingBox();
        double minX = box.minX + AntiFlyConstants.SUPPORT_EPSILON;
        double maxX = box.maxX - AntiFlyConstants.SUPPORT_EPSILON;
        double minZ = box.minZ + AntiFlyConstants.SUPPORT_EPSILON;
        double maxZ = box.maxZ - AntiFlyConstants.SUPPORT_EPSILON;
        double y = box.minY - AntiFlyConstants.SUPPORT_EPSILON;
        int blockY = Mth.floor(y);
        if (blockY < player.serverLevel().getMinBuildHeight()) {
            return false;
        }
        if (hasSolidAt(player, minX, blockY, minZ)) {
            return true;
        }
        if (hasSolidAt(player, maxX, blockY, minZ)) {
            return true;
        }
        if (hasSolidAt(player, minX, blockY, maxZ)) {
            return true;
        }
        return hasSolidAt(player, maxX, blockY, maxZ);
    }

    private boolean hasGroundSupportLoose(ServerPlayer player) {
        AABB box = player.getBoundingBox();
        double minX = box.minX + AntiFlyConstants.SUPPORT_EPSILON;
        double maxX = box.maxX - AntiFlyConstants.SUPPORT_EPSILON;
        double minZ = box.minZ + AntiFlyConstants.SUPPORT_EPSILON;
        double maxZ = box.maxZ - AntiFlyConstants.SUPPORT_EPSILON;
        double y = box.minY - AntiFlyConstants.SUPPORT_LOOSE_EPSILON;
        int blockY = Mth.floor(y);
        if (blockY < player.serverLevel().getMinBuildHeight()) {
            return false;
        }
        if (hasSolidAt(player, minX, blockY, minZ)) {
            return true;
        }
        if (hasSolidAt(player, maxX, blockY, minZ)) {
            return true;
        }
        if (hasSolidAt(player, minX, blockY, maxZ)) {
            return true;
        }
        return hasSolidAt(player, maxX, blockY, maxZ);
    }

    private boolean hasSolidAt(ServerPlayer player, double x, int y, double z) {
        BlockPos pos = new BlockPos(Mth.floor(x), y, Mth.floor(z));
        BlockState state = player.serverLevel().getBlockState(pos);
        return !state.getCollisionShape(player.serverLevel(), pos).isEmpty();
    }

    private void rubberBand(ServerPlayer player, PlayerState state, Vec3 target, String reason,
                            double actual, double allowed) {
        long now = System.currentTimeMillis();
        if (now - state.lastRubberBandAtMs > LOG_COOLDOWN_MS) {
            int count = attemptTracker.record(player.getUUID());
            LOGGER.info("Blocked {} for {} ({}) count={} loc={},{},{} actual={} allowed={}",
                reason,
                player.getName().getString(),
                player.getUUID(),
                count,
                target.x, target.y, target.z,
                String.format("%.3f", actual),
                String.format("%.3f", allowed)
            );
            state.lastRubberBandAtMs = now;
        }

        player.teleportTo(player.serverLevel(), target.x, target.y, target.z, player.getYRot(), player.getXRot());
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

    private void resetState(PlayerState state, Vec3 pos) {
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
        if (pos != null) {
            state.lastGroundPos = pos;
            state.lastSupportPos = pos;
            state.lastPos = pos;
            state.lastServerOnGround = true;
        }
    }

    private void updateSupport(PlayerState state, boolean onGround, boolean inFluid, Vec3 pos) {
        if (onGround) {
            state.lastGroundPos = pos;
            state.lastSupportPos = pos;
            state.airTicks = 0;
            state.hoverTicks = 0;
            state.voidTicks = 0;
            state.groundSpoofTicks = 0;
            state.lastServerOnGround = true;
        } else if (inFluid) {
            state.lastSupportPos = pos;
            state.airTicks = 0;
            state.hoverTicks = 0;
            state.voidTicks = 0;
            state.groundSpoofTicks = 0;
            state.lastServerOnGround = false;
        }
    }

    private int setValue(net.minecraft.commands.CommandSourceStack source, String key, double value) {
        switch (key) {
            case "groundSpeed" -> config.groundMax = value;
            case "airSpeed" -> config.airMax = value;
            case "airVertical" -> config.airVerticalMax = value;
            case "waterSpeed" -> config.waterMax = value;
            case "waterVertical" -> config.waterVerticalMax = value;
            case "elytraEnabled" -> config.elytraChecksEnabled = value > 0.5;
            case "elytraMaxHorizontal" -> config.elytraMaxHorizontal = value;
            case "elytraMaxUp" -> config.elytraMaxUp = value;
            case "elytraMaxDown" -> config.elytraMaxDown = value;
            case "elytraStallHorizontalMax" -> config.elytraStallHorizontalMax = value;
            case "elytraStallVerticalMax" -> config.elytraStallVerticalMax = value;
            case "elytraStallTicks" -> config.elytraStallTicks = (int) Math.round(value);
            case "elytraSlowdownMinSpeed" -> config.elytraSlowdownMinSpeed = value;
            case "elytraSlowdownMinScale" -> config.elytraSlowdownMinScale = value;
            case "elytraSlowdownGraceTicks" -> config.elytraSlowdownGraceTicks = (int) Math.round(value);
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
        String value = formatSettingValue(key);
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
            case "groundSpeed" -> String.valueOf(config.groundMax);
            case "airSpeed" -> String.valueOf(config.airMax);
            case "airVertical" -> String.valueOf(config.airVerticalMax);
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
            default -> null;
        };
    }

    private static final java.util.List<String> SET_KEYS = java.util.List.of(
        "airSpeed",
        "airVertical",
        "waterSpeed",
        "waterVertical",
        "groundSpeed",
        "elytraEnabled",
        "elytraMaxHorizontal",
        "elytraMaxUp",
        "elytraMaxDown",
        "elytraStallHorizontalMax",
        "elytraStallVerticalMax",
        "elytraStallTicks",
        "elytraSlowdownMinSpeed",
        "elytraSlowdownMinScale",
        "elytraSlowdownGraceTicks"
    );

    private void saveConfig() {
        config.exempt = exempt.stream().map(UUID::toString).sorted().toList();
        config.save();
    }

    private static final class AntiFlyConfig {
        boolean enabled = true;
        double groundMax = AntiFlyConstants.BASE_GROUND_MAX + AntiFlyConstants.GROUND_BUFFER;
        double airMax = AntiFlyConstants.BASE_AIR_MAX + AntiFlyConstants.AIR_BUFFER;
        double airVerticalMax = AntiFlyConstants.BASE_AIR_VERTICAL_MAX + AntiFlyConstants.AIR_VERTICAL_BUFFER;
        double waterMax = AntiFlyConstants.BASE_WATER_MAX + AntiFlyConstants.WATER_BUFFER;
        double waterVerticalMax = AntiFlyConstants.WATER_VERTICAL_MAX + AntiFlyConstants.WATER_VERTICAL_BUFFER;
        boolean elytraChecksEnabled = true;
        double elytraMaxHorizontal = AntiFlyConstants.ELYTRA_MAX_HORIZONTAL;
        double elytraMaxUp = AntiFlyConstants.ELYTRA_MAX_UP;
        double elytraMaxDown = AntiFlyConstants.ELYTRA_MAX_DOWN;
        double elytraStallHorizontalMax = AntiFlyConstants.ELYTRA_STALL_HORIZONTAL_MAX;
        double elytraStallVerticalMax = AntiFlyConstants.ELYTRA_STALL_VERTICAL_MAX;
        int elytraStallTicks = AntiFlyConstants.ELYTRA_STALL_TICKS;
        double elytraSlowdownMinSpeed = AntiFlyConstants.ELYTRA_SLOWDOWN_MIN_SPEED;
        double elytraSlowdownMinScale = AntiFlyConstants.ELYTRA_SLOWDOWN_MIN_SCALE;
        int elytraSlowdownGraceTicks = AntiFlyConstants.ELYTRA_SLOWDOWN_GRACE_TICKS;
        java.util.List<String> exempt = java.util.List.of();

        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

        static AntiFlyConfig load() {
            Path path = getPath();
            if (Files.exists(path)) {
                try {
                    String json = Files.readString(path);
                    AntiFlyConfig cfg = GSON.fromJson(json, AntiFlyConfig.class);
                    if (cfg != null) {
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
        int airTicks;
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
        boolean wasInVehicle;
        long lastRubberBandAtMs;
    }
}
