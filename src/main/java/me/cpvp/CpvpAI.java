package me.cpvp;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

public class CpvpAI implements ModInitializer {

    private static final Map<UUID, HuskEntity> BOTS = new HashMap<>();   // oyuncu -> bot
    private static final Map<UUID, UUID> OWNER = new HashMap<>();        // bot -> oyuncu
    private static final Map<UUID, Integer> POPS = new HashMap<>();      // bot totem sayisi
    private static final Random RND = new Random();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
            dispatcher.register(CommandManager.literal("cpvp")
                .then(CommandManager.literal("ai")
                    .then(CommandManager.literal("vs").executes(ctx -> {
                        startDuel(ctx.getSource().getPlayerOrThrow());
                        return 1;
                    }))
                    .then(CommandManager.literal("stop").executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                        end(p.getUuid());
                        p.sendMessage(Text.literal("§cDuello bitirildi."), false);
                        return 1;
                    })))));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Map.Entry<UUID, HuskEntity> e : new ArrayList<>(BOTS.entrySet())) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
                HuskEntity bot = e.getValue();
                if (p == null || !p.isAlive() || !bot.isAlive()) { end(e.getKey()); continue; }

                ServerWorld w = (ServerWorld) bot.getEntityWorld();
                bot.setTarget(p);
                double dist = bot.distanceTo(p);

                // Kristal vurusu: yakin mesafede ~1 saniyede bir
                if (dist < 7 && bot.age % 20 == 0 && RND.nextInt(3) != 0) {
                    w.createExplosion(bot, p.getX(), p.getY(), p.getZ(), 4f, World.ExplosionSourceType.NONE);
                }
                // Canı azsa altin elma etkisi
                if (bot.getHealth() < 14 && bot.age % 60 == 0) {
                    bot.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 100, 1));
                    bot.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 2400, 0));
                }
                // Cok uzaktaysa pearl gibi yaklas
                if (dist > 14 && bot.age % 100 == 0) {
                    Vec3d back = p.getPos().subtract(p.getRotationVector().multiply(3, 0, 3));
                    bot.requestTeleport(back.x, back.y, back.z);
                }
            }
        });

        // Bot kendi patlamasindan hasar almasin
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (OWNER.containsKey(entity.getUuid())
                    && source.isIn(DamageTypeTags.IS_EXPLOSION)
                    && source.getAttacker() == entity) return false;
            return true;
        });

        // Totem: bot 2 kez olumden doner
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            UUID id = entity.getUuid();
            if (entity instanceof HuskEntity h && OWNER.containsKey(id) && POPS.getOrDefault(id, 0) < 2) {
                POPS.merge(id, 1, Integer::sum);
                h.setHealth(20f);
                h.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 40, 1));
                h.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 100, 1));
                ((ServerWorld) h.getEntityWorld()).sendEntityStatus(h, (byte) 35);
                ServerPlayerEntity p = ((ServerWorld) h.getEntityWorld()).getServer()
                        .getPlayerManager().getPlayer(OWNER.get(id));
                if (p != null) p.sendMessage(Text.literal("§6AI totem patlatti! (" + POPS.get(id) + "/2)"), false);
                return false;
            }
            return true;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof HuskEntity h && OWNER.containsKey(h.getUuid())) {
                UUID owner = OWNER.get(h.getUuid());
                ServerPlayerEntity p = ((ServerWorld) h.getEntityWorld()).getServer().getPlayerManager().getPlayer(owner);
                if (p != null) p.sendMessage(Text.literal("§a§lKAZANDIN! §7AI yenildi."), false);
                end(owner);
            } else if (entity instanceof ServerPlayerEntity p && BOTS.containsKey(p.getUuid())) {
                p.sendMessage(Text.literal("§c§lKAYBETTIN! §7AI kazandi."), false);
                end(p.getUuid());
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> end(handler.getPlayer().getUuid()));
    }

    private static void startDuel(ServerPlayerEntity p) {
        end(p.getUuid());
        ServerWorld w = (ServerWorld) p.getEntityWorld();
        giveKit(p, w);

        HuskEntity bot = EntityType.HUSK.create(w, SpawnReason.COMMAND);
        if (bot == null) return;
        Vec3d dir = p.getRotationVector().multiply(1, 0, 1).normalize().multiply(8);
        bot.refreshPositionAndAngles(p.getX() + dir.x, p.getY(), p.getZ() + dir.z, p.getYaw() + 180f, 0f);
        bot.setCustomName(Text.literal("§cCPVP AI"));
        bot.setCustomNameVisible(true);
        bot.setPersistent();
        bot.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(40);
        bot.setHealth(40f);
        bot.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, Integer.MAX_VALUE, 1, false, false));
        bot.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));

        bot.equipStack(EquipmentSlot.HEAD, armor(w, Items.NETHERITE_HELMET));
        bot.equipStack(EquipmentSlot.CHEST, armor(w, Items.NETHERITE_CHESTPLATE));
        bot.equipStack(EquipmentSlot.LEGS, armor(w, Items.NETHERITE_LEGGINGS));
        bot.equipStack(EquipmentSlot.FEET, armor(w, Items.NETHERITE_BOOTS));
        bot.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
        bot.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        for (EquipmentSlot s : EquipmentSlot.values()) bot.setEquipmentDropChance(s, 0f);

        w.spawnEntity(bot);
        bot.setTarget(p);
        BOTS.put(p.getUuid(), bot);
        OWNER.put(bot.getUuid(), p.getUuid());
        POPS.put(bot.getUuid(), 0);
        p.sendMessage(Text.literal("§aKit verildi! §eVS basladi, bol sans."), false);
    }

    private static void giveKit(ServerPlayerEntity p, ServerWorld w) {
        p.getInventory().clear();
        p.clearStatusEffects();
        p.setHealth(p.getMaxHealth());
        p.getHungerManager().setFoodLevel(20);

        p.equipStack(EquipmentSlot.HEAD, armor(w, Items.NETHERITE_HELMET));
        p.equipStack(EquipmentSlot.CHEST, armor(w, Items.NETHERITE_CHESTPLATE));
        p.equipStack(EquipmentSlot.LEGS, armor(w, Items.NETHERITE_LEGGINGS));
        p.equipStack(EquipmentSlot.FEET, armor(w, Items.NETHERITE_BOOTS));

        ItemStack sword = new ItemStack(Items.NETHERITE_SWORD);
        enchant(sword, w, Enchantments.SHARPNESS, 5);
        enchant(sword, w, Enchantments.UNBREAKING, 3);

        p.getInventory().insertStack(sword);
        p.getInventory().insertStack(new ItemStack(Items.END_CRYSTAL, 64));
        p.getInventory().insertStack(new ItemStack(Items.OBSIDIAN, 64));
        p.getInventory().insertStack(new ItemStack(Items.GOLDEN_APPLE, 16));
        p.getInventory().insertStack(new ItemStack(Items.ENDER_PEARL, 16));
        p.getInventory().insertStack(new ItemStack(Items.TOTEM_OF_UNDYING, 1));
        p.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
    }

    private static ItemStack armor(ServerWorld w, Item item) {
        ItemStack s = new ItemStack(item);
        enchant(s, w, Enchantments.PROTECTION, 4);
        enchant(s, w, Enchantments.UNBREAKING, 3);
        return s;
    }

    private static void enchant(ItemStack s, ServerWorld w, RegistryKey<Enchantment> key, int lvl) {
        s.addEnchantment(w.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(key), lvl);
    }

    private static void end(UUID player) {
        HuskEntity bot = BOTS.remove(player);
        if (bot != null) {
            OWNER.remove(bot.getUuid());
            POPS.remove(bot.getUuid());
            bot.discard();
        }
    }
}
