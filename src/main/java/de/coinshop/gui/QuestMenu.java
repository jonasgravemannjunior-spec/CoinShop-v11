package de.coinshop.gui;

import de.coinshop.CoinShopPlugin;
import de.coinshop.ItemUtil;
import de.coinshop.Messages;
import de.coinshop.QuestManager;
import de.coinshop.QuestManager.Progress;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Die täglichen Aufgaben und die Login-Serie. */
public final class QuestMenu extends Menu {

    private static final int[] QUEST_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    public QuestMenu(CoinShopPlugin plugin, Player viewer) {
        super(plugin, 4, plugin.messages().get("gui.quest.title"));
        Messages msg = plugin.messages();
        QuestManager quests = plugin.quests();
        UUID uuid = viewer.getUniqueId();

        // Login-Serie (oben Mitte)
        int streak = plugin.logins().currentStreak(uuid);
        int best = plugin.database().getStreak(uuid).map(s -> s.best()).orElse(0);
        set(4, ItemUtil.icon(Material.CLOCK, msg.get("gui.quest.streak-name"),
                msg.getList("gui.quest.streak-lore", "streak", streak, "best", best,
                        "bonus", plugin.logins().bonusFor(streak),
                        "next", plugin.logins().bonusFor(streak + 1))), null);

        if (!quests.enabled()) {
            set(22, ItemUtil.icon(Material.BARRIER, msg.get("gui.quest.disabled"), List.of()), null);
        } else {
            List<Progress> progress = quests.check(viewer);
            int done = 0;
            for (int i = 0; i < progress.size() && i < QUEST_SLOTS.length; i++) {
                Progress p = progress.get(i);
                if (p.done()) {
                    done++;
                }
                List<Component> lore = new ArrayList<>(msg.getList("gui.quest.lore",
                        "goal", QuestManager.amountText(p.quest()), "reward", p.quest().reward(),
                        "have", p.current(), "need", p.quest().amount(), "bar", bar(p.current(), p.quest().amount())));
                lore.add(p.done() ? msg.get("gui.quest.done") : msg.get("gui.quest.open"));
                set(QUEST_SLOTS[i], ItemUtil.icon(p.quest().icon(),
                        msg.get("gui.quest.name", "name", p.quest().name()), lore), null);
            }
            set(22, ItemUtil.icon(Material.NETHER_STAR, msg.get("gui.quest.bonus-name"),
                    msg.getList("gui.quest.bonus-lore", "done", done, "total", progress.size(),
                            "bonus", quests.bonusAll(),
                            "time", ItemUtil.formatDuration(plugin.serverShop().timeUntilReset().toMillis()))), null);
        }

        set(29, balanceIcon(viewer), incomeClick());
        set(31, backButton(), (player, click) -> new MainMenu(plugin).open(player));
        fillEmpty();
    }

    /** Ein Fortschrittsbalken aus 10 Kästchen. */
    private static Component bar(long have, long need) {
        int filled = (int) Math.max(0, Math.min(10, need <= 0 ? 10 : have * 10 / need));
        return Component.text("\u2588".repeat(filled), NamedTextColor.GREEN)
                .append(Component.text("\u2588".repeat(10 - filled), NamedTextColor.DARK_GRAY));
    }
}
