package com.jamesrenrold.mordkaiser;

import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.joml.Vector3f;

@Mod(MordKaiserCompanion.MOD_ID)
public final class MordKaiserCompanion {
    public static final String MOD_ID = "mord_kaiser";

    private static final String ORIGIN_TAG = "mord_kaiser";
    private static final String SOUL_DAMAGE = "MordSoulDamage";
    private static final String SOUL_HUD = "MordSoulHud";
    private static final String MACE_CHARGES = "MordMaceCharges";
    private static final String MACE_HUD = "MordMaceHud";
    private static final String MACE_COOLDOWN = "MordMaceCooldown";
    private static final String MACE_RECHARGE_START = "MordMaceRechargeStart";
    private static final String SHIELD_COOLDOWN = "MordShieldCooldown";
    private static final String METAL_UNTIL = "MordMetalUntil";
    private static final String METAL_COOLDOWN = "MordMetalCooldown";
    private static final String GRASP_COOLDOWN = "MordGraspCooldown";

    private static final int SOUL_RESOURCE_MAX = 100;
    private static final int MACE_MAX = 3;
    private static final long SHIELD_COOLDOWN_TICKS = 240L;
    private static final long MACE_COOLDOWN_TICKS = 200L;
    private static final long METAL_DURATION_TICKS = 300L;
    private static final long METAL_COOLDOWN_TICKS = 900L;
    private static final long GRASP_COOLDOWN_TICKS = 260L;
    private static final double METAL_RADIUS = 3.0D;
    private static final double GRASP_RANGE = 14.0D;
    private static final double GRASP_RADIUS = 3.0D;

    private static final ResourceKey<Level> DOMAIN_KEY =
            ResourceKey.create(Registries.DIMENSION, new ResourceLocation(MOD_ID, "mord_domain"));
    private static final int DOMAIN_DURATION_TICKS = 20 * 60;
    private static final int DOMAIN_WINDUP_TICKS = 23;
    private static final double DOMAIN_TARGET_RANGE = 40.0D;
    private static final double ARENA_SPACING = 160.0D;
    private static final int ARENA_HALF_SIZE = 24;
    private static final int ARENA_WALL_HEIGHT = 24;

    private static final UUID ARMOR_FLAT_ID = UUID.fromString("d4e6bbf8-3e5c-4a09-9e9c-bd3dbf1d6b01");
    private static final UUID ARMOR_PERCENT_ID = UUID.fromString("497559cc-d50c-4ae8-9e02-12f33a0f4d02");
    private static final UUID DOMAIN_SPELL_POWER_ID = UUID.fromString("6a7d749d-0b9d-4bcf-9c57-9e50ec8f8f01");
    private static final UUID DOMAIN_ATTACK_DAMAGE_ID = UUID.fromString("e4d1c8df-6b8d-4d32-9f29-63ce4d6bf102");
    private static final UUID DOMAIN_ATTACK_SPEED_ID = UUID.fromString("9f5cb1ef-8e06-40f0-95e0-1a5e9c711203");
    private static final UUID DOMAIN_ARMOR_ID = UUID.fromString("3a2f7a7e-55d3-4d89-b9e0-cc3cc9e2d204");
    private static final UUID DOMAIN_HEALTH_ID = UUID.fromString("1d7c4e91-2ac0-4a5f-8e7d-0b8b9f3a5205");
    private static final Map<UUID, PendingDomain> PENDING_DOMAINS = new HashMap<>();
    private static final Map<UUID, DomainSession> DOMAIN_SESSIONS = new HashMap<>();

    public MordKaiserCompanion() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    private static final class SavedLocation {
        final ResourceKey<Level> dimension;
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;

        SavedLocation(Entity entity) {
            this.dimension = entity.level().dimension();
            this.x = entity.getX();
            this.y = entity.getY();
            this.z = entity.getZ();
            this.yaw = entity.getYRot();
            this.pitch = entity.getXRot();
        }
    }

    private static final class PendingDomain {
        final SavedLocation playerOrigin;
        final SavedLocation targetOrigin;
        final UUID targetId;
        final LivingEntity target;
        int ticksRemaining = DOMAIN_WINDUP_TICKS;

        PendingDomain(SavedLocation playerOrigin, SavedLocation targetOrigin, LivingEntity target) {
            this.playerOrigin = playerOrigin;
            this.targetOrigin = targetOrigin;
            this.target = target;
            this.targetId = target.getUUID();
        }
    }

    private static final class DomainSession {
        final UUID playerId;
        final UUID targetId;
        final SavedLocation playerOrigin;
        final SavedLocation targetOrigin;
        final double arenaX;
        final int arenaFloorTop;
        LivingEntity target;
        int ticksRemaining = DOMAIN_DURATION_TICKS;
        int lastDisplayedSecond = Integer.MIN_VALUE;

