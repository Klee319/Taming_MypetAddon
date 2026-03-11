# Items, Blocks & Entities

## アイテム操作

### ItemStack 作成・編集

```java
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;

// 基本作成
ItemStack item = new ItemStack(Material.DIAMOND_SWORD);

// editMeta でメタデータ編集（推奨パターン）
item.editMeta(meta -> {
    meta.displayName(Component.text("Legendary Sword", NamedTextColor.GOLD)
        .decoration(TextDecoration.ITALIC, false));  // デフォルトの斜体を無効化
    meta.lore(List.of(
        Component.text("A blade of legend", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false),
        Component.text(""),
        Component.text("Damage: +15", NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false)
    ));
    meta.addEnchant(Enchantment.SHARPNESS, 5, true);
    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
    meta.setUnbreakable(true);
});

// プレイヤーに渡す
player.getInventory().addItem(item);
```

### Persistent Data Container (PDC)

```java
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;

NamespacedKey key = new NamespacedKey(plugin, "custom_id");

// データ書き込み
item.editMeta(meta -> {
    meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "magic_wand");
});

// データ読み込み
ItemMeta meta = item.getItemMeta();
PersistentDataContainer pdc = meta.getPersistentDataContainer();
if (pdc.has(key, PersistentDataType.STRING)) {
    String value = pdc.get(key, PersistentDataType.STRING);
}

// エンティティへのPDC
player.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, 100);
```

### PersistentDataType 一覧

| 型 | Java型 |
|----|--------|
| `BYTE` | byte |
| `SHORT` | short |
| `INTEGER` | int |
| `LONG` | long |
| `FLOAT` | float |
| `DOUBLE` | double |
| `STRING` | String |
| `BYTE_ARRAY` | byte[] |
| `INTEGER_ARRAY` | int[] |
| `LONG_ARRAY` | long[] |
| `TAG_CONTAINER` | PersistentDataContainer (ネスト) |
| `TAG_CONTAINER_ARRAY` | PersistentDataContainer[] |
| `BOOLEAN` | boolean |

### カスタムレシピ

```java
// Shaped Recipe（形あり）
NamespacedKey recipeKey = new NamespacedKey(plugin, "magic_sword");
ShapedRecipe recipe = new ShapedRecipe(recipeKey, resultItem);
recipe.shape("DDD", " S ", " S ");
recipe.setIngredient('D', Material.DIAMOND);
recipe.setIngredient('S', Material.STICK);
Bukkit.addRecipe(recipe);

// Shapeless Recipe（形なし）
ShapelessRecipe shapeless = new ShapelessRecipe(recipeKey, resultItem);
shapeless.addIngredient(Material.GOLD_INGOT);
shapeless.addIngredient(Material.REDSTONE);
Bukkit.addRecipe(shapeless);

// Furnace Recipe
FurnaceRecipe furnace = new FurnaceRecipe(recipeKey, resultItem, Material.RAW_IRON, 0.7f, 200);
Bukkit.addRecipe(furnace);
```

## ブロック操作

```java
import org.bukkit.block.Block;
import org.bukkit.Location;

// ブロック取得・設定
Block block = player.getLocation().getBlock();
block.setType(Material.DIAMOND_BLOCK);

// Location指定
Location loc = new Location(world, x, y, z);
loc.getBlock().setType(Material.AIR);

// ブロックデータ
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;

Block stairBlock = world.getBlockAt(x, y, z);
if (stairBlock.getBlockData() instanceof Stairs stairs) {
    stairs.setFacing(BlockFace.NORTH);
    stairs.setHalf(Bisected.Half.TOP);
    stairBlock.setBlockData(stairs);
}

// ブロックエンティティ（チェスト、看板等）
if (block.getState() instanceof Chest chest) {
    chest.getInventory().addItem(new ItemStack(Material.DIAMOND));
}

if (block.getState() instanceof Sign sign) {
    sign.line(0, Component.text("Line 1", NamedTextColor.BLUE));
    sign.update();
}
```

## エンティティ操作

