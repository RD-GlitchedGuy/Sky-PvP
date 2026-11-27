package de.skypvp.kits;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(createKitsCommand(), "Kit-System für Sky PvP", List.of("kits")));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createKitsCommand() {
        return Commands.literal("kit")
                .executes(context -> openMenu(context.getSource().getSender()))
                .then(Commands.literal("give")
                        .then(Commands.argument("kit", StringArgumentType.word())
                                .suggests((context, builder) -> suggestKitIds(builder))
                                .executes(context -> giveKitToSender(context)))
                        .then(Commands.argument("targets", ArgumentTypes.entities())
                                .requires(context -> maySelectTargets(context.getSender()))
                                .then(Commands.argument("kit", StringArgumentType.word())
                                        .suggests((context, builder) -> suggestKitIds(builder))
                                        .executes(this::giveKitToTargets))))
                .then(Commands.literal("save")
                        .requires(context -> context.getSender().hasPermission("skykits.admin"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> suggestKitIds(builder))
                                .executes(context -> saveKitFromSender(context))))
                .then(Commands.literal("delete")
                        .requires(context -> context.getSender().hasPermission("skykits.admin"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> suggestKitIds(builder))
                                .executes(context -> deleteKit(context))));
    }

    private List<String> kitIds() {
        var section = getConfig().getConfigurationSection("kits");
        return section == null ? List.of() : section.getKeys(false).stream().sorted().toList();
    }

    private CompletableFuture<Suggestions> suggestKitIds(SuggestionsBuilder builder) {
        String prefix = builder.getRemainingLowerCase();
        for (String id : kitIds()) {
            if (id.toLowerCase(Locale.ROOT).startsWith(prefix)) builder.suggest(id);
        }
        return builder.buildFuture();
    }

    private boolean maySelectTargets(CommandSender sender) {
        return !(sender instanceof Player player) || player.isOp();
    }

    private int openMenu(CommandSender sender) {
        if (sender instanceof Player player) openKitMenu(player);
        else sender.sendMessage("§eVerwendung: /kits give <Ziel> <Kitname>");
        return Command.SINGLE_SUCCESS;
    }

    private int giveKitToSender(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cGib als Command-Block ein Ziel an: /kits give @p mace");
            return 0;
        }
        String id = StringArgumentType.getString(context, "kit");
        if (!giveKit(player, id)) sender.sendMessage("§cDieses Kit gibt es nicht.");
        return Command.SINGLE_SUCCESS;
    }

    private int giveKitToTargets(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSender sender = context.getSource().getSender();
        EntitySelectorArgumentResolver resolver = context.getArgument("targets", EntitySelectorArgumentResolver.class);
        List<Player> targets = resolver.resolve(context.getSource()).stream()
                .filter(Player.class::isInstance).map(Player.class::cast).toList();
        if (targets.isEmpty()) {
            sender.sendMessage("§cDer Selector hat keine Spieler ausgewählt.");
            return 0;
        }
        String id = StringArgumentType.getString(context, "kit");
        int given = 0;
        for (Player target : targets) if (giveKit(target, id)) given++;
        if (given == 0) sender.sendMessage("§cDieses Kit gibt es nicht.");
        else sender.sendMessage("§aKit §f" + id + " §aan " + given + " Spieler vergeben.");
        return given;
    }

    private int saveKitFromSender(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cNur Spieler können ihr Inventar als Kit speichern.");
            return 0;
        }
        String id = normalizeId(StringArgumentType.getString(context, "name"));
        if (id.isBlank()) {
            sender.sendMessage("§cDer Kit-Name ist ungültig.");
            return 0;
        }
        savePlayerKit(id, player);
        sender.sendMessage("§aKit §f" + id + " §awurde gespeichert. Es erscheint in /kits.");
        return Command.SINGLE_SUCCESS;
    }

    private int deleteKit(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String id = normalizeId(StringArgumentType.getString(context, "name"));
        String path = "kits." + id;
        if (!getConfig().isConfigurationSection(path)) {
            sender.sendMessage("§cDieses Kit gibt es nicht.");
        } else if (id.equals("mace")) {
            sender.sendMessage("§cDas eingebaute Kit „mace“ kann nicht gelöscht werden.");
        } else {
            getConfig().set(path, null);
            saveConfig();
            sender.sendMessage("§aKit §f" + id + " §awurde gelöscht.");
        }
        return Command.SINGLE_SUCCESS;
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