        DomainSession(UUID playerId, UUID targetId, SavedLocation playerOrigin, SavedLocation targetOrigin,
                      LivingEntity target, double arenaX, int arenaFloorTop) {
            this.playerId = playerId;
            this.targetId = targetId;
            this.playerOrigin = playerOrigin;
            this.targetOrigin = targetOrigin;
            this.target = target;
            this.arenaX = arenaX;
            this.arenaFloorTop = arenaFloorTop;
        }
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("mordshield")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> useSoulShield(context.getSource().getPlayerOrException())));
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("mordmace")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> armMace(context.getSource().getPlayerOrException())));
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("mordmetal")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> activateMetal(context.getSource().getPlayerOrException())));
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("mordgrasp")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> useDeathGrasp(context.getSource().getPlayerOrException())));
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("mordconvert")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> convertAbsorption(context.getSource().getPlayerOrException())));
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("morddomain")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> activateDomain(context.getSource().getPlayerOrException())));
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("morddomainreturn")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> emergencyDomainReturn(context.getSource().getPlayerOrException())));
    }

    @SubscribeEvent
    public void onLivingHeal(LivingHealEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isMord(player)
                && event.getAmount() <= 1.0F) {
            // Natural food regeneration heals one point at a time; block that while
            // leaving deliberate, stronger spell/potion healing available.
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onItemUseStart(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof ServerPlayer player && isMord(player)
                && event.getItem().isEdible()) {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("Iron Revenants cannot eat.")
                    .withStyle(ChatFormatting.DARK_GRAY), true);
        }
    }


    @SubscribeEvent
    public void onDomainPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;

        PendingDomain pending = PENDING_DOMAINS.get(player.getUUID());
        if (pending != null) {
            if (pending.target == null || pending.target.isRemoved() || !pending.target.isAlive()) {
                PENDING_DOMAINS.remove(player.getUUID());
                player.displayClientMessage(Component.literal("Realm of Death cancelled: target was defeated.")
                        .withStyle(ChatFormatting.DARK_RED), true);
                return;
            }
            pending.ticksRemaining--;
            if (pending.ticksRemaining <= 0) {
                PENDING_DOMAINS.remove(player.getUUID());
                completeDomainActivation(player, pending);
            }
            return;
        }

        DomainSession session = DOMAIN_SESSIONS.get(player.getUUID());
        if (session == null) return;
        if (player.level().dimension() != DOMAIN_KEY) {
            finishDomain(player, session, "Realm of Death released: you left the arena.");
            return;
        }

        LivingEntity target = session.target;
        if (target == null || target.isRemoved() || !target.isAlive()) {
            finishDomain(player, session, "Realm of Death released: target defeated.");
            return;
        }
        if (target.level().dimension() != DOMAIN_KEY) {
            finishDomain(player, session, "Realm of Death released: target escaped.");
            return;
        }

        if (session.ticksRemaining % 20 == 0) {
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 0, false, true, true));
        }
        updateDomainCountdown(player, session);
        session.ticksRemaining--;
        if (session.ticksRemaining <= 0) {
            finishDomain(player, session, "Realm of Death released: one minute elapsed.");
        }
    }

    @SubscribeEvent
    public void onDomainLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PENDING_DOMAINS.remove(player.getUUID());
        DomainSession session = DOMAIN_SESSIONS.get(player.getUUID());
        if (session != null) finishDomain(player, session, null);
    }

    @SubscribeEvent
    public void onDomainRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PENDING_DOMAINS.remove(player.getUUID());
        DomainSession session = DOMAIN_SESSIONS.remove(player.getUUID());
        if (session == null) return;

        removeDomainModifiers(player);
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (session.target == null || session.target.isRemoved() || !session.target.isAlive()) {
            transferDomainLoot(server, session);
        } else {
            returnDomainTarget(server, session);
        }
        cleanupDomainArena(server, session);
        ServerLevel origin = server.getLevel(session.playerOrigin.dimension);
        if (origin != null) {
            player.teleportTo(origin, session.playerOrigin.x, session.playerOrigin.y, session.playerOrigin.z,
                    session.playerOrigin.yaw, session.playerOrigin.pitch);
            player.setDeltaMovement(Vec3.ZERO);
        }
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        // Resolve the player through a spell projectile's owner as well as direct
        // melee sources, so Soul Charge fills from both weapon and spell damage.
        ServerPlayer player = resolvePlayer(event.getSource().getEntity());
        if (player == null) player = resolvePlayer(event.getSource().getDirectEntity());
        if (player == null || !isMord(player) || event.getEntity() == player || event.getAmount() <= 0.0F) return;

        // Mace of Spades counts successful melee hits only: both source entities
        // must be the player, excluding projectiles and spell entities.
        if (event.getSource().getEntity() == player && event.getSource().getDirectEntity() == player) {
            int charges = getData(player).getInt(MACE_CHARGES);
            if (charges > 0) {
                double spellPower = getSpellPower(player);
                event.setAmount(event.getAmount() + (float) (spellPower * 0.30D));
                charges--;
                getData(player).putInt(MACE_CHARGES, charges);
                syncMaceResource(player, charges);
                showMaceBurst(player, event.getEntity());
                if (charges == 0) {
                    long cooldownStart = player.serverLevel().getGameTime();
                    getData(player).putLong(MACE_RECHARGE_START, cooldownStart);
                    getData(player).putLong(MACE_COOLDOWN, cooldownStart + MACE_COOLDOWN_TICKS);
                    player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 300, 0, false, true, true));
                    player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 300, 0, false, true, true));
                    player.displayClientMessage(Component.literal("Mace of Spades completed: Speed I and Strength I.")
                            .withStyle(ChatFormatting.DARK_RED), true);
                }
            }
        }

        addSoulDamage(player, event.getAmount());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        if (now % 5L != 0L) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!isMord(player)) continue;
            syncSoulResource(player);
            syncMaceRecharge(player, now);
            long metalUntil = getData(player).getLong(METAL_UNTIL);
            if (metalUntil > now) {
                ensureArmorModifiers(player);
                if (now % 10L == 0L) applyMetalAura(player);
            } else {
                removeArmorModifiers(player);
            }
        }
    }


    private static int activateDomain(ServerPlayer player) {
        if (!isMord(player)) return 0;
        if (PENDING_DOMAINS.containsKey(player.getUUID()) || DOMAIN_SESSIONS.containsKey(player.getUUID())) {
            player.displayClientMessage(Component.literal("Realm of Death is already active or opening.")
                    .withStyle(ChatFormatting.DARK_RED), true);
            return 0;
        }

        MinecraftServer server = player.getServer();
        if (server == null || server.getLevel(DOMAIN_KEY) == null) {
            player.displayClientMessage(Component.literal("Realm of Death failed: mord_kaiser:mord_domain is not loaded.")
                    .withStyle(ChatFormatting.DARK_RED), true);
            return 0;
        }

        LivingEntity target = findHostileCrosshairTarget(player);
        if (target == null) {
            player.displayClientMessage(Component.literal("Aim directly at a hostile mob within 40 blocks.")
                    .withStyle(ChatFormatting.DARK_RED), true);
            return 0;
        }

        PENDING_DOMAINS.put(player.getUUID(),
                new PendingDomain(new SavedLocation(player), new SavedLocation(target), target));
        player.displayClientMessage(Component.literal("Realm of Death...")
                .withStyle(ChatFormatting.DARK_RED), true);
        return 1;
    }

    private static void completeDomainActivation(ServerPlayer player, PendingDomain pending) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel domain = server.getLevel(DOMAIN_KEY);
        LivingEntity target = pending.target;
        if (domain == null || target == null || target.isRemoved() || !target.isAlive()) return;

        double arenaX = arenaCenterFor(player.getUUID());
        int floorTop = arenaFloorTop(domain, arenaX);
        double arenaY = floorTop + 1.0D;
        buildArenaCage(domain, arenaX, floorTop);

        LivingEntity domainTarget = transferLivingEntity(target, domain, arenaX, arenaY, 7.0D, 180.0F, 0.0F);
        if (domainTarget == null || domainTarget.isRemoved() || !domainTarget.isAlive()) {
            removeArenaCage(domain, arenaX, floorTop);
            player.displayClientMessage(Component.literal("Realm of Death failed to move the target.")
                    .withStyle(ChatFormatting.DARK_RED), true);
            return;
        }

        applyDomainModifiers(player);
        DomainSession session = new DomainSession(player.getUUID(), pending.targetId, pending.playerOrigin,
                pending.targetOrigin, domainTarget, arenaX, floorTop);
        DOMAIN_SESSIONS.put(player.getUUID(), session);
        player.teleportTo(domain, arenaX, arenaY, -7.0D, 0.0F, 0.0F);
        player.setDeltaMovement(Vec3.ZERO);
        domain.playSound(null, player.blockPosition(), SoundEvents.END_PORTAL_SPAWN,
                SoundSource.PLAYERS, 0.8F, 1.1F);
        updateDomainCountdown(player, session);
    }

    private static int emergencyDomainReturn(ServerPlayer player) {
        if (!isMord(player)) return 0;
        PENDING_DOMAINS.remove(player.getUUID());
        DomainSession session = DOMAIN_SESSIONS.get(player.getUUID());
        if (session == null) {
            player.displayClientMessage(Component.literal("No active Realm of Death found.")
                    .withStyle(ChatFormatting.DARK_RED), true);
            return 0;
        }
        finishDomain(player, session, "Realm of Death released manually.");
        return 1;
    }

    private static LivingEntity findHostileCrosshairTarget(ServerPlayer player) {
        Vec3 start = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end = start.add(look.scale(DOMAIN_TARGET_RANGE));
        AABB search = player.getBoundingBox().expandTowards(look.scale(DOMAIN_TARGET_RANGE)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, start, end, search,
                entity -> entity instanceof LivingEntity living
                        && entity != player
                        && entity.isPickable()
                        && entity.isAlive()
                        && isHostile(living)
                        && !living.getTags().contains(ORIGIN_TAG),
                DOMAIN_TARGET_RANGE * DOMAIN_TARGET_RANGE);
        if (hit == null || !(hit.getEntity() instanceof LivingEntity living)) return null;
        return player.hasLineOfSight(living) ? living : null;
    }

    private static boolean isHostile(LivingEntity living) {
        return living instanceof Enemy || living.getType().getCategory() == MobCategory.MONSTER;
    }

    private static double arenaCenterFor(UUID playerId) {
        return Math.floorMod(playerId.hashCode(), 4096) * ARENA_SPACING + 0.5D;
    }

    private static int arenaFloorTop(ServerLevel domain, double arenaX) {
        int x = (int) Math.floor(arenaX);
        domain.getChunkAt(new BlockPos(x, 0, -7));
        domain.getChunkAt(new BlockPos(x, 0, 7));
        int playerFloor = domain.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, -7);
        int targetFloor = domain.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, 7);
        return Math.max(playerFloor, targetFloor);
    }

    private static void buildArenaCage(ServerLevel domain, double arenaX, int floorTop) {
        int centerX = (int) Math.floor(arenaX);
        int minX = centerX - ARENA_HALF_SIZE;
        int maxX = centerX + ARENA_HALF_SIZE;
        int minY = floorTop;
        int maxY = floorTop + ARENA_WALL_HEIGHT - 1;
        int minZ = -ARENA_HALF_SIZE;
        int maxZ = ARENA_HALF_SIZE;

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                domain.setBlock(new BlockPos(x, y, minZ), Blocks.BARRIER.defaultBlockState(), 3);
                domain.setBlock(new BlockPos(x, y, maxZ), Blocks.BARRIER.defaultBlockState(), 3);
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                domain.setBlock(new BlockPos(minX, y, z), Blocks.BARRIER.defaultBlockState(), 3);
                domain.setBlock(new BlockPos(maxX, y, z), Blocks.BARRIER.defaultBlockState(), 3);
            }
        }
    }

    private static void removeArenaCage(ServerLevel domain, double arenaX, int floorTop) {
        int centerX = (int) Math.floor(arenaX);
        int minX = centerX - ARENA_HALF_SIZE;
        int maxX = centerX + ARENA_HALF_SIZE;
        int minY = floorTop;
        int maxY = floorTop + ARENA_WALL_HEIGHT - 1;
        int minZ = -ARENA_HALF_SIZE;
        int maxZ = ARENA_HALF_SIZE;

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                clearBarrier(domain, new BlockPos(x, y, minZ));
                clearBarrier(domain, new BlockPos(x, y, maxZ));
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                clearBarrier(domain, new BlockPos(minX, y, z));
                clearBarrier(domain, new BlockPos(maxX, y, z));
            }
        }
    }

    private static void clearBarrier(ServerLevel domain, BlockPos pos) {
        if (domain.getBlockState(pos).is(Blocks.BARRIER)) {
            domain.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static void updateDomainCountdown(ServerPlayer player, DomainSession session) {
        int seconds = Math.max(0, (session.ticksRemaining + 19) / 20);
        if (seconds == session.lastDisplayedSecond) return;
        session.lastDisplayedSecond = seconds;
        String json = "{\"text\":\"Realm of Death: " + seconds
                + "s\",\"color\":\"dark_red\",\"bold\":true}";
        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(2).withSuppressedOutput(),
                "title @s actionbar " + json);
    }

    private static void applyDomainModifiers(ServerPlayer player) {
        AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
        double oldMaxHealth = player.getMaxHealth();
        addDomainModifier(maxHealth, DOMAIN_HEALTH_ID, "Mord Realm max health", 0.20D);
        addDomainModifier(player.getAttribute(Attributes.ATTACK_DAMAGE), DOMAIN_ATTACK_DAMAGE_ID,
                "Mord Realm attack damage", 0.20D);
        addDomainModifier(player.getAttribute(Attributes.ATTACK_SPEED), DOMAIN_ATTACK_SPEED_ID,
                "Mord Realm attack speed", 0.15D);
        addDomainModifier(player.getAttribute(Attributes.ARMOR), DOMAIN_ARMOR_ID,
                "Mord Realm armor", 0.20D);
        addDomainModifier(player.getAttribute(AttributeRegistry.SPELL_POWER.get()), DOMAIN_SPELL_POWER_ID,
                "Mord Realm spell power", 0.20D);
        double newMaxHealth = player.getMaxHealth();
        if (newMaxHealth > oldMaxHealth) {
            player.setHealth(Math.min((float) newMaxHealth,
                    player.getHealth() + (float) (newMaxHealth - oldMaxHealth)));
        }
    }

    private static void addDomainModifier(AttributeInstance attribute, UUID id, String name, double value) {
        if (attribute != null && attribute.getModifier(id) == null) {
            attribute.addTransientModifier(new AttributeModifier(id, name, value,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }

    private static void removeDomainModifiers(ServerPlayer player) {
        removeDomainModifier(player.getAttribute(Attributes.MAX_HEALTH), DOMAIN_HEALTH_ID);
        removeDomainModifier(player.getAttribute(Attributes.ATTACK_DAMAGE), DOMAIN_ATTACK_DAMAGE_ID);
        removeDomainModifier(player.getAttribute(Attributes.ATTACK_SPEED), DOMAIN_ATTACK_SPEED_ID);
        removeDomainModifier(player.getAttribute(Attributes.ARMOR), DOMAIN_ARMOR_ID);
        removeDomainModifier(player.getAttribute(AttributeRegistry.SPELL_POWER.get()), DOMAIN_SPELL_POWER_ID);
        player.setHealth(Math.min(player.getHealth(), player.getMaxHealth()));
    }

    private static void removeDomainModifier(AttributeInstance attribute, UUID id) {
        if (attribute != null) attribute.removeModifier(id);
    }

    private static void finishDomain(ServerPlayer player, DomainSession session, String message) {
        if (!DOMAIN_SESSIONS.remove(session.playerId, session)) return;
        removeDomainModifiers(player);
        MinecraftServer server = player.getServer();
        if (server == null) return;

        if (session.target == null || session.target.isRemoved() || !session.target.isAlive()) {
            transferDomainLoot(server, session);
        } else {
            returnDomainTarget(server, session);
        }
        cleanupDomainArena(server, session);
        ServerLevel origin = server.getLevel(session.playerOrigin.dimension);
        if (origin != null) {
            player.teleportTo(origin, session.playerOrigin.x, session.playerOrigin.y, session.playerOrigin.z,
                    session.playerOrigin.yaw, session.playerOrigin.pitch);
            player.setDeltaMovement(Vec3.ZERO);
        }
        if (message != null) {
            player.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.DARK_RED), true);
        }
    }

    private static void cleanupDomainArena(MinecraftServer server, DomainSession session) {
        ServerLevel domain = server.getLevel(DOMAIN_KEY);
        if (domain == null) return;
        int centerX = (int) Math.floor(session.arenaX);
        AABB arena = new AABB(centerX - ARENA_HALF_SIZE, session.arenaFloorTop,
                -ARENA_HALF_SIZE, centerX + ARENA_HALF_SIZE + 1,
                session.arenaFloorTop + ARENA_WALL_HEIGHT, ARENA_HALF_SIZE + 1);
        ServerLevel destination = server.getLevel(session.targetOrigin.dimension);
        for (Entity entity : new ArrayList<>(domain.getEntities(null, arena, Entity::isAlive))) {
            if (entity instanceof ServerPlayer serverPlayer && serverPlayer.getUUID().equals(session.playerId)) continue;
            if (entity.getUUID().equals(session.targetId)) continue;
            if ((entity instanceof ItemEntity || entity instanceof ExperienceOrb) && destination != null) {
                transferLooseEntity(entity, destination, session.targetOrigin.x, session.targetOrigin.y, session.targetOrigin.z);
            } else {
                entity.discard();
            }
        }
        removeArenaCage(domain, session.arenaX, session.arenaFloorTop);
    }

    private static void transferDomainLoot(MinecraftServer server, DomainSession session) {
        LivingEntity deadTarget = session.target;
        if (deadTarget == null || !(deadTarget.level() instanceof ServerLevel domain)) return;
        ServerLevel destination = server.getLevel(session.targetOrigin.dimension);
        if (destination == null) return;
        Vec3 deathPos = deadTarget.position();
        AABB capture = new AABB(deathPos, deathPos).inflate(8.0D);
        List<Entity> loot = new ArrayList<>();
        loot.addAll(domain.getEntitiesOfClass(ItemEntity.class, capture, Entity::isAlive));
        loot.addAll(domain.getEntitiesOfClass(ExperienceOrb.class, capture, Entity::isAlive));
        for (Entity entity : loot) {
            transferLooseEntity(entity, destination, session.targetOrigin.x, session.targetOrigin.y, session.targetOrigin.z);
        }
    }

    private static void returnDomainTarget(MinecraftServer server, DomainSession session) {
        LivingEntity target = session.target;
        if (target == null || target.isRemoved() || !target.isAlive()) return;
        ServerLevel destination = server.getLevel(session.targetOrigin.dimension);
        if (destination == null) return;
        LivingEntity returned = transferLivingEntity(target, destination, session.targetOrigin.x,
                session.targetOrigin.y, session.targetOrigin.z, session.targetOrigin.yaw, session.targetOrigin.pitch);
        if (returned != null) {
            returned.setDeltaMovement(Vec3.ZERO);
            session.target = returned;
        }
    }

    private static LivingEntity transferLivingEntity(LivingEntity source, ServerLevel destination,
                                                      double x, double y, double z, float yaw, float pitch) {
        if (source instanceof ServerPlayer || destination == null) return null;
        ServerLevel sourceLevel = source.level() instanceof ServerLevel serverLevel ? serverLevel : null;
        if (sourceLevel == null) return null;
        UUID sourceId = source.getUUID();
        double originalX = source.getX();
        double originalY = source.getY();
        double originalZ = source.getZ();
        float originalYaw = source.getYRot();
        float originalPitch = source.getXRot();
        CompoundTag snapshot = new CompoundTag();
        if (!source.save(snapshot)) return null;
        snapshot.putUUID("UUID", sourceId);
        source.discard();

        Entity recreated = EntityType.loadEntityRecursive(snapshot, destination, entity -> {
            entity.setUUID(sourceId);
            entity.moveTo(x, y, z, yaw, pitch);
            entity.setDeltaMovement(Vec3.ZERO);
            return entity;
        });
        if (recreated instanceof LivingEntity living && destination.addWithUUID(living)) {
            return living;
        }

        Entity restored = EntityType.loadEntityRecursive(snapshot, sourceLevel, entity -> {
            entity.setUUID(sourceId);
            entity.moveTo(originalX, originalY, originalZ, originalYaw, originalPitch);
            entity.setDeltaMovement(Vec3.ZERO);
            return entity;
        });
        if (restored != null) sourceLevel.addWithUUID(restored);
        return null;
    }

    private static boolean transferLooseEntity(Entity source, ServerLevel destination,
                                               double x, double y, double z) {
        if (source == null || source.isRemoved() || destination == null
                || source instanceof LivingEntity || source instanceof ServerPlayer) return false;
        ServerLevel sourceLevel = source.level() instanceof ServerLevel serverLevel ? serverLevel : null;
        if (sourceLevel == null) return false;
        UUID sourceId = source.getUUID();
        double originalX = source.getX();
        double originalY = source.getY();
        double originalZ = source.getZ();
        float originalYaw = source.getYRot();
        float originalPitch = source.getXRot();
        CompoundTag snapshot = new CompoundTag();
        if (!source.save(snapshot)) return false;
        snapshot.putUUID("UUID", sourceId);
        source.discard();

        Entity recreated = EntityType.loadEntityRecursive(snapshot, destination, entity -> {
            entity.setUUID(sourceId);
            entity.moveTo(x, y, z, originalYaw, originalPitch);
            entity.setDeltaMovement(Vec3.ZERO);
            return entity;
        });
        if (recreated != null && destination.addWithUUID(recreated)) return true;

        Entity restored = EntityType.loadEntityRecursive(snapshot, sourceLevel, entity -> {
            entity.setUUID(sourceId);
            entity.moveTo(originalX, originalY, originalZ, originalYaw, originalPitch);
            entity.setDeltaMovement(Vec3.ZERO);
            return entity;
        });
        if (restored != null) sourceLevel.addWithUUID(restored);
        return false;
    }

    private static int useSoulShield(ServerPlayer player) {
        if (!isMord(player)) return 0;
        long now = player.serverLevel().getGameTime();
        var data = getData(player);
        if (data.getLong(SHIELD_COOLDOWN) > now) {
            player.displayClientMessage(Component.literal("Soul Shield is still cooling down.")
                    .withStyle(ChatFormatting.DARK_PURPLE), true);
            return 0;
        }

        double maxHealth = player.getMaxHealth();
        double capDamage = maxHealth * 2.0D;
        double charge = Math.min(Math.max(0.0D, data.getDouble(SOUL_DAMAGE)), capDamage);
        double ratio = capDamage <= 0.0D ? 0.0D : charge / capDamage;
        double shieldFraction = ratio >= 1.0D ? 0.33D : ratio >= 0.66D ? 0.22D : ratio >= 0.33D ? 0.11D : 0.0D;

        if (shieldFraction <= 0.0D) {
            player.displayClientMessage(Component.literal("Soul Shield needs at least 33% charge.")
                    .withStyle(ChatFormatting.DARK_PURPLE), true);
            return 0;
        }

        player.setAbsorptionAmount((float) (maxHealth * shieldFraction));
        data.putDouble(SOUL_DAMAGE, 0.0D);
        data.putLong(SHIELD_COOLDOWN, now + SHIELD_COOLDOWN_TICKS);
        syncSoulResource(player);
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE,
                SoundSource.PLAYERS, 0.8F, 0.8F);
        player.displayClientMessage(Component.literal("Soul Shield: " + Math.round(shieldFraction * 100.0D)
                + "% maximum health absorption.").withStyle(ChatFormatting.DARK_PURPLE), true);
        return 1;
    }

    private static int armMace(ServerPlayer player) {
        if (!isMord(player)) return 0;
        var data = getData(player);
        if (data.getInt(MACE_CHARGES) > 0) {
            player.displayClientMessage(Component.literal("Mace of Spades is already armed.")
                    .withStyle(ChatFormatting.DARK_RED), true);
            return 0;
        }
        if (data.getLong(MACE_COOLDOWN) > player.serverLevel().getGameTime()) {
            long remaining = data.getLong(MACE_COOLDOWN) - player.serverLevel().getGameTime();
            player.displayClientMessage(Component.literal("Mace of Spades is cooling down (" + ((remaining + 19L) / 20L) + "s).")
                    .withStyle(ChatFormatting.DARK_RED), true);
            return 0;
        }
        data.putInt(MACE_CHARGES, MACE_MAX);
        syncMaceResource(player, MACE_MAX);
        player.displayClientMessage(Component.literal("Mace of Spades armed: land three melee hits.")
                .withStyle(ChatFormatting.DARK_RED), true);
        return 1;
    }

    private static int useDeathGrasp(ServerPlayer player) {
        if (!isMord(player)) return 0;
        long now = player.serverLevel().getGameTime();
        var data = getData(player);
        if (data.getLong(GRASP_COOLDOWN) > now) {
            long remaining = data.getLong(GRASP_COOLDOWN) - now;
            player.displayClientMessage(Component.literal("Death's Grasp is cooling down ("
                    + ((remaining + 19L) / 20L) + "s).").withStyle(ChatFormatting.DARK_PURPLE), true);
            return 0;
        }

        Vec3 direction = player.getLookAngle().normalize();
        // Start the pull at waist height so the hand effect stays below the crosshair.
        Vec3 origin = player.position().add(0.0D, 0.45D, 0.0D);
        showDeathGrasp(player, direction);
        AABB area = new AABB(origin, origin.add(direction.scale(GRASP_RANGE))).inflate(GRASP_RADIUS);
        int pulled = 0;
        for (LivingEntity target : player.serverLevel().getEntitiesOfClass(LivingEntity.class, area,
                entity -> entity != player && entity.isAlive() && !player.isAlliedTo(entity)
                        && !(entity instanceof ServerPlayer))) {
            Vec3 offset = target.position().add(0.0D, target.getBbHeight() * 0.45D, 0.0D)
                    .subtract(origin);
            double along = offset.dot(direction);
            if (along < 0.5D || along > GRASP_RANGE) continue;
            double lateral = offset.subtract(direction.scale(along)).length();
            if (lateral > GRASP_RADIUS) continue;

            Vec3 pull = player.position().add(0.0D, 0.55D, 0.0D)
                    .subtract(target.position());
            if (pull.lengthSqr() > 0.01D) {
                target.setDeltaMovement(pull.normalize().scale(1.05D).add(0.0D, 0.22D, 0.0D));
                target.hurtMarked = true;
            }
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 0, false, true, true));
            showGraspImpact(player.serverLevel(), target);
            pulled++;
        }
        data.putLong(GRASP_COOLDOWN, now + GRASP_COOLDOWN_TICKS);
        player.displayClientMessage(Component.literal("Death's Grasp pulled " + pulled + " target"
                + (pulled == 1 ? "" : "s") + ".").withStyle(ChatFormatting.DARK_PURPLE), true);
        return 1;
    }

    private static int convertAbsorption(ServerPlayer player) {
        if (!isMord(player)) return 0;
        float current = player.getAbsorptionAmount();
        if (current <= 0.01F) {
            player.displayClientMessage(Component.literal("You have no absorption to convert.")
                    .withStyle(ChatFormatting.GOLD), true);
            return 0;
        }

        // Consume every absorption point; split its value evenly between health and hunger.
        float restored = current * 0.5F;
        player.setAbsorptionAmount(0.0F);
        player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + restored));
        int hunger = Math.max(1, Math.round(restored));
        var food = player.getFoodData();
        food.setFoodLevel(Math.min(20, food.getFoodLevel() + hunger));
        food.setSaturation(Math.min(food.getFoodLevel(), food.getSaturationLevel() + hunger));
        player.displayClientMessage(Component.literal("Consumed all absorption: half became "
                + String.format(java.util.Locale.ROOT, "%.1f", restored)
                + " health and half became " + hunger + " hunger.").withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static int activateMetal(ServerPlayer player) {
        if (!isMord(player)) return 0;
        long now = player.serverLevel().getGameTime();
        var data = getData(player);
        if (data.getLong(METAL_COOLDOWN) > now) {
            player.displayClientMessage(Component.literal("Metal Shards is still cooling down.")
                    .withStyle(ChatFormatting.GRAY), true);
            return 0;
        }
        data.putLong(METAL_UNTIL, now + METAL_DURATION_TICKS);
        data.putLong(METAL_COOLDOWN, now + METAL_COOLDOWN_TICKS);
        ensureArmorModifiers(player);
        player.displayClientMessage(Component.literal("Metal Shards activated for 15 seconds.")
                .withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    private static void addSoulDamage(ServerPlayer player, float damage) {
        var data = getData(player);
        double cap = player.getMaxHealth() * 2.0D;
        double stored = Math.min(cap, Math.max(0.0D, data.getDouble(SOUL_DAMAGE)) + damage);
        data.putDouble(SOUL_DAMAGE, stored);
        syncSoulResource(player);
    }

    private static void syncSoulResource(ServerPlayer player) {
        var data = getData(player);
        double cap = Math.max(1.0D, player.getMaxHealth() * 2.0D);
        double charge = Math.min(cap, Math.max(0.0D, data.getDouble(SOUL_DAMAGE)));
        int value = (int) Math.round((charge / cap) * SOUL_RESOURCE_MAX);
        if (data.getInt(SOUL_HUD) == value) return;
        data.putInt(SOUL_HUD, value);
        runResourceCommand(player, "mord_kaiser:soul_charge", value);
    }

    private static void syncMaceRecharge(ServerPlayer player, long now) {
        var data = getData(player);
        long cooldownEnd = data.getLong(MACE_COOLDOWN);
        if (cooldownEnd <= 0L) return;

        long rechargeStart = data.getLong(MACE_RECHARGE_START);
        if (rechargeStart <= 0L) {
            rechargeStart = Math.max(0L, cooldownEnd - MACE_COOLDOWN_TICKS);
            data.putLong(MACE_RECHARGE_START, rechargeStart);
        }

        if (now >= cooldownEnd) {
            data.putInt(MACE_CHARGES, MACE_MAX);
            data.putLong(MACE_COOLDOWN, 0L);
            data.putLong(MACE_RECHARGE_START, 0L);
            syncMaceResource(player, MACE_MAX);
            return;
        }

        // During cooldown the HUD fills visually, but the charges remain locked
        // at zero until the full cooldown is complete.
        long elapsed = Math.max(0L, Math.min(MACE_COOLDOWN_TICKS, now - rechargeStart));
        int visual = Math.min(MACE_MAX, (int) ((elapsed * MACE_MAX) / MACE_COOLDOWN_TICKS));
        if (data.getInt(MACE_HUD) != visual) {
            syncMaceResource(player, visual);
        }
    }

    private static void syncMaceResource(ServerPlayer player, int value) {
        var data = getData(player);
        data.putInt(MACE_HUD, Math.max(0, Math.min(MACE_MAX, value)));
        runResourceCommand(player, "mord_kaiser:mace_charges", Math.max(0, Math.min(MACE_MAX, value)));
    }

    private static void runResourceCommand(ServerPlayer player, String resource, int value) {
        String command = "resource set @s " + resource + " " + value;
        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(2).withSuppressedOutput(), command);
    }

    private static ServerPlayer resolvePlayer(Entity entity) {
        if (entity instanceof ServerPlayer player) return player;
        if (entity instanceof Projectile projectile && projectile.getOwner() != entity) {
            return resolvePlayer(projectile.getOwner());
        }
        return null;
    }

    private static double getSpellPower(ServerPlayer player) {
        AttributeInstance attribute = player.getAttribute(AttributeRegistry.SPELL_POWER.get());
        return attribute == null ? 0.0D : Math.max(0.0D, attribute.getValue());
    }

    private static void showDeathGrasp(ServerPlayer player, Vec3 direction) {
        ServerLevel level = player.serverLevel();
        // Render the spectral hand from the player's lower torso rather than the camera.
        Vec3 origin = player.position().add(0.0D, 0.45D, 0.0D).add(direction.scale(0.35D));
        Vec3 side = direction.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (side.lengthSqr() < 0.01D) side = new Vec3(1.0D, 0.0D, 0.0D);
        else side = side.normalize();

        // A moving palm with five finger trails gives the pull a recognizable spectral-hand silhouette.
        for (int step = 0; step < 18; step++) {
            Vec3 center = origin.add(direction.scale(step * 0.48D));
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, center.x, center.y, center.z,
                    2, 0.10D, 0.10D, 0.10D, 0.01D);
            for (int finger = -2; finger <= 2; finger++) {
                Vec3 fingertip = center.add(side.scale(finger * 0.22D))
                        .add(0.0D, 0.18D + Math.abs(finger) * 0.05D, 0.0D);
                level.sendParticles(ParticleTypes.SOUL, fingertip.x, fingertip.y, fingertip.z,
                        1, 0.02D, 0.02D, 0.02D, 0.0D);
            }
        }
    }

    private static void showGraspImpact(ServerLevel level, LivingEntity target) {
        double x = target.getX();
        double y = target.getY() + target.getBbHeight() * 0.5D;
        double z = target.getZ();
        level.sendParticles(ParticleTypes.SOUL, x, y, z, 12, 0.35D, 0.45D, 0.35D, 0.03D);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y, z, 10, 0.25D, 0.35D, 0.25D, 0.05D);
    }

    private static void showMaceBurst(ServerPlayer player, LivingEntity target) {
        ServerLevel level = player.serverLevel();
        double x = target.getX();
        double y = target.getY() + target.getBbHeight() * 0.55D;
        double z = target.getZ();
        level.sendParticles(new DustParticleOptions(new Vector3f(0.02F, 0.28F, 0.08F), 1.4F),
                x, y, z, 22, 0.45D, 0.45D, 0.45D, 0.08D);
        level.sendParticles(new DustParticleOptions(new Vector3f(0.0F, 0.85F, 1.0F), 1.2F),
                x, y, z, 18, 0.55D, 0.55D, 0.55D, 0.10D);
        level.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    private static void applyMetalAura(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        double physical = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float damage = (float) Math.max(0.1D, physical * 0.10D);
        AABB area = player.getBoundingBox().inflate(METAL_RADIUS);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, area,
                entity -> entity != player && entity.isAlive() && !player.isAlliedTo(entity))) {
            target.hurt(level.damageSources().generic(), damage);
        }
        showMetalRing(level, player);
    }

    private static void showMetalRing(ServerLevel level, ServerPlayer player) {
        DustParticleOptions cyan = new DustParticleOptions(new Vector3f(0.0F, 0.85F, 1.0F), 1.0F);
        double y = player.getY() + 0.08D;
        for (int i = 0; i < 32; i++) {
            double angle = (Math.PI * 2.0D * i) / 32.0D;
            double x = player.getX() + Math.cos(angle) * METAL_RADIUS;
            double z = player.getZ() + Math.sin(angle) * METAL_RADIUS;
            level.sendParticles(cyan, x, y, z, 1, 0.0D, 0.02D, 0.0D, 0.0D);
        }
        for (int i = 0; i < 10; i++) {
            double angle = (Math.PI * 2.0D * i) / 10.0D;
            double radius = 0.45D + (i % 3) * 0.55D;
            level.sendParticles(cyan, player.getX() + Math.cos(angle) * radius, player.getY() + 0.25D,
                    player.getZ() + Math.sin(angle) * radius, 1, 0.02D, 0.08D, 0.02D, 0.0D);
        }
    }

    private static void ensureArmorModifiers(ServerPlayer player) {
        AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
        if (armor == null) return;
        if (armor.getModifier(ARMOR_FLAT_ID) == null) {
            armor.addTransientModifier(new AttributeModifier(ARMOR_FLAT_ID, "Mord-Kaiser metal shards", 3.0D,
                    AttributeModifier.Operation.ADDITION));
        }
        if (armor.getModifier(ARMOR_PERCENT_ID) == null) {
            armor.addTransientModifier(new AttributeModifier(ARMOR_PERCENT_ID, "Mord-Kaiser metal shards percent", 0.10D,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }

    private static void removeArmorModifiers(ServerPlayer player) {
        AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
        if (armor == null) return;
        armor.removeModifier(ARMOR_FLAT_ID);
        armor.removeModifier(ARMOR_PERCENT_ID);
    }

    private static boolean isMord(ServerPlayer player) {
        return player.getTags().contains(ORIGIN_TAG);
    }

    private static net.minecraft.nbt.CompoundTag getData(ServerPlayer player) {
        return player.getPersistentData();
    }
}
