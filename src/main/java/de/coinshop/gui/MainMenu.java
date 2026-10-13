package de.coinshop.gui;

import de.coinshop.CoinShopPlugin;
import de.coinshop.ItemUtil;
import de.coinshop.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Das Hauptmenü: Shops, Glücksrad, Investitionen, Ranglisten, Kollektionen, geheime Erfolge, Titel und Aufgaben. */
public final class MainMenu extends Menu {

    public MainMenu(CoinShopPlugin plugin) {
        super(plugin, 5, plugin.messages().get("gui.main.title"));
        Messages msg = plugin.messages();
        int hour = plugin.serverShop().resetHour();

        // Obere Reihe: die vier Bereiche (Server-Shop, Spieler-Shop, Casino, Investitionen)
        set(10, ItemUtil.icon(Material.ENDER_CHEST, msg.get("gui.main.server-name"),
                        msg.getList("gui.main.server-lore", "hour", hour)),
                (player, click) -> new ServerShopMenu(plugin, player).open(player));

        set(12, ItemUtil.icon(Material.EMERALD, msg.get("gui.main.player-name"),
                        msg.getList("gui.main.player-lore")),
                (player, click) -> new PlayerShopMenu(plugin, player, 0).open(player));

        set(14, ItemUtil.icon(Material.NETHER_STAR, msg.get("gui.main.casino-name"),
                        msg.getList("gui.main.casino-lore")),
                (player, click) -> new CasinoMenu(plugin, player).open(player));

        set(16, ItemUtil.icon(Material.GOLD_BLOCK, msg.get("gui.main.invest-name"),
                        msg.getList("gui.main.invest-lore")),
                (player, click) -> new InvestHubMenu(plugin, player).open(player));

        // Mittlere Reihe: Ranglisten und geheime Erfolge
        set(19, ItemUtil.icon(Material.GOLDEN_HELMET, msg.get("gui.main.rank-name"),
                        msg.getList("gui.main.rank-lore")),
                (player, click) -> new RankingMenu(plugin, player).open(player));

        set(21, ItemUtil.icon(Material.BOOKSHELF, msg.get("gui.main.collection-name"),
                        msg.getList("gui.main.collection-lore")),
                (player, click) -> new CollectionsMenu(plugin, player).open(player));

        set(23, ItemUtil.icon(Material.ENDER_EYE, msg.get("gui.main.secret-name"),
                        msg.getList("gui.main.secret-lore")),
                (player, click) -> new SecretMenu(plugin, player).open(player));

        set(25, ItemUtil.icon(Material.NAME_TAG, msg.get("gui.main.title-name"),
                        msg.getList("gui.main.title-lore")),
                (player, click) -> new TitleMenu(plugin, player).open(player));

        // Untere Mitte: tägliche Aufgaben und Spieler-Profil
        set(32, ItemUtil.icon(Material.PLAYER_HEAD, msg.get("gui.main.profile-name"),
                        msg.getList("gui.main.profile-lore")),
                (player, click) -> new ProfileMenu(plugin, player, player.getUniqueId()).open(player));

        set(30, ItemUtil.icon(Material.WRITABLE_BOOK, msg.get("gui.main.quest-name"),
                        msg.getList("gui.main.quest-lore")),
                (player, click) -> new QuestMenu(plugin, player).open(player));

        // Untere Reihe: Schließen (Kontostand und Shop-Konto kommen beim Öffnen dazu)
        set(40, ItemUtil.icon(Material.BARRIER, msg.get("gui.close-name"), List.of()),
                (player, click) -> player.closeInventory());

        fillEmpty();
    }

    @Override
    public void open(Player player) {
        Messages msg = plugin.messages();
        // Diese beiden Anzeigen ändern sich ständig, deshalb erst beim Öffnen einsetzen
        set(38, balanceIcon(player), incomeClick());
        set(42, ItemUtil.icon(Material.GOLD_INGOT, msg.get("gui.main.shop-account-name"),
                msg.getList("gui.main.shop-account-lore", "coins", plugin.database().getShopAccount())), null);
        super.open(player);
    }
}
