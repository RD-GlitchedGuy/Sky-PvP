package de.skypvp.kits;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Comparator;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SkyKitsPlugin extends JavaPlugin implements Listener {
    private static final String MENU_TITLE = "§8Kit-Auswahl";
    private static final String MENU_KEY = "menu.kits";
    private static final int[] MENU_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getConfig().isConfigurationSection("kits.mace")) {
            createDefaultMaceKit();
            saveConfig();
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("kits").setExecutor((sender, command, label, args) -> {
            if (args.length > 0 && args[0].equalsIgnoreCase("give")) {
                return giveKitCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§eVerwendung: /kits give @p <Kitname>");
                return true;
            }
            if (args.length > 0) {
                player.sendMessage("§cVerwendung: /kits oder /kits give <Kitname>");
                return true;
            }
            openKitMenu(player);
            return true;
        });
        getCommand("kits").setTabCompleter((sender, command, alias, args) -> completeKitsCommand(sender, args, true));
        getCommand("kit").setExecutor((sender, command, label, args) -> handleKitCommand(sender, args));
        getCommand("kit").setTabCompleter((sender, command, alias, args) -> completeKitsCommand(sender, args, false));
    }

    private List<String> completeKitsCommand(CommandSender sender, String[] args, boolean kitsAlias) {
        if (args.length == 1) {
            if (kitsAlias) return matching(List.of("give"), args[0]);
            List<String> actions = sender.hasPermission("skykits.admin")
                    ? List.of("give", "save", "delete") : List.of("give");
            return matching(actions, args[0]);
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (args.length == 2) {
                List<String> options = new java.util.ArrayList<>(kitIds());
                if (!(sender instanceof Player player) || player.isOp()) {
                    options.addAll(sender instanceof Player ? List.of("@p", "@a", "@r", "@s") : List.of("@p", "@a", "@r"));
                    Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(options::add);
                }
                return matching(options, args[1]);
            }
            if (args.length == 3 && (!(sender instanceof Player player) || player.isOp())) {
                return matching(kitIds(), args[2]);
            }
        } else if (!kitsAlias && args.length == 2 && args[0].equalsIgnoreCase("delete")
                && sender.hasPermission("skykits.admin")) {
            return matching(kitIds(), args[1]);
        }
        return List.of();
    }

    private List<String> kitIds() {
        var section = getConfig().getConfigurationSection("kits");
        return section == null ? List.of() : section.getKeys(false).stream().sorted().toList();
    }

    private List<String> matching(List<String> options, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .distinct().toList();
    }

    private boolean handleKitCommand(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e/kits §7öffnet die Kit-Auswahl.");
            sender.sendMessage("§e/kit give <name> §7gibt dir ein Kit.");
            sender.sendMessage("§e/kit save <name> §7speichert dein aktuelles Inventar als Kit.");
            sender.sendMessage("§e/kit delete <name> §7löscht ein Kit.");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("give")) return giveKitCommand(sender, Arrays.copyOfRange(args, 1, args.length));
        if (!action.equals("save") && !action.equals("delete")) {
            sender.sendMessage("§cVerwendung: /kit <give|save|delete> <name>");
            return true;
        }
        if (!sender.hasPermission("skykits.admin")) {
            sender.sendMessage("§cDafür hast du keine Berechtigung.");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage("§cVerwendung: /kit " + action + " <name>");
            return true;
        }
        String id = normalizeId(args[1]);
        if (id.isBlank()) {
            sender.sendMessage("§cDer Kit-Name ist ungültig.");
            return true;
        }
        String path = "kits." + id;
        if (action.equals("save")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Nur Spieler können ihr Inventar als Kit speichern.");
                return true;
            }
            savePlayerKit(id, player);
            sender.sendMessage("§aKit §f" + id + " §awurde gespeichert. Es erscheint in /kits.");
        } else if (!getConfig().isConfigurationSection(path)) {
            sender.sendMessage("§cDieses Kit gibt es nicht.");
        } else if (id.equals("mace")) {
            sender.sendMessage("§cDas eingebaute Kit „mace“ kann nicht gelöscht werden.");
        } else {
            getConfig().set(path, null);
            saveConfig();
            sender.sendMessage("§aKit §f" + id + " §awurde gelöscht.");
        }
        return true;
    }

    private boolean giveKitCommand(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cGib als Command-Block ein Ziel an, zum Beispiel: /kits give @p mace");
                return true;
            }
            if (!giveKit(player, args[0])) player.sendMessage("§cDieses Kit gibt es nicht.");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage("§cVerwendung: /kits give <Kitname> oder /kits give <Ziel> <Kitname>");
            return true;
        }
        if (sender instanceof Player player && !player.isOp()) {
            player.sendMessage("§cNur OPs dürfen ein Kit an ausgewählte Spieler vergeben.");
            return true;
        }

        List<Player> targets;
        try {
            targets = Bukkit.selectEntities(sender, args[0]).stream()
                    .filter(Player.class::isInstance)
                    .map(Player.class::cast)
                    .toList();
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§cUngültiger Spieler-Selektor: " + args[0]);
            return true;
        }
        if (targets.isEmpty()) {
            sender.sendMessage("§cDer Selektor hat keinen Online-Spieler gefunden.");
            return true;
        }
        int given = 0;
        for (Player target : targets) if (giveKit(target, args[1])) given++;
        if (given == 0) sender.sendMessage("§cDieses Kit gibt es nicht.");
        else sender.sendMessage("§aKit §f" + args[1] + " §aan " + given + " Spieler vergeben.");
        return true;
    }

    private void openKitMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 27, Component.text("Kit-Auswahl", NamedTextColor.DARK_GRAY));
        List<String> kitIds = getConfig().getConfigurationSection("kits").getKeys(false).stream()
                .sorted(Comparator.naturalOrder()).toList();
        for (int i = 0; i < Math.min(kitIds.size(), MENU_SLOTS.length); i++) {
            String id = kitIds.get(i);
            String path = "kits." + id;
            Material icon = Material.matchMaterial(getConfig().getString(path + ".icon", "DIAMOND_CHESTPLATE"));
            if (icon == null || !icon.isItem()) icon = Material.CHEST;
            ItemStack button = new ItemStack(icon);
            ItemMeta meta = button.getItemMeta();
            meta.displayName(Component.text(getConfig().getString(path + ".display-name", id), NamedTextColor.AQUA));
            meta.lore(List.of(Component.text("Klicken zum Ausrüsten", NamedTextColor.GRAY)));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            button.setItemMeta(meta);
            menu.setItem(MENU_SLOTS[i], button);
        }
        player.openInventory(menu);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!MENU_TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        int index = -1;
        for (int i = 0; i < MENU_SLOTS.length; i++) if (MENU_SLOTS[i] == slot) index = i;
        if (index < 0) return;
        List<String> ids = getConfig().getConfigurationSection("kits").getKeys(false).stream()
                .sorted(Comparator.naturalOrder()).toList();
        if (index >= ids.size()) return;
        if (giveKit(player, ids.get(index))) player.closeInventory();
    }

    private boolean giveKit(Player player, String id) {
        String path = "kits." + normalizeId(id);
        if (!getConfig().isConfigurationSection(path)) return false;
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(null);

        var itemsSection = getConfig().getConfigurationSection(path + ".items");
        if (itemsSection != null) for (String key : itemsSection.getKeys(false)) {
            int slot;
            try { slot = Integer.parseInt(key); } catch (NumberFormatException ignored) { continue; }
            ItemStack item = getConfig().getItemStack(path + ".items." + key);
            if (slot >= 0 && slot <= 35 && item != null) inventory.setItem(slot, item.clone());
        }
        inventory.setHelmet(getConfig().getItemStack(path + ".armor.helmet"));
        inventory.setChestplate(getConfig().getItemStack(path + ".armor.chestplate"));
        inventory.setLeggings(getConfig().getItemStack(path + ".armor.leggings"));
        inventory.setBoots(getConfig().getItemStack(path + ".armor.boots"));
        inventory.setItemInOffHand(getConfig().getItemStack(path + ".offhand"));
        player.sendMessage("§aKit §f" + getConfig().getString(path + ".display-name", id) + " §aausgerüstet.");
        return true;
    }

    private void savePlayerKit(String id, Player player) {
        String path = "kits." + id;
        getConfig().set(path, null);
        getConfig().set(path + ".display-name", id);
        getConfig().set(path + ".icon", "CHEST");
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] != null && !contents[slot].getType().isAir())
                getConfig().set(path + ".items." + slot, contents[slot].clone());
        }
        ItemStack[] armor = player.getInventory().getArmorContents();
        getConfig().set(path + ".armor.boots", cloneOrNull(armor[0]));
        getConfig().set(path + ".armor.leggings", cloneOrNull(armor[1]));
        getConfig().set(path + ".armor.chestplate", cloneOrNull(armor[2]));
        getConfig().set(path + ".armor.helmet", cloneOrNull(armor[3]));
        getConfig().set(path + ".offhand", cloneOrNull(player.getInventory().getItemInOffHand()));
        saveConfig();
    }

    private ItemStack cloneOrNull(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    private void createDefaultMaceKit() {
        String path = "kits.mace";
        getConfig().set(path + ".display-name", "Mace PvP");
        getConfig().set(path + ".icon", "MACE");

        ItemStack[] items = new ItemStack[36];
        items[0] = enchanted(Material.MACE, Map.of(Enchantment.WIND_BURST, 1, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1));
        items[1] = enchanted(Material.ELYTRA, Map.of(Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1));
        items[2] = new ItemStack(Material.FIREWORK_ROCKET, 64);
        items[3] = new ItemStack(Material.WIND_CHARGE, 64);
        items[4] = new ItemStack(Material.ENDER_PEARL, 16);
        items[5] = new ItemStack(Material.TOTEM_OF_UNDYING);
        items[6] = new ItemStack(Material.SHIELD);
        items[7] = enchanted(Material.NETHERITE_AXE, Map.of(Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1));
        items[8] = enchanted(Material.NETHERITE_SWORD, Map.of(Enchantment.SHARPNESS, 5, Enchantment.SWEEPING_EDGE, 3, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1));
        items[12] = new ItemStack(Material.TOTEM_OF_UNDYING);
        items[13] = new ItemStack(Material.TOTEM_OF_UNDYING);
        items[14] = new ItemStack(Material.TOTEM_OF_UNDYING);
        items[15] = potion(PotionType.STRONG_SWIFTNESS);
        items[16] = potion(PotionType.STRONG_HEALING);
        items[17] = potion(PotionType.STRONG_STRENGTH);
        items[21] = new ItemStack(Material.ENDER_PEARL, 16);
        items[22] = new ItemStack(Material.ENDER_PEARL, 16);
        items[24] = potion(PotionType.STRONG_SWIFTNESS);
        items[25] = potion(PotionType.STRONG_HEALING);
        items[26] = potion(PotionType.STRONG_STRENGTH);
        items[30] = new ItemStack(Material.ARROW, 64);
        items[31] = new ItemStack(Material.ENDER_PEARL, 16);
        items[33] = potion(PotionType.STRONG_SWIFTNESS);
        items[34] = potion(PotionType.STRONG_HEALING);
        items[35] = potion(PotionType.STRONG_STRENGTH);
        for (int slot = 0; slot < items.length; slot++) if (items[slot] != null)
            getConfig().set(path + ".items." + slot, items[slot]);

        ItemStack protectionHelmet = enchanted(Material.DIAMOND_HELMET, Map.of(Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1));
        ItemStack protectionChest = enchanted(Material.DIAMOND_CHESTPLATE, Map.of(Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1));
        ItemStack protectionLegs = enchanted(Material.DIAMOND_LEGGINGS, Map.of(Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1));
        ItemStack protectionBoots = enchanted(Material.DIAMOND_BOOTS, Map.of(Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1, Enchantment.FEATHER_FALLING, 4));
        getConfig().set(path + ".armor.helmet", protectionHelmet);
        getConfig().set(path + ".armor.chestplate", protectionChest);
        getConfig().set(path + ".armor.leggings", protectionLegs);
        getConfig().set(path + ".armor.boots", protectionBoots);
        getConfig().set(path + ".offhand", new ItemStack(Material.TOTEM_OF_UNDYING));
    }

    private ItemStack enchanted(Material material, Map<Enchantment, Integer> enchantments) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        enchantments.forEach((enchantment, level) -> meta.addEnchant(enchantment, level, true));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack potion(PotionType type) {
        ItemStack item = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setBasePotionType(type);
        item.setItemMeta(meta);
        return item;
    }

    private String normalizeId(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }
}

