package me.sandevistan;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class SandevistanPlugin extends JavaPlugin implements Listener {

    private NamespacedKey key;
    private final Set<UUID> active = new HashSet<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        key = new NamespacedKey(this, "sandevistan");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private boolean hasAbility(Player p) {
        return p.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // ---------- Команда (только админ) ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("sandevistan.admin")) {
            sender.sendMessage("§cУ вас нет прав.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§eИспользование: /" + label + " <give|take|check> <игрок>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("§cИгрок не найден.");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "give" -> {
                target.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
                sender.sendMessage("§aСандевистан выдан игроку " + target.getName());
                target.sendMessage("§bВы получили Сандевистан! Нажмите §eF§b для активации.");
            }
            case "take" -> {
                target.getPersistentDataContainer().remove(key);
                active.remove(target.getUniqueId());
                sender.sendMessage("§aСандевистан забран у игрока " + target.getName());
                target.sendMessage("§cУ вас забрали Сандевистан.");
            }
            case "check" -> sender.sendMessage("§e" + target.getName() + ": "
                    + (hasAbility(target) ? "§aесть способность" : "§cнет способности"));
            default -> sender.sendMessage("§cНеизвестная подкоманда.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("sandevistan.admin")) return List.of();
        if (args.length == 1) return List.of("give", "take", "check");
        if (args.length == 2) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        return List.of();
    }

    // ---------- Активация на клавишу F ----------
    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!hasAbility(p)) return;
        e.setCancelled(true); // предметы не меняются местами

        UUID id = p.getUniqueId();
        if (active.contains(id)) return;

        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(id, 0L);
        if (now < readyAt) {
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    "§cПерезарядка: " + ((readyAt - now) / 1000 + 1) + " с"));
            return;
        }
        activate(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        active.remove(e.getPlayer().getUniqueId());
    }

    // ---------- Сама способность ----------
    private void activate(Player p) {
        final double radius = getConfig().getDouble("radius", 30);
        final int durationTicks = getConfig().getInt("duration", 10) * 20;
        final int cooldownSec = getConfig().getInt("cooldown", 30);
        final int slowLevel = getConfig().getInt("slow-level", 4);
        final int speedLevel = getConfig().getInt("speed-level", 3);
        final double projFactor = getConfig().getDouble("projectile-factor", 0.3);

        UUID id = p.getUniqueId();
        active.add(id);
        p.sendMessage("§b§lСАНДЕВИСТАН АКТИВИРОВАН");

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || ticks >= durationTicks || !active.contains(id)) {
                    finish();
                    return;
                }

                // Ускорение владельца
                if (ticks % 10 == 0) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 30, speedLevel, false, false, false));
                }

                for (Entity en : p.getNearbyEntities(radius, radius, radius)) {
                    if (en.getLocation().distanceSquared(p.getLocation()) > radius * radius) continue;

                    if (en instanceof LivingEntity le) {
                        // Другие владельцы Сандевистана не замедляются
                        if (le instanceof Player other && active.contains(other.getUniqueId())) continue;
                        if (ticks % 10 == 0) {
                            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, slowLevel, false, false, false));
                        }
                    } else if (en instanceof Projectile proj) {
                        if (p.equals(proj.getShooter())) continue;
                        Vector v = proj.getVelocity();
                        proj.setVelocity(v.multiply(projFactor));
                    }
                }
                ticks++;
            }

            private void finish() {
                cancel();
                active.remove(id);
                cooldowns.put(id, System.currentTimeMillis() + cooldownSec * 1000L);
                if (p.isOnline()) {
                    p.removePotionEffect(PotionEffectType.SPEED);
                    p.sendMessage("§7Сандевистан отключён.");
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }
}
