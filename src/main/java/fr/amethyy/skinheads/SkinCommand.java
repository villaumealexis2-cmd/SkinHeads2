package fr.amethyy.skinheads;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SkinCommand implements CommandExecutor, TabCompleter {

    private final SkinHeadsPlugin plugin;

    public SkinCommand(SkinHeadsPlugin plugin) { this.plugin = plugin; }

    private void msg(CommandSender s, String m) {
        String p = plugin.getConfig().getString("messages.prefix", "&8[&dSkinHeads&8] &7");
        s.sendMessage(ChatColor.translateAlternateColorCodes('&', p + m));
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) {
            msg(s, "/skinheads <reload|regen|check|give|info>");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reload();
                msg(s, "Configuration rechargée.");
            }
            case "regen" -> {
                msg(s, "Génération des images manquantes + /iazip si besoin...");
                plugin.service().runDailyNow(false).thenAccept(ch ->
                        Bukkit.getScheduler().runTask(plugin, () ->
                                msg(s, ch ? "Terminé, /iazip lancé." : "Rien à mettre à jour.")));
            }
            case "check" -> {
                msg(s, "Vérification des changements de skin de tous les joueurs...");
                plugin.service().runDailyNow(true).thenAccept(ch ->
                        Bukkit.getScheduler().runTask(plugin, () ->
                                msg(s, ch ? "Skins mis à jour, /iazip lancé." : "Aucun changement.")));
            }
            case "info" -> {
                if (a.length < 2) { msg(s, "/skinheads info <joueur>"); return true; }
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                var e = plugin.store().get(op.getUniqueId());
                if (e == null) { msg(s, "Joueur inconnu."); return true; }
                msg(s, e.name + " -> custom_model_data " + e.customModelData
                        + ", id " + plugin.getConfig().getString("itemsadder.namespace", "amethyy")
                        + ":" + e.itemId(plugin.getConfig().getString("itemsadder.id-prefix", "skin_")));
            }
            case "give" -> {
                // /skinheads give <joueur-cible> [joueur-profil]  -> utilise /iagive
                if (a.length < 2) { msg(s, "/skinheads give <cible> [profil]"); return true; }
                String target = a[1];
                String profil = a.length >= 3 ? a[2] : a[1];
                OfflinePlayer op = Bukkit.getOfflinePlayer(profil);
                var e = plugin.store().get(op.getUniqueId());
                if (e == null || e.hash == null) { msg(s, "Aucune image pour ce joueur."); return true; }
                String id = plugin.getConfig().getString("itemsadder.namespace", "amethyy")
                        + ":" + e.itemId(plugin.getConfig().getString("itemsadder.id-prefix", "skin_"));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "iagive " + target + " " + id + " 1");
                msg(s, "Item donné.");
            }
            default -> msg(s, "Sous-commande inconnue.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : List.of("reload", "regen", "check", "give", "info"))
                if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
        } else if (a.length >= 2 && (a[0].equalsIgnoreCase("give") || a[0].equalsIgnoreCase("info"))) {
            for (var p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[a.length - 1].toLowerCase(Locale.ROOT)))
                    out.add(p.getName());
        }
        return out;
    }
}
