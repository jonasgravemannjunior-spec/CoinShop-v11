package de.coinshop;

import de.coinshop.gui.ProfileMenu;
import de.coinshop.gui.QuestMenu;
import de.coinshop.gui.TitleMenu;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Die Befehle /titel, /aufgaben und /profil [Spieler]. */
public final class ExtraCommands implements CommandExecutor, TabCompleter {

    private final CoinShopPlugin plugin;

    public ExtraCommands(CoinShopPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "only-players");
            return true;
        }
        try {
            switch (command.getName().toLowerCase()) {
                case "titel" -> new TitleMenu(plugin, player).open(player);
                case "aufgaben" -> new QuestMenu(plugin, player).open(player);
                case "profil" -> profile(player, args);
                default -> { }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Fehler bei /" + command.getName() + ": " + e);
            plugin.messages().send(player, "error");
        }
        return true;
    }

    /** /profil zeigt das eigene Profil, /profil Name das eines anderen Spielers (auch wenn er offline ist). */
    private void profile(Player player, String[] args) {
        UUID target = player.getUniqueId();
        if (args.length > 0) {
            Optional<UUID> found = plugin.database().findUuidByName(args[0]);
            if (found.isEmpty()) {
                plugin.messages().send(player, "player-unknown", "player", args[0]);
                return;
            }
            target = found.get();
        }
        new ProfileMenu(plugin, player, target).open(player);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("profil") && args.length == 1) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                    options.add(online.getName());
                }
            }
        }
        return options;
    }
}
