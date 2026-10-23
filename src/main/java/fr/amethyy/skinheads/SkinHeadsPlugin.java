package fr.amethyy.skinheads;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * SkinHeads : génère pour chaque joueur un item ItemsAdder dont la texture
 * est le buste de son skin (via une API externe).
 */
public final class SkinHeadsPlugin extends JavaPlugin implements Listener {

    private SkinStore store;
    private SkinService service;
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new SkinStore(this);
        this.store.load();
        this.service = new SkinService(this, store);

        Bukkit.getPluginManager().registerEvents(this, this);

        SkinCommand cmd = new SkinCommand(this);
        getCommand("skinheads").setExecutor(cmd);
        getCommand("skinheads").setTabCompleter(cmd);

        // On reécrit toujours le YAML au démarrage pour être cohérent
        service.writeItemsFile();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SkinHeads-Scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduleNextMidnight();
        getLogger().info("SkinHeads activé : " + store.size() + " joueur(s) enregistré(s).");
    }

    @Override
    public void onDisable() {
        if (scheduler != null) scheduler.shutdownNow();
        if (store != null) store.save();
    }

    public SkinStore store() { return store; }
    public SkinService service() { return service; }

    public void reload() {
        reloadConfig();
        service.writeItemsFile();
    }

    /** Programme le prochain déclenchement à l'heure configurée (minuit par défaut). */
    private void scheduleNextMidnight() {
        FileConfiguration c = getConfig();
        ZoneId zone;
        try {
            zone = ZoneId.of(c.getString("schedule.timezone", "Europe/Paris"));
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        LocalTime time;
        try {
            time = LocalTime.parse(c.getString("schedule.daily-time", "00:00"));
        } catch (Exception e) {
            time = LocalTime.MIDNIGHT;
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = now.with(time);
        if (!next.isAfter(now)) next = next.plusDays(1);
        long delay = Duration.between(now, next).toMillis();
        final ZoneId z = zone;
        scheduler.schedule(() -> {
            try {
                runDaily(z);
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Erreur lors de la tâche quotidienne", t);
            } finally {
                if (!scheduler.isShutdown()) scheduleNextMidnight();
            }
        }, delay, TimeUnit.MILLISECONDS);
        getLogger().info("Prochaine mise à jour : " + next);
    }

    /**
     * Tâche de minuit. "Dimanche soir à minuit" = le moment où on passe de
     * dimanche à lundi, c'est-à-dire que le jour qui vient de se terminer
     * est weekly-day.
     */
    private void runDaily(ZoneId zone) {
        String wd = getConfig().getString("schedule.weekly-day", "SUNDAY");
        DayOfWeek weekly;
        try {
            weekly = DayOfWeek.valueOf(wd.toUpperCase());
        } catch (Exception e) {
            weekly = DayOfWeek.SUNDAY;
        }
        DayOfWeek yesterday = ZonedDateTime.now(zone).minusMinutes(5).getDayOfWeek();
        boolean weeklyCheck = (yesterday == weekly);

        boolean changed = false;
        if (weeklyCheck) {
            getLogger().info("Vérification hebdomadaire des changements de skin...");
            changed |= service.refreshAllSkins();
        }
        // Génère les images des nouveaux joueurs en attente
        changed |= service.processPending();

        if (changed || dirty.getAndSet(false)) {
            service.writeItemsFile();
            service.runZipCommand();
        } else {
            getLogger().info("Rien à mettre à jour cette nuit, pas de /iazip.");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        var p = e.getPlayer();
        // Nouveau joueur : on l'enregistre immédiatement (CMD réservé), image générée en asynchrone
        if (!store.has(p.getUniqueId())) {
            store.register(p.getUniqueId(), p.getName());
            store.save();
            dirty.set(true);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                boolean ok = service.downloadFor(p.getUniqueId(), p.getName());
                if (ok) {
                    service.writeItemsFile();
                    getLogger().info("Image générée pour " + p.getName()
                            + " (CMD " + store.get(p.getUniqueId()).customModelData + "). Active après /iazip.");
                }
            });
        } else {
            store.updateName(p.getUniqueId(), p.getName());
        }
    }
}
