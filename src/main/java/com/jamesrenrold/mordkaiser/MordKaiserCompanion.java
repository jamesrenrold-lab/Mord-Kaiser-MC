package com.jamesrenrold.mordkaiser;

import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

@Mod(MordKaiserCompanion.MOD_ID)
public final class MordKaiserCompanion {
    public static final String MOD_ID = "mord_kaiser";

    private static final String ORIGIN_TAG = "mord_kaiser";
    private static final String SOUL_DAMAGE = "MordSoulDamage";
    private static final String SOUL_HUD = "MordSoulHud";
    private static final String MACE_CHARGES = "MordMaceCharges";
    private static final String SHIELD_COOLDOWN = "MordShieldCooldown";
    private static final String METAL_UNTIL = "MordMetalUntil";
    private static final String METAL_COOLDOWN = "MordMetalCooldown";

    private static final int SOUL_RESOURCE_MAX = 100;
    private static final int MACE_MAX = 3;
    private static final long SHIELD_COOLDOWN_TICKS = 240L;
    private static final long METAL_DURATION_TICKS = 300L;
    private static final long METAL_COOLDOWN_TICKS = 900L;
    private static final double METAL_RADIUS = 3.0D;
    private static final UUID ARMOR_FLAT_ID = UUID.fromString("d4e6bbf8-3e5c-4a09-9e9c-bd3dbf1d6b01");
    private static final UUID ARMOR_PERCENT_ID = UUID.fromString("497559cc-d50c-4ae8-9e02-12f33a0f4d02");

    public MordKaiserCompanion() {
        MinecraftForge.EVENT_BUS.register(this);
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
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof ServerPlayer player) || !isMord(player) || event.getEntity() == player) return;

        // Mace of Spades counts successful melee hits only: both source entities
        // must be the player, excluding projectiles and spell entities.
        if (event.getSource().getDirectEntity() == player) {
            int charges = getData(player).getInt(MACE_CHARGES);
            if (charges > 0) {
                double spellPower = getSpellPower(player);
                event.setAmount(event.getAmount() + (float) (spellPower * 0.30D));
                charges--;
                getData(player).putInt(MACE_CHARGES, charges);
                syncMaceResource(player, charges);
                if (charges == 0) {
                    player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 300, 0, false, true, true));
                    player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 300, 0, false, true, true));
                    player.displayClientMessage(Component.literal("Mace of Spades completed: Speed I and Strength I.")
                            .withStyle(ChatFormatting.DARK_RED), true);
                }
            }
        }

        if (event.getAmount() > 0.0F) {
            addSoulDamage(player, event.getAmount());
        }
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
            long metalUntil = getData(player).getLong(METAL_UNTIL);
            if (metalUntil > now) {
                ensureArmorModifiers(player);
                if (now % 10L == 0L) applyMetalAura(player);
            } else {
                removeArmorModifiers(player);
            }
        }
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
        data.putInt(MACE_CHARGES, MACE_MAX);
        syncMaceResource(player, MACE_MAX);
        player.displayClientMessage(Component.literal("Mace of Spades armed: land three melee hits.")
                .withStyle(ChatFormatting.DARK_RED), true);
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

    private static void syncMaceResource(ServerPlayer player, int value) {
        runResourceCommand(player, "mord_kaiser:mace_charges", Math.max(0, Math.min(MACE_MAX, value)));
    }

    private static void runResourceCommand(ServerPlayer player, String resource, int value) {
        String command = "resource set @s " + resource + " " + value;
        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(2).withSuppressedOutput(), command);
    }

    private static double getSpellPower(ServerPlayer player) {
        AttributeInstance attribute = player.getAttribute(AttributeRegistry.SPELL_POWER.get());
        return attribute == null ? 0.0D : Math.max(0.0D, attribute.getValue());
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
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT,
                player.getX(), player.getY() + 1.0D, player.getZ(), 8, 0.7D, 0.6D, 0.7D, 0.0D);
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
