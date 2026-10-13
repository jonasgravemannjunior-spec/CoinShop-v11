package de.coinshop.gui;

import de.coinshop.CoinShopPlugin;
import de.coinshop.InvestmentManager;
import de.coinshop.InvestmentManager.SetDef;
import de.coinshop.ItemUtil;
import de.coinshop.Messages;
import de.coinshop.RankingManager.Placed;
import de.coinshop.RankingManager.Rank;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Deine persönliche Einkommens-Aufstellung (Klick auf den Kontostand):
 * Grundeinkommen, Login-Serie, jedes Set mit Einkommen und jede Rangliste mit Bonus - und die Summe.
 */
public final class MyIncomeMenu extends Menu {

    public MyIncomeMenu(CoinShopPlugin plugin, Player viewer) {
        super(plugin, 6, plugin.messages().get("gui.myincome.title"));
        Messages msg = plugin.messages();
        InvestmentManager invest = plugin.investments();
        UUID uuid = viewer.getUniqueId();
        var owners = invest.ownershipMap();

        int slot = 0;
        long total = 0;

        // 1. Grundeinkommen
        long base = invest.baseIncome();
        total += base;
        set(slot++, ItemUtil.icon(Material.GOLD_NUGGET, msg.get("gui.myincome.base-name"),
                msg.getList("gui.myincome.base-lore", "coins", base)), null);

        // 2. Login-Serie
        long login = plugin.logins().bonusOf(uuid);
        total += login;
        set(slot++, ItemUtil.icon(Material.SUNFLOWER, msg.get("gui.myincome.login-name"),
                msg.getList("gui.myincome.login-lore", "coins", login,
                        "days", plugin.logins().currentStreak(uuid))), null);

        // 3. Jedes Set, das etwas einbringt
        for (SetDef set : invest.sets()) {
            int owned = invest.ownedCount(set, uuid, owners);
            long income = invest.setIncome(set, owned);
            if (owned <= 0 || income <= 0 || slot >= 45) {
                continue;
            }
            total += income;
            Material icon = set.items().isEmpty() ? Material.GOLD_BLOCK : set.items().get(0);
            set(slot++, ItemUtil.icon(icon, msg.get("gui.myincome.set-name", "name", set.name()),
                    msg.getList("gui.myincome.set-lore", "owned", owned, "total", set.items().size(),
                            "coins", income)), null);
        }

        // 4. Jede Rangliste, in der du einen Bonus bekommst
        for (Rank rank : Rank.values()) {
            Placed info = plugin.rankings().info(rank, uuid);
            if (info == null || info.bonus() <= 0 || slot >= 45) {
                continue;
            }
            total += info.bonus();
            Component name = msg.get("gui.rank.cat." + rank.id() + ".name");
            set(slot++, ItemUtil.icon(rank.icon(), msg.get("gui.myincome.rank-name", "name", name),
                    msg.getList("gui.myincome.rank-lore", "place", info.place(), "coins", info.bonus())), null);
        }

        // Unten: Zurück | Summe | Kontostand
        set(48, backButton(), (player, click) -> new MainMenu(plugin).open(player));
        set(49, ItemUtil.icon(Material.EMERALD, msg.get("gui.myincome.total-name"),
                msg.getList("gui.myincome.total-lore", "coins", total,
                        "hour", plugin.serverShop().resetHour())), null);
        set(50, balanceIcon(viewer), null);
        fillEmpty();
    }
}