```java
import org.bukkit.entity.*;

// エンティティスポーン
Location loc = player.getLocation();

// 基本スポーン
Zombie zombie = world.spawn(loc, Zombie.class);

// Consumer付きスポーン（スポーン前にカスタマイズ）
world.spawn(loc, Zombie.class, zombie -> {
    zombie.customName(Component.text("Boss Zombie", NamedTextColor.RED));
    zombie.setCustomNameVisible(true);
    zombie.setMaxHealth(100);
    zombie.setHealth(100);
    zombie.getEquipment().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
    zombie.getPersistentDataContainer().set(
        new NamespacedKey(plugin, "boss"), PersistentDataType.BOOLEAN, true);
});

// アーマースタンド（ホログラム）
world.spawn(loc, ArmorStand.class, stand -> {
    stand.setInvisible(true);
    stand.setGravity(false);
    stand.setMarker(true);  // 当たり判定なし
    stand.customName(Component.text("Hologram Text", NamedTextColor.GOLD));
    stand.setCustomNameVisible(true);
});

// エンティティ検索
// 半径10ブロック以内の全プレイヤー
Collection<Player> nearby = loc.getNearbyPlayers(10);

// 半径内の全エンティティ（フィルタ付き）
Collection<Entity> entities = world.getNearbyEntities(loc, 5, 5, 5,
    entity -> entity instanceof Monster);

// ワールド内の特定型エンティティ
Collection<Zombie> zombies = world.getEntitiesByClass(Zombie.class);
```

## インベントリ / GUI

### Custom InventoryHolder パターン（推奨）

```java
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class MyGUI implements InventoryHolder {
    private final Inventory inventory;

    public MyGUI() {
        // 9の倍数（9, 18, 27, 36, 45, 54）
        this.inventory = Bukkit.createInventory(this, 27,
            Component.text("Custom Menu", NamedTextColor.DARK_PURPLE));
        initializeItems();
    }

    private void initializeItems() {
        // 装飾（ガラス板で埋める）
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        filler.editMeta(meta -> meta.displayName(Component.text("")));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        // ボタン配置
        ItemStack button = new ItemStack(Material.EMERALD);
        button.editMeta(meta -> {
            meta.displayName(Component.text("Click Me!", NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        });
        inventory.setItem(13, button); // 中央
    }

    @Override
    public Inventory getInventory() { return inventory; }
}

// GUIを開く
player.openInventory(new MyGUI().getInventory());
```

### クリックハンドラ

```java
public class GUIListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof MyGUI)) return;

        event.setCancelled(true); // アイテム移動を防止

        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        switch (event.getSlot()) {
            case 13 -> {
                player.sendMessage(Component.text("Button clicked!", NamedTextColor.GREEN));
                player.closeInventory();
            }
        }
    }
}
```

### ページネーション付きGUI

```java
public class PaginatedGUI implements InventoryHolder {
    private final Inventory inventory;
    private final List<ItemStack> items;
    private int page;

    private static final int ITEMS_PER_PAGE = 45; // 54 - 9(ナビゲーション行)

    public PaginatedGUI(List<ItemStack> items, int page) {
        this.items = items;
        this.page = page;
        this.inventory = Bukkit.createInventory(this, 54,
            Component.text("Page " + (page + 1)));
        populatePage();
    }

    private void populatePage() {
        inventory.clear();
        int start = page * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE && start + i < items.size(); i++) {
            inventory.setItem(i, items.get(start + i));
        }

        // ナビゲーションボタン（最下段）
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            prev.editMeta(m -> m.displayName(Component.text("Previous Page")));
            inventory.setItem(45, prev);
        }
        if ((page + 1) * ITEMS_PER_PAGE < items.size()) {
            ItemStack next = new ItemStack(Material.ARROW);
            next.editMeta(m -> m.displayName(Component.text("Next Page")));
            inventory.setItem(53, next);
        }
    }

    public int getPage() { return page; }
    public List<ItemStack> getItems() { return items; }
    @Override public Inventory getInventory() { return inventory; }
}
```
