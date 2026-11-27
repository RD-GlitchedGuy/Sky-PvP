package de.skypvp.recorder;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class SkyBotRecorderPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private int maxTicks;
    private int flushTicks;
    private BukkitTask ticker;

    @Override public void onEnable() {
        saveDefaultConfig();
        maxTicks = Math.max(20, getConfig().getInt("max-recording-minutes", 30) * 60 * 20);
        flushTicks = Math.max(1, getConfig().getInt("flush-every-ticks", 20));
        getServer().getPluginManager().registerEvents(this, this);
        var command = getCommand("record");
        if (command == null) throw new IllegalStateException("/record command missing from plugin.yml");
        command.setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player player)) { sender.sendMessage("Diesen Befehl kannst du nur im Spiel verwenden."); return true; }
            if (args.length == 0) { player.sendMessage("§eNutze: /record <start [Name]|stop|status>"); return true; }
            switch (args[0].toLowerCase()) {
                case "start" -> start(player, args.length > 1 ? args[1] : "session");
                case "stop" -> stop(player, "manuell beendet");
                case "status" -> status(player);
                default -> player.sendMessage("§eNutze: /record <start [Name]|stop|status>");
            }
            return true;
        });
        command.setTabCompleter((sender, cmd, alias, args) -> args.length == 1
                ? java.util.List.of("start", "stop", "status").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList()
                : java.util.List.of());
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("SkyBotRecorder ist bereit.");
    }

    @Override public void onDisable() {
        if (ticker != null) ticker.cancel();
        for (Session session : new ArrayList<>(sessions.values())) finish(session, "Server wird beendet", false);
    }

    private void start(Player player, String label) {
        if (sessions.containsKey(player.getUniqueId())) { player.sendMessage("§cDu zeichnest bereits auf. Nutze /record stop."); return; }
        String safe = label.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isBlank()) safe = "session";
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC).format(Instant.now());
        Path dir = getDataFolder().toPath().resolve("recordings");
        Path file = dir.resolve(player.getName().replaceAll("[^A-Za-z0-9_-]", "_") + "_" + stamp + "_" + safe + ".jsonl");
        try {
            Files.createDirectories(dir);
            BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            Session session = new Session(player, file, writer);
            sessions.put(player.getUniqueId(), session);
            Map<String, Object> header = base("header", session);
            header.put("schema", "skybots-recorder/1"); header.put("server", Bukkit.getVersion());
            header.put("startedAt", Instant.now().toString()); header.put("maxTicks", maxTicks);
            write(session, header);
            writeInventory(session, player, "initial");
            player.sendMessage("§aAufnahme gestartet: §f" + file.getFileName());
        } catch (IOException ex) {
            player.sendMessage("§cAufnahme konnte nicht erstellt werden.");
            getLogger().severe("Aufnahmedatei konnte nicht erstellt werden: " + ex.getMessage());
        }
    }

    private void stop(Player player, String reason) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) { player.sendMessage("§eDu zeichnest gerade nicht auf."); return; }
        finish(s, reason, true);
    }

    private void status(Player player) {
        Session s = sessions.get(player.getUniqueId());
        player.sendMessage(s == null ? "§eKeine aktive Aufnahme." : "§aAufnahme läuft: §f" + s.file.getFileName() + " §7(" + s.ticks + " Ticks)");
    }

    private void tick() {
        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.playerId);
            if (p == null || !p.isOnline()) { finish(s, "Spieler hat den Server verlassen", false); continue; }
            try {
                s.ticks++;
                Map<String, Object> row = base("sample", s);
                Location l = p.getLocation(); Vector v = p.getVelocity();
                row.put("tick", s.ticks); row.put("world", l.getWorld() == null ? null : l.getWorld().getName());
                row.put("worldId", l.getWorld() == null ? null : l.getWorld().getUID().toString());
                row.put("x", l.getX()); row.put("y", l.getY()); row.put("z", l.getZ());
                row.put("yaw", l.getYaw()); row.put("pitch", l.getPitch());
                row.put("velocity", data("x", v.getX(), "y", v.getY(), "z", v.getZ()));
                row.put("onGround", p.isOnGround()); row.put("fallDistance", p.getFallDistance());
                row.put("sprinting", p.isSprinting()); row.put("sneaking", p.isSneaking()); row.put("gliding", p.isGliding());
                row.put("swimming", p.isSwimming()); row.put("pose", p.getPose().name());
                row.put("health", p.getHealth()); row.put("food", p.getFoodLevel()); row.put("selectedHotbar", p.getInventory().getHeldItemSlot());
                row.put("input", input(p));
                if (s.ticks % 20 == 0) {
                    String now = inventoryJson(p);
                    if (!now.equals(s.lastInventory)) writeInventory(s, p, "changed");
                }
                row.put("inventoryVersion", s.inventoryVersion);
                write(s, row);
                if (s.ticks % flushTicks == 0) s.writer.flush();
                if (s.ticks >= maxTicks) finish(s, "Zeitlimit erreicht", true);
            } catch (Exception ex) {
                getLogger().warning("Aufnahme für " + p.getName() + " abgebrochen: " + ex.getMessage());
                finish(s, "Aufnahmefehler", true);
            }
        }
    }

    private Map<String, Object> input(Player p) {
        var i = p.getCurrentInput();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("forward", i.isForward()); m.put("backward", i.isBackward()); m.put("left", i.isLeft());
        m.put("right", i.isRight()); m.put("jump", i.isJump()); m.put("sneak", i.isSneak()); m.put("sprint", i.isSprint());
        return m;
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { event(e.getPlayer(), "join", data()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { event(e.getPlayer(), "quit", data()); Session s=sessions.get(e.getPlayer().getUniqueId()); if(s!=null) finish(s,"Spieler hat den Server verlassen",true); }
    @EventHandler public void onHeld(PlayerItemHeldEvent e) { event(e.getPlayer(), "hotbar_change", data("from",e.getPreviousSlot(),"to",e.getNewSlot())); }
    @EventHandler public void onSwing(PlayerAnimationEvent e) { event(e.getPlayer(), "swing", data()); }
    @EventHandler public void onInteract(PlayerInteractEvent e) { Map<String,Object> d=new LinkedHashMap<>(); d.put("action",e.getAction().name()); d.put("hand",e.getHand()==null?null:e.getHand().name()); d.put("item", item(e.getItem())); if(e.getClickedBlock()!=null)d.put("block",e.getClickedBlock().getType().name()); event(e.getPlayer(),"interact",d); }
    @EventHandler public void onEntityInteract(PlayerInteractEntityEvent e) { event(e.getPlayer(),"entity_interact",entity(e.getRightClicked())); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { event(e.getPlayer(),"block_place",data("block",e.getBlockPlaced().getType().name(),"x",e.getBlockPlaced().getX(),"y",e.getBlockPlaced().getY(),"z",e.getBlockPlaced().getZ(),"item",item(e.getItemInHand()))); }
    @EventHandler public void onBreak(BlockBreakEvent e) { event(e.getPlayer(),"block_break",data("block",e.getBlock().getType().name(),"x",e.getBlock().getX(),"y",e.getBlock().getY(),"z",e.getBlock().getZ())); }
    @EventHandler public void onDamage(EntityDamageByEntityEvent e) { if(e.getDamager() instanceof Player p){ Map<String,Object>d=entity(e.getEntity());d.put("damage",e.getFinalDamage());event(p,"attack",d); } }
    @EventHandler public void onConsume(PlayerItemConsumeEvent e) { event(e.getPlayer(),"consume",data("item",item(e.getItem()))); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { event(e.getPlayer(),"drop",data("item",item(e.getItemDrop().getItemStack()))); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { event(e.getPlayer(),"swap_hands",data("main",item(e.getMainHandItem()),"off",item(e.getOffHandItem()))); }
    @EventHandler public void onInventory(InventoryClickEvent e) { if(e.getWhoClicked() instanceof Player p) event(p,"inventory_click",data("slot",e.getSlot(),"rawSlot",e.getRawSlot(),"click",e.getClick().name(),"action",e.getAction().name(),"current",item(e.getCurrentItem()),"cursor",item(e.getCursor()))); }
    @EventHandler public void onBow(EntityShootBowEvent e) { if(e.getEntity() instanceof Player p) event(p,"bow_shoot",data("bow",item(e.getBow()),"force",e.getForce(),"projectile",e.getProjectile().getType().name())); }

    private void event(Player p, String type, Map<String, Object> data) {
        Session s=sessions.get(p.getUniqueId()); if(s==null)return;
        Map<String,Object> row=base("event",s); row.put("tick",s.ticks); row.put("event",type); row.put("data",data); write(s,row);
    }
    private void writeInventory(Session s, Player p, String reason) {
        Map<String,Object> inv=new LinkedHashMap<>();
        var pi=p.getInventory();
        for(int slot=0;slot<pi.getStorageContents().length;slot++) inv.put("storage_"+slot,item(pi.getStorageContents()[slot]));
        inv.put("helmet",item(pi.getHelmet())); inv.put("chestplate",item(pi.getChestplate())); inv.put("leggings",item(pi.getLeggings())); inv.put("boots",item(pi.getBoots())); inv.put("offhand",item(pi.getItemInOffHand()));
        Map<String,Object> row=base("inventory",s); row.put("tick",s.ticks); row.put("reason",reason); row.put("version",++s.inventoryVersion); row.put("slots",inv);
        write(s,row); s.lastInventory=inventoryJson(p);
    }
    private String inventoryJson(Player p) {
        Map<String,Object> inv=new LinkedHashMap<>(); var pi=p.getInventory();
        ItemStack[] contents=pi.getStorageContents(); for(int i=0;i<contents.length;i++)inv.put("s"+i,item(contents[i]));
        inv.put("h",item(pi.getHelmet()));inv.put("c",item(pi.getChestplate()));inv.put("l",item(pi.getLeggings()));inv.put("b",item(pi.getBoots()));inv.put("o",item(pi.getItemInOffHand()));
        return json(inv);
    }
    private Map<String,Object> entity(Entity e) { Map<String,Object>d=new LinkedHashMap<>();d.put("type",e.getType().name());d.put("uuid",e.getUniqueId().toString());Location l=e.getLocation();d.put("x",l.getX());d.put("y",l.getY());d.put("z",l.getZ());if(e instanceof Player p)d.put("name",p.getName());return d; }
    private Object item(ItemStack stack) { return stack==null?null:convert(stack.serialize()); }
    private Map<String,Object> data(Object... pairs) { Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i+1<pairs.length;i+=2)m.put(String.valueOf(pairs[i]),pairs[i+1]);return m; }
    private Map<String,Object> base(String kind, Session s) { Map<String,Object>m=new LinkedHashMap<>();m.put("type",kind);m.put("player",s.playerName);m.put("playerId",s.playerId.toString());m.put("recordingId",s.id);m.put("elapsedMillis",System.currentTimeMillis()-s.startedMillis);return m; }
    private Object convert(Object value) {
        if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean)return value;
        if(value instanceof ConfigurationSerializable c)return convert(c.serialize());
        if(value instanceof Map<?,?> map){Map<String,Object>out=new LinkedHashMap<>();map.forEach((k,v)->out.put(String.valueOf(k),convert(v)));return out;}
        if(value instanceof Collection<?> c){ArrayList<Object>out=new ArrayList<>();for(Object v:c)out.add(convert(v));return out;}
        if(value instanceof Enum<?> e)return e.name();
        if(value.getClass().isArray()){ArrayList<Object>out=new ArrayList<>();for(int i=0;i<java.lang.reflect.Array.getLength(value);i++)out.add(convert(java.lang.reflect.Array.get(value,i)));return out;}
        return String.valueOf(value);
    }
    private String json(Object value) {
        if(value==null)return "null";
        if(value instanceof String s)return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";
        if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map){StringBuilder b=new StringBuilder("{");boolean first=true;for(var e:map.entrySet()){if(!first)b.append(',');first=false;b.append(json(String.valueOf(e.getKey()))).append(':').append(json(e.getValue()));}return b.append('}').toString();}
        if(value instanceof Collection<?> list){StringBuilder b=new StringBuilder("[");boolean first=true;for(Object x:list){if(!first)b.append(',');first=false;b.append(json(x));}return b.append(']').toString();}
        return json(String.valueOf(value));
    }
    private void write(Session s, Map<String,Object> row) { try{s.writer.write(json(row));s.writer.newLine();}catch(IOException ex){throw new RuntimeException(ex);} }
    private void finish(Session s,String reason,boolean inform) {
        if(!sessions.remove(s.playerId,s))return;
        try {Map<String,Object>end=base("end",s);end.put("tick",s.ticks);end.put("reason",reason);write(s,end);s.writer.flush();s.writer.close();}catch(IOException ex){getLogger().warning("Aufnahme konnte nicht sauber geschlossen werden: "+ex.getMessage());}
        Player p=Bukkit.getPlayer(s.playerId);if(inform&&p!=null)p.sendMessage("§aAufnahme gespeichert: §fplugins/SkyBotRecorder/recordings/"+s.file.getFileName());
    }
    private static final class Session {
        final UUID playerId,id=UUID.randomUUID(); final String playerName; final Path file; final BufferedWriter writer; final long startedMillis=System.currentTimeMillis();
        long ticks; long inventoryVersion; String lastInventory="";
        Session(Player p,Path file,BufferedWriter writer){playerId=p.getUniqueId();playerName=p.getName();this.file=file;this.writer=writer;}
    }
}


