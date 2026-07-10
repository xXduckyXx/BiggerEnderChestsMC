package com.echest.enderchest.database;

import com.echest.enderchest.util.ItemSerializationException;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;
import java.util.Base64;

public class DatabaseManager {

    private static final int MAX_CACHE_SIZE = 100;
    private static final int MAX_ITEM_BLOB_SIZE = 1_048_576;

    private final JavaPlugin plugin;
    private Connection connection;

    private final Map<UUID, ItemStack[]> cache = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, ItemStack[]> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    });

    private final Gson gson = new Gson();

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        init();
    }

    private void init() {
        try {
            File dbFile = new File(plugin.getDataFolder(), "enderchest.db");
            plugin.getDataFolder().mkdirs();
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS enderchest ("
                        + "uuid TEXT PRIMARY KEY,"
                        + "contents BLOB NOT NULL"
                        + ")");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public synchronized boolean hasEnderChest(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM enderchest WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("hasEnderChest error: " + e.getMessage());
            return false;
        }
    }

    public synchronized void saveEnderChest(UUID uuid, ItemStack[] contents) {
        ItemStack[] cachedCopy = copyContents(contents);
        cache.put(uuid, cachedCopy);

        byte[] data = serializeItemsToNbtBytes(contents);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO enderchest (uuid, contents) VALUES (?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setBytes(2, data);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save ender chest: " + e.getMessage());
        }
    }

    public synchronized void saveEnderChestBatch(List<Map.Entry<UUID, ItemStack[]>> entries) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO enderchest (uuid, contents) VALUES (?, ?)")) {
                for (int i = 0; i < entries.size(); i++) {
                    Map.Entry<UUID, ItemStack[]> entry = entries.get(i);
                    UUID uuid = entry.getKey();
                    ItemStack[] contents = entry.getValue();

                    ItemStack[] cachedCopy = copyContents(contents);
                    cache.put(uuid, cachedCopy);

                    byte[] data = serializeItemsToNbtBytes(contents);
                    ps.setString(1, uuid.toString());
                    ps.setBytes(2, data);
                    ps.addBatch();

                    if ((i + 1) % 100 == 0) {
                        ps.executeBatch();
                        connection.commit();
                    }
                }
                ps.executeBatch();
                connection.commit();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to batch save ender chests: " + e.getMessage());
            try {
                connection.rollback();
            } catch (SQLException ignored) {}
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {}
        }
    }

    public synchronized ItemStack[] loadEnderChest(UUID uuid) {
        ItemStack[] cached = cache.get(uuid);
        if (cached != null) {
            return copyContents(cached);
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT contents FROM enderchest WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] data = rs.getBytes("contents");

                    ItemStack[] items = null;
                    if (data != null && data.length > 0) {
                        if (data[0] == '[' || data[0] == '{') {
                            try {
                                String possibleJson = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                                if (possibleJson.trim().startsWith("[")) {
                                    items = deserializeItemsFromJson(possibleJson, 54);
                                }
                            } catch (Exception ignored) {}
                        }

                        if (items == null) {
                            items = deserializeItemsFromNbtBytes(data, 54);
                        }
                    }

                    if (items == null) {
                        items = new ItemStack[54];
                    }

                    ItemStack[] toCache = copyContents(items);
                    cache.put(uuid, toCache);
                    return copyContents(items);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("loadEnderChest error: " + e.getMessage());
        }

        ItemStack[] empty = new ItemStack[54];
        cache.put(uuid, empty);
        return empty;
    }

    public synchronized Set<UUID> getAllUUIDs() {
        Set<UUID> uuids = new HashSet<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT uuid FROM enderchest")) {
            while (rs.next()) {
                uuids.add(UUID.fromString(rs.getString("uuid")));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getAllUUIDs error: " + e.getMessage());
        }
        return uuids;
    }

    public void evictCache(UUID uuid) {
        cache.remove(uuid);
    }

    public synchronized void close() {
        cache.clear();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Database close error: " + e.getMessage());
        }
    }

    public List<String> serializeItemsToNbtBytesWithValidation(ItemStack[] items) {
        List<String> failures = new ArrayList<>();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            for (int i = 0; i < items.length; i++) {
                ItemStack item = items[i];
                if (item == null || item.getType().isAir()) {
                    dos.writeBoolean(false);
                } else {
                    try {
                        byte[] bytes = com.echest.enderchest.util.ItemSerializer.serialize(item);
                        if (bytes == null || bytes.length == 0) {
                            failures.add("slot " + i + " " + item.getType() + " x" + item.getAmount() + ": empty result");
                            dos.writeBoolean(false);
                        } else {
                            dos.writeBoolean(true);
                            dos.writeInt(bytes.length);
                            dos.write(bytes);
                        }
                    } catch (ItemSerializationException e) {
                        failures.add(e.getMessage());
                        plugin.getLogger().warning("Failed to serialize item in slot " + i + ": " + e.getMessage());
                        dos.writeBoolean(false);
                    }
                }
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to serialize items to NBT bytes: " + e.getMessage());
            failures.add("I/O error: " + e.getMessage());
        }
        return failures;
    }

    private byte[] serializeItemsToNbtBytes(ItemStack[] items) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            for (int i = 0; i < items.length; i++) {
                ItemStack item = items[i];
                if (item == null || item.getType().isAir()) {
                    dos.writeBoolean(false);
                } else {
                    try {
                        byte[] bytes = com.echest.enderchest.util.ItemSerializer.serialize(item);
                        if (bytes == null || bytes.length == 0) {
                            plugin.getLogger().warning("ItemSerializer returned empty bytes for " + item.getType() + " at slot " + i + " — treating as empty slot");
                            dos.writeBoolean(false);
                        } else {
                            dos.writeBoolean(true);
                            dos.writeInt(bytes.length);
                            dos.write(bytes);
                        }
                    } catch (ItemSerializationException e) {
                        plugin.getLogger().warning("Failed to serialize item in slot " + i + ": " + e.getMessage());
                        dos.writeBoolean(false);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Unexpected error serializing item in slot " + i + " (" + item.getType() + "): " + e.getMessage());
                        dos.writeBoolean(false);
                    }
                }
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to serialize items to NBT bytes: " + e.getMessage());
        }
        return baos.toByteArray();
    }

    private ItemStack[] deserializeItemsFromNbtBytes(byte[] data, int size) {
        ItemStack[] items = new ItemStack[size];
        if (data == null || data.length == 0) return items;

        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            for (int i = 0; i < size; i++) {
                if (dis.available() <= 0) break;
                if (dis.readBoolean()) {
                    int len = dis.readInt();
                    if (len <= 0 || len > MAX_ITEM_BLOB_SIZE) {
                        plugin.getLogger().warning("Invalid item data length " + len + " at slot " + i + " — skipping");
                        continue;
                    }
                    byte[] itemBytes = new byte[len];
                    dis.readFully(itemBytes);
                    ItemStack item = com.echest.enderchest.util.ItemSerializer.deserialize(itemBytes);
                    if (item != null) items[i] = item;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to deserialize items from NBT bytes: " + e.getMessage() + " — returning partial data");
        }
        return items;
    }

    private ItemStack[] deserializeItemsFromJson(String json, int size) {
        ItemStack[] result = new ItemStack[size];
        if (json == null || json.isBlank()) return result;

        try {
            Type listType = new TypeToken<List<Object>>() {}.getType();
            List<Object> list = gson.fromJson(json, listType);
            if (list != null) {
                for (int i = 0; i < Math.min(list.size(), size); i++) {
                    Object entry = list.get(i);
                    if (entry == null) continue;
                    try {
                        if (entry instanceof String) {
                            byte[] bytes = Base64.getDecoder().decode((String) entry);
                            ItemStack item = com.echest.enderchest.util.ItemSerializer.deserialize(bytes);
                            if (item != null) result[i] = item;
                        } else if (entry instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> map = (Map<String, Object>) entry;
                            ItemStack item = ItemStack.deserialize(map);
                            if (item != null) result[i] = item;
                        }
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Failed to deserialize item slot " + i + " from old JSON data: " + ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse old JSON enderchest data during transition: " + e.getMessage());
        }
        return result;
    }

    private ItemStack[] copyContents(ItemStack[] source) {
        if (source == null) return new ItemStack[54];
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            ItemStack it = source[i];
            copy[i] = (it == null ? null : it.clone());
        }
        return copy;
    }
}
