package nl.semmmetjeee.bedrockmenubridge;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Presents virtual server inventories as native Bedrock simple forms. A selected
 * form button replays a normal left-click event on the original slot, so menu
 * plugins (notably DeluxeMenus) keep owning their commands and permissions.
 */
public final class BedrockMenuBridge extends JavaPlugin implements Listener {
    private List<Pattern> includeTitles;
    private List<Pattern> excludeTitles;
    private boolean virtualMenusOnly;
    private Map<String, String> iconOverrides;
    private final Set<java.util.UUID> replaying = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void reloadSettings() {
        reloadConfig();
        virtualMenusOnly = getConfig().getBoolean("virtual-menus-only", true);
        includeTitles = patterns(getConfig().getStringList("include-titles"));
        excludeTitles = patterns(getConfig().getStringList("exclude-titles"));
        iconOverrides = getConfig().getConfigurationSection("icon-overrides") == null
                ? Map.of() : getConfig().getConfigurationSection("icon-overrides").getValues(false)
                .entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> String.valueOf(entry.getValue())));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())) return;
        if (replaying.contains(player.getUniqueId())) return;
        Inventory top = event.getView().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING || top.getType() == InventoryType.PLAYER) return;
        if (virtualMenusOnly && top.getLocation() != null) return;

        String title = plain(event.getView().getTitle());
        if (!matches(includeTitles, title) || matches(excludeTitles, title)) return;

        List<MenuButton> buttons = new ArrayList<>();
        for (int slot = 0; slot < top.getSize(); slot++) {
            ItemStack item = top.getItem(slot);
            if (item != null && !item.getType().isAir()) buttons.add(new MenuButton(slot, item.clone()));
        }
        if (buttons.isEmpty()) return;

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> openForm(player, top, title, buttons));
    }

    private void openForm(Player player, Inventory inventory, String title, List<MenuButton> buttons) {
        if (!player.isOnline()) return;
        SimpleForm.Builder form = SimpleForm.builder().title(title.isBlank()
                ? getConfig().getString("fallback-title", "Menu") : title);
        for (MenuButton button : buttons) {
            form.button(button.label(), FormImage.Type.PATH, texturePath(button.item().getType()),
                    ignored -> Bukkit.getScheduler().runTask(this, () -> replayClick(player, inventory, button.slot())));
        }
        // Pass the finished form, rather than relying on Floodgate's builder
        // overload. This keeps the call compatible with all current Floodgate
        // implementations and ensures callbacks are retained.
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form.build());
    }

    private void replayClick(Player player, Inventory inventory, int slot) {
        if (!player.isOnline()) return;
        replaying.add(player.getUniqueId());
        try {
            player.openInventory(inventory);
            InventoryView view = player.getOpenInventory();
            Bukkit.getPluginManager().callEvent(new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        } finally {
            player.closeInventory();
            replaying.remove(player.getUniqueId());
        }
    }

    private String texturePath(Material material) {
        String overridden = iconOverrides.get(material.name());
        if (overridden != null) return overridden;
        String key = material.getKey().getKey();
        return material.isBlock() ? "textures/blocks/" + key : "textures/items/" + key;
    }

    private static String plain(String value) { return ChatColor.stripColor(value == null ? "" : value); }
    private static boolean matches(List<Pattern> patterns, String title) { return patterns.stream().anyMatch(pattern -> pattern.matcher(title).matches()); }
    private static List<Pattern> patterns(List<String> values) { return values.stream().map(Pattern::compile).toList(); }
    private record MenuButton(int slot, ItemStack item) {
        private String label() {
            String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                    ? ChatColor.stripColor(item.getItemMeta().getDisplayName()) : item.getType().translationKey();
            return name + (item.hasItemMeta() && item.getItemMeta().hasLore() ? "\n" + String.join("\n", item.getItemMeta().getLore().stream().map(ChatColor::stripColor).toList()) : "");
        }
    }
}