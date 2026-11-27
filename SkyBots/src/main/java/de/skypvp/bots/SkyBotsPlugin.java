package de.skypvp.bots;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SkyBotsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final int MAX_BOTS_PER_PLAYER = 5;
    private static final int[] LIST_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int[] LOADOUT_SLOTS = {45, 46, 47, 48, 49};

    private final Map<Integer, BotData> bots = new HashMap<>();
    private final Map<Inventory, MenuSession> sessions = new IdentityHashMap<>();
    private final Map<Integer, UUID> activeTargets = new HashMap<>();
    private final Map<Integer, Long> nextAttackTick = new HashMap<>();
    private final Map<Integer, Long> nextCrystalTick = new HashMap<>();

    private NPCRegistry npcRegistry;
    private BukkitTask aiTask;
    private long currentTick;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        npcRegistry = CitizensAPI.getNPCRegistry();
        loadBots();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("bot"), "Der Befehl 'bot' fehlt in plugin.yml").setExecutor(this);
        Objects.requireNonNull(getCommand("bot")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("bots"), "Der Befehl 'bots' fehlt in plugin.yml").setExecutor(this);
        Objects.requireNonNull(getCommand("bots")).setTabCompleter(this);
        aiTask = Bukkit.getScheduler().runTaskTimer(this, this::tickBots, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (aiTask != null) aiTask.cancel();
        saveConfig();
        if (npcRegistry != null) npcRegistry.saveToStore();
        sessions.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("bots") || args.length == 0) {
            openBotList(sender);
            return true;
        }
        if (command.getName().equalsIgnoreCase("bot") && args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            if (!(sender instanceof Player owner)) {
                sender.sendMessage("§cBots können nur von Spielern erstellt werden.");
                return true;
            }
            spawnBot(owner, args[1]);
            return true;
        }
        sender.sendMessage("§cVerwendung: /bot spawn <Name> oder /bots");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("bot") && args.length == 1) {
            return "spawn".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("spawn") : List.of();
        }
        if (command.getName().equalsIgnoreCase("bot") && args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            return List.of();
        }
        return List.of();
    }

    private boolean openBotList(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cNur Spieler können ihr Bot-Menü öffnen.");
            return false;
        }
        openListMenu(player);
        return true;
    }

    private boolean spawnBot(Player owner, String name) {
        if (!name.matches("[A-Za-z0-9_-]{1,16}")) {
            owner.sendMessage("§cDer Name muss 1 bis 16 Zeichen lang sein und darf nur Buchstaben, Zahlen, _ oder - enthalten.");
            return false;
        }
        List<BotData> owned = botsFor(owner.getUniqueId());
        if (owned.size() >= MAX_BOTS_PER_PLAYER) {
            owner.sendMessage("§cDu kannst höchstens fünf Bots besitzen.");
            return false;
        }
        if (owned.stream().anyMatch(bot -> bot.name.equalsIgnoreCase(name))) {
            owner.sendMessage("§cDu hast bereits einen Bot mit diesem Namen.");
            return false;
        }

        NPC npc = null;
        try {
            npc = npcRegistry.createNPC(EntityType.PLAYER, name);
            npc.setProtected(false);
            npc.setUseMinecraftAI(false);
            npc.getOrAddTrait(SkinTrait.class).setSkinPersistent(owner);
            if (!npc.spawn(owner.getLocation())) throw new IllegalStateException("Citizens konnte den Bot nicht spawnen");
            BotData data = new BotData(npc.getId(), owner.getUniqueId(), name);
            bots.put(data.id, data);
            applyLoadout(data);
            saveBot(data);
            npcRegistry.saveToStore();
            owner.sendMessage("§aBot §f" + name + " §aerstellt. Stelle ihn jetzt im Menü ein.");
            openSettingsMenu(owner, data);
            return true;
        } catch (RuntimeException exception) {
            if (npc != null) npc.destroy();
            getLogger().warning("Bot-Erstellung fehlgeschlagen: " + exception.getMessage());
            owner.sendMessage("§cDer Bot konnte nicht erstellt werden. Prüfe, ob Citizens korrekt geladen ist.");
            return false;
        }
    }

    private void openListMenu(Player owner) {
        Inventory inventory = Bukkit.createInventory(null, 27, "§8Deine Bots");
        List<BotData> owned = botsFor(owner.getUniqueId());
        for (int index = 0; index < owned.size() && index < LIST_SLOTS.length; index++) {
            BotData data = owned.get(index);
            NPC npc = npcRegistry.getById(data.id);
            boolean spawned = npc != null && npc.isSpawned();
            inventory.setItem(LIST_SLOTS[index], button(Material.PLAYER_HEAD, "§b" + data.name,
                    "§7Linksklick: zu diesem Bot teleportieren",
                    "§7Rechtsklick: Bot einstellen",
                    spawned ? "§aAktiv" : "§eWird beim Benutzen gespawnt"));
        }
        if (owned.isEmpty()) {
            inventory.setItem(13, button(Material.BARRIER, "§cNoch keine Bots",
                    "§7Erstelle einen Bot mit §f/bot spawn <Name>"));
        }
        sessions.put(inventory, new MenuSession(owner.getUniqueId(), 0, MenuType.LIST));
        owner.openInventory(inventory);
    }

    private void openSettingsMenu(Player owner, BotData data) {
        Inventory inventory = Bukkit.createInventory(null, 27, "§8Bot: " + data.name);
        inventory.setItem(10, button(data.attackEnabled ? Material.IRON_SWORD : Material.WOODEN_SWORD,
                data.attackEnabled ? "§aAngreifen: AN" : "§cAngreifen: AUS",
                "§7Der Bot sucht nahe Spieler und greift fair an."));
        inventory.setItem(12, button(data.crystalEnabled ? Material.END_CRYSTAL : Material.GLASS,
                data.crystalEnabled ? "§aCrystal-PvP: AN" : "§cCrystal-PvP: AUS",
                "§7Benötigt End-Crystals im Bot-Inventar.",
                "§7Der Bot platziert nur in normaler Reichweite."));
        inventory.setItem(14, button(Material.CHEST, "§eInventar und Ausrüstung",
                "§7Lege Items aus deinem Inventar in die oberen Slots."));
        inventory.setItem(16, button(Material.ENDER_PEARL, "§bZum Bot teleportieren"));
        inventory.setItem(22, button(Material.BARRIER, "§cBot löschen"));
        sessions.put(inventory, new MenuSession(owner.getUniqueId(), data.id, MenuType.SETTINGS));
        owner.openInventory(inventory);
    }

    private void openDeleteMenu(Player owner, BotData data) {
        Inventory inventory = Bukkit.createInventory(null, 27, "§8Bot löschen: " + data.name);
        inventory.setItem(11, button(Material.RED_CONCRETE, "§cJa, Bot löschen"));
        inventory.setItem(15, button(Material.GREEN_CONCRETE, "§aZurück"));
        sessions.put(inventory, new MenuSession(owner.getUniqueId(), data.id, MenuType.DELETE));
        owner.openInventory(inventory);
    }

    private void openLoadoutMenu(Player owner, BotData data) {
        Inventory inventory = Bukkit.createInventory(null, 54, "§8Inventar: " + data.name);
        for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, cloneOrNull(data.contents[slot]));
        inventory.setItem(45, cloneOrNull(data.helmet));
        inventory.setItem(46, cloneOrNull(data.chestplate));
        inventory.setItem(47, cloneOrNull(data.leggings));
        inventory.setItem(48, cloneOrNull(data.boots));
        inventory.setItem(49, cloneOrNull(data.offhand));
        for (int slot = 36; slot <= 44; slot++) inventory.setItem(slot, button(Material.GRAY_STAINED_GLASS_PANE, "§8Gesperrt"));
        inventory.setItem(50, button(Material.PLAYER_HEAD, "§7Helm-Slot"));
        inventory.setItem(51, button(Material.PLAYER_HEAD, "§7Brust-Slot"));
        inventory.setItem(52, button(Material.PLAYER_HEAD, "§7Bein-Slot"));
        inventory.setItem(53, button(Material.PLAYER_HEAD, "§7Stiefel / Offhand: Slots 48–49"));
        sessions.put(inventory, new MenuSession(owner.getUniqueId(), data.id, MenuType.LOADOUT));
        owner.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        MenuSession session = sessions.get(top);
        if (session == null) return;
        if (!session.owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (session.type == MenuType.LOADOUT) {
            handleLoadoutClick(event, player, top);
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != top) return;
        int slot = event.getRawSlot();
        BotData data = bots.get(session.botId);
        if (session.type == MenuType.LIST) {
            List<BotData> owned = botsFor(player.getUniqueId());
            for (int index = 0; index < Math.min(owned.size(), LIST_SLOTS.length); index++) {
                if (LIST_SLOTS[index] != slot) continue;
                BotData selected = owned.get(index);
                if (event.getClick().isRightClick()) openSettingsMenu(player, selected);
                else teleportToBot(player, selected);
                return;
            }
        } else if (data == null) {
            player.closeInventory();
        } else if (session.type == MenuType.SETTINGS) {
            switch (slot) {
                case 10 -> {
                    data.attackEnabled = !data.attackEnabled;
                    saveBot(data);
                    openSettingsMenu(player, data);
                }
                case 12 -> {
                    data.crystalEnabled = !data.crystalEnabled;
                    saveBot(data);
                    openSettingsMenu(player, data);
                }
                case 14 -> openLoadoutMenu(player, data);
                case 16 -> teleportToBot(player, data);
                case 22 -> openDeleteMenu(player, data);
                default -> { }
            }
        } else if (session.type == MenuType.DELETE) {
            if (slot == 11) {
                deleteBot(data);
                player.sendMessage("§aBot §f" + data.name + " §awurde gelöscht.");
                player.closeInventory();
                Bukkit.getScheduler().runTask(this, () -> openListMenu(player));
            } else if (slot == 15) {
                openSettingsMenu(player, data);
            }
        }
    }

    private void handleLoadoutClick(InventoryClickEvent event, Player player, Inventory top) {
        int rawSlot = event.getRawSlot();
        if (event.getClickedInventory() == top) {
            if (!isEditableLoadoutSlot(rawSlot)) {
                event.setCancelled(true);
                return;
            }
            event.setCancelled(true);
            ItemStack current = top.getItem(rawSlot);
            ItemStack cursor = event.getCursor();
            if (event.getClick() == ClickType.LEFT) {
                top.setItem(rawSlot, cloneOrNull(cursor));
                event.setCursor(cloneOrNull(current));
            } else if (event.getClick() == ClickType.RIGHT) {
                if (cursor == null || cursor.getType().isAir()) {
                    if (current != null && !current.getType().isAir()) {
                        int amount = (current.getAmount() + 1) / 2;
                        ItemStack picked = current.clone();
                        picked.setAmount(amount);
                        event.setCursor(picked);
                        current.setAmount(current.getAmount() - amount);
                        top.setItem(rawSlot, current.getAmount() <= 0 ? null : current);
                    }
                } else if (current == null || current.getType().isAir()) {
                    ItemStack placed = cursor.clone();
                    placed.setAmount(1);
                    top.setItem(rawSlot, placed);
                    cursor.setAmount(cursor.getAmount() - 1);
                    event.setCursor(cursor.getAmount() <= 0 ? null : cursor);
                } else if (current.isSimilar(cursor) && current.getAmount() < current.getMaxStackSize()) {
                    current.setAmount(current.getAmount() + 1);
                    cursor.setAmount(cursor.getAmount() - 1);
                    top.setItem(rawSlot, current);
                    event.setCursor(cursor.getAmount() <= 0 ? null : cursor);
                }
            }
            return;
        }

        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
            ItemStack moving = event.getCurrentItem();
            if (moving == null || moving.getType().isAir()) return;
            ItemStack remaining = moving.clone();
            for (int slot = 0; slot < 36 && remaining.getAmount() > 0; slot++) {
                ItemStack current = top.getItem(slot);
                if (current == null || !current.isSimilar(remaining) || current.getAmount() >= current.getMaxStackSize()) continue;
                int moved = Math.min(remaining.getAmount(), current.getMaxStackSize() - current.getAmount());
                current.setAmount(current.getAmount() + moved);
                remaining.setAmount(remaining.getAmount() - moved);
            }
            for (int slot = 0; slot < 36 && remaining.getAmount() > 0; slot++) {
                ItemStack current = top.getItem(slot);
                if (current != null && !current.getType().isAir()) continue;
                int moved = Math.min(remaining.getAmount(), remaining.getMaxStackSize());
                ItemStack placed = remaining.clone();
                placed.setAmount(moved);
                top.setItem(slot, placed);
                remaining.setAmount(remaining.getAmount() - moved);
            }
            event.setCurrentItem(remaining.getAmount() == 0 ? null : remaining);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        MenuSession session = sessions.remove(inventory);
        if (session == null || session.type != MenuType.LOADOUT) return;
        BotData data = bots.get(session.botId);
        if (data == null) return;
        for (int slot = 0; slot < 36; slot++) data.contents[slot] = cloneOrNull(inventory.getItem(slot));
        data.helmet = cloneOrNull(inventory.getItem(45));
        data.chestplate = cloneOrNull(inventory.getItem(46));
        data.leggings = cloneOrNull(inventory.getItem(47));
        data.boots = cloneOrNull(inventory.getItem(48));
        data.offhand = cloneOrNull(inventory.getItem(49));
        saveBot(data);
        applyLoadout(data);
        event.getPlayer().sendMessage("§aInventar von §f" + data.name + " §agespeichert.");
    }

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        MenuSession session = sessions.get(top);
        if (session == null || session.type != MenuType.LOADOUT) return;
        if (!session.owner.equals(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize() && !isEditableLoadoutSlot(rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private boolean isEditableLoadoutSlot(int slot) {
        return (slot >= 0 && slot <= 35) || (slot >= 45 && slot <= 49);
    }

    private void teleportToBot(Player owner, BotData data) {
        NPC npc = npcRegistry.getById(data.id);
        if (npc == null) {
            owner.sendMessage("§cDieser Bot ist nicht mehr vorhanden.");
            return;
        }
        if (!npc.isSpawned()) {
            Location stored = npc.getStoredLocation();
            if (stored == null || !npc.spawn(stored)) {
                owner.sendMessage("§cDer Bot kann an seinem gespeicherten Ort nicht gespawnt werden.");
                return;
            }
            applyLoadout(data);
        }
        Entity entity = npc.getEntity();
        if (entity == null) {
            owner.sendMessage("§cDer Bot ist gerade nicht verfügbar.");
            return;
        }
        Location destination = entity.getLocation().clone().add(1.0, 0.0, 0.0);
        owner.teleport(destination);
        owner.closeInventory();
    }

    private void deleteBot(BotData data) {
        NPC npc = npcRegistry.getById(data.id);
        if (npc != null) npc.destroy();
        bots.remove(data.id);
        activeTargets.remove(data.id);
        nextAttackTick.remove(data.id);
        nextCrystalTick.remove(data.id);
        getConfig().set("bots." + data.id, null);
        saveConfig();
        npcRegistry.saveToStore();
    }

    private List<BotData> botsFor(UUID owner) {
        return bots.values().stream().filter(bot -> bot.owner.equals(owner))
                .sorted(Comparator.comparingInt(bot -> bot.id)).toList();
    }

    private void tickBots() {
        currentTick++;
        for (BotData data : bots.values()) {
            NPC npc = npcRegistry.getById(data.id);
            if (npc == null || !npc.isSpawned() || !(npc.getEntity() instanceof Player bot)) continue;
            if (!data.attackEnabled) {
                if (npc.getNavigator().isNavigating()) npc.getNavigator().cancelNavigation();
                activeTargets.remove(data.id);
                continue;
            }
            Player target = findTarget(data, bot);
            if (target == null) {
                if (npc.getNavigator().isNavigating()) npc.getNavigator().cancelNavigation();
                activeTargets.remove(data.id);
                continue;
            }

            double distanceSquared = bot.getEyeLocation().distanceSquared(target.getLocation().add(0, 0.9, 0));
            if (distanceSquared > 9.0) {
                UUID oldTarget = activeTargets.put(data.id, target.getUniqueId());
                if (!target.getUniqueId().equals(oldTarget) || !npc.getNavigator().isNavigating()) {
                    npc.getNavigator().setTarget(target, false);
                }
            } else if (npc.getNavigator().isNavigating()) {
                npc.getNavigator().cancelNavigation();
            }

            if (distanceSquared <= 9.0 && bot.hasLineOfSight(target)
                    && currentTick >= nextAttackTick.getOrDefault(data.id, 0L)) {
                bot.swingMainHand();
                bot.attack(target);
                nextAttackTick.put(data.id, currentTick + attackDelay(bot.getInventory().getItemInMainHand()));
            }
            if (data.crystalEnabled && currentTick >= nextCrystalTick.getOrDefault(data.id, 0L)) {
                if (tryCrystal(data, bot, target)) nextCrystalTick.put(data.id, currentTick + 60L);
                else nextCrystalTick.put(data.id, currentTick + 10L);
            }
        }
    }

    private Player findTarget(BotData data, Player bot) {
        return bot.getWorld().getPlayers().stream()
                .filter(player -> player.isOnline() && !player.isDead() && player.getHealth() > 0)
                .filter(player -> !player.getUniqueId().equals(data.owner))
                .filter(player -> !npcRegistry.isNPC(player))
                .filter(player -> player.getGameMode() != org.bukkit.GameMode.SPECTATOR)
                .filter(player -> player.getLocation().distanceSquared(bot.getLocation()) <= 400.0)
                .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(bot.getLocation())))
                .orElse(null);
    }

    private int attackDelay(ItemStack weapon) {
        if (weapon == null) return 10;
        Material material = weapon.getType();
        if (material.name().endsWith("_SWORD")) return 12;
        if (material.name().endsWith("_AXE")) return 20;
        if (material == Material.MACE) return 34;
        return 10;
    }

    private boolean tryCrystal(BotData data, Player bot, Player target) {
        PlayerInventory inventory = bot.getInventory();
        if (!inventory.contains(Material.END_CRYSTAL) || !bot.hasLineOfSight(target)) return false;
        World world = bot.getWorld();
        Block targetBlock = target.getLocation().getBlock();
        Location ownerLocation = Bukkit.getPlayer(data.owner) == null ? null : Bukkit.getPlayer(data.owner).getLocation();
        Location best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    Block base = world.getBlockAt(targetBlock.getX() + dx, targetBlock.getY() + dy, targetBlock.getZ() + dz);
                    if (base.getType() != Material.OBSIDIAN && base.getType() != Material.BEDROCK) continue;
                    if (!base.getRelative(0, 1, 0).getType().isAir() || !base.getRelative(0, 2, 0).getType().isAir()) continue;
                    Location place = new Location(world, base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
                    double botDistance = bot.getEyeLocation().distance(place);
                    double targetDistance = target.getLocation().distance(place);
                    if (botDistance > 4.5 || targetDistance > 6.0 || botDistance < 2.8) continue;
                    if (ownerLocation != null && ownerLocation.getWorld() == world && ownerLocation.distance(place) <= 6.0) continue;
                    if (!world.getNearbyEntities(place, 1.0, 2.0, 1.0).isEmpty()) continue;
                    if (targetDistance < bestDistance) {
                        best = place;
                        bestDistance = targetDistance;
                    }
                }
            }
        }
        if (best == null) return false;

        EnderCrystal crystal = world.spawn(best, EnderCrystal.class, entity -> entity.setShowingBottom(false));
        Map<Integer, ItemStack> removed = inventory.removeItem(new ItemStack(Material.END_CRYSTAL, 1));
        if (!removed.isEmpty()) {
            crystal.remove();
            return false;
        }
        Location blast = crystal.getLocation();
        world.createExplosion(blast, 6.0f, false, true, bot);
        crystal.remove();
        return true;
    }

    private void applyLoadout(BotData data) {
        NPC npc = npcRegistry.getById(data.id);
        if (npc == null || !npc.isSpawned() || !(npc.getEntity() instanceof Player bot)) return;
        PlayerInventory inventory = bot.getInventory();
        ItemStack[] contents = new ItemStack[36];
        for (int slot = 0; slot < contents.length; slot++) contents[slot] = cloneOrNull(data.contents[slot]);
        inventory.setStorageContents(contents);
        inventory.setHelmet(cloneOrNull(data.helmet));
        inventory.setChestplate(cloneOrNull(data.chestplate));
        inventory.setLeggings(cloneOrNull(data.leggings));
        inventory.setBoots(cloneOrNull(data.boots));
        inventory.setItemInOffHand(cloneOrNull(data.offhand));
        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        equipment.set(Equipment.EquipmentSlot.HAND, inventory.getItemInMainHand());
        equipment.set(Equipment.EquipmentSlot.HELMET, inventory.getHelmet());
        equipment.set(Equipment.EquipmentSlot.CHESTPLATE, inventory.getChestplate());
        equipment.set(Equipment.EquipmentSlot.LEGGINGS, inventory.getLeggings());
        equipment.set(Equipment.EquipmentSlot.BOOTS, inventory.getBoots());
        equipment.set(Equipment.EquipmentSlot.OFF_HAND, inventory.getItemInOffHand());
    }

    private void loadBots() {
        var section = getConfig().getConfigurationSection("bots");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            int id;
            UUID owner;
            try {
                id = Integer.parseInt(key);
                owner = UUID.fromString(getConfig().getString("bots." + key + ".owner", ""));
            } catch (IllegalArgumentException exception) {
                getConfig().set("bots." + key, null);
                continue;
            }
            NPC npc = npcRegistry.getById(id);
            if (npc == null) {
                getConfig().set("bots." + key, null);
                continue;
            }
            String path = "bots." + key;
            BotData data = new BotData(id, owner, getConfig().getString(path + ".name", npc.getName()));
            data.attackEnabled = getConfig().getBoolean(path + ".attack", false);
            data.crystalEnabled = getConfig().getBoolean(path + ".crystal", false);
            for (int slot = 0; slot < 36; slot++) data.contents[slot] = getConfig().getItemStack(path + ".items." + slot);
            data.helmet = getConfig().getItemStack(path + ".armor.helmet");
            data.chestplate = getConfig().getItemStack(path + ".armor.chestplate");
            data.leggings = getConfig().getItemStack(path + ".armor.leggings");
            data.boots = getConfig().getItemStack(path + ".armor.boots");
            data.offhand = getConfig().getItemStack(path + ".offhand");
            bots.put(id, data);
            applyLoadout(data);
        }
        saveConfig();
    }

    private void saveBot(BotData data) {
        String path = "bots." + data.id;
        getConfig().set(path + ".owner", data.owner.toString());
        getConfig().set(path + ".name", data.name);
        getConfig().set(path + ".attack", data.attackEnabled);
        getConfig().set(path + ".crystal", data.crystalEnabled);
        for (int slot = 0; slot < data.contents.length; slot++) getConfig().set(path + ".items." + slot, cloneOrNull(data.contents[slot]));
        getConfig().set(path + ".armor.helmet", cloneOrNull(data.helmet));
        getConfig().set(path + ".armor.chestplate", cloneOrNull(data.chestplate));
        getConfig().set(path + ".armor.leggings", cloneOrNull(data.leggings));
        getConfig().set(path + ".armor.boots", cloneOrNull(data.boots));
        getConfig().set(path + ".offhand", cloneOrNull(data.offhand));
        saveConfig();
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack cloneOrNull(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    private static final class BotData {
        private final int id;
        private final UUID owner;
        private final String name;
        private final ItemStack[] contents = new ItemStack[36];
        private ItemStack helmet;
        private ItemStack chestplate;
        private ItemStack leggings;
        private ItemStack boots;
        private ItemStack offhand;
        private boolean attackEnabled;
        private boolean crystalEnabled;

        private BotData(int id, UUID owner, String name) {
            this.id = id;
            this.owner = owner;
            this.name = name;
        }
    }

    private enum MenuType { LIST, SETTINGS, DELETE, LOADOUT }

    private record MenuSession(UUID owner, int botId, MenuType type) { }
}

