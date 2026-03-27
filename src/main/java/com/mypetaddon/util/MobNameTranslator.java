package com.mypetaddon.util;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Translates Minecraft entity type names to Japanese display names.
 */
public final class MobNameTranslator {

    private static final Map<String, String> MOB_NAME_JA;

    static {
        Map<String, String> m = new HashMap<>();
        // Tier 1
        m.put("CHICKEN", "ニワトリ");
        m.put("RABBIT", "ウサギ");
        m.put("COD", "タラ");
        m.put("SALMON", "サケ");
        m.put("TROPICAL_FISH", "熱帯魚");
        m.put("PUFFERFISH", "フグ");
        m.put("BAT", "コウモリ");
        m.put("SILVERFISH", "シルバーフィッシュ");
        m.put("ENDERMITE", "エンダーマイト");
        m.put("SQUID", "イカ");
        m.put("GLOW_SQUID", "ヒカリイカ");
        m.put("TADPOLE", "オタマジャクシ");
        // Tier 2
        m.put("PIG", "ブタ");
        m.put("COW", "ウシ");
        m.put("SHEEP", "ヒツジ");
        m.put("HORSE", "ウマ");
        m.put("DONKEY", "ロバ");
        m.put("MULE", "ラバ");
        m.put("LLAMA", "ラマ");
        m.put("CAT", "ネコ");
        m.put("WOLF", "オオカミ");
        m.put("FOX", "キツネ");
        m.put("OCELOT", "ヤマネコ");
        m.put("PARROT", "オウム");
        m.put("TURTLE", "カメ");
        m.put("FROG", "カエル");
        m.put("GOAT", "ヤギ");
        m.put("CAMEL", "ラクダ");
        m.put("SNIFFER", "スニッファー");
        m.put("ARMADILLO", "アルマジロ");
        m.put("ALLAY", "アレイ");
        m.put("AXOLOTL", "ウーパールーパー");
        m.put("BEE", "ミツバチ");
        m.put("DOLPHIN", "イルカ");
        m.put("MOOSHROOM", "ムーシュルーム");
        m.put("PANDA", "パンダ");
        m.put("POLAR_BEAR", "シロクマ");
        m.put("STRIDER", "ストライダー");
        m.put("ZOMBIE_HORSE", "ゾンビホース");
        m.put("SKELETON_HORSE", "スケルトンホース");
        m.put("SNOW_GOLEM", "スノウゴーレム");
        // Tier 3
        m.put("ZOMBIE", "ゾンビ");
        m.put("SKELETON", "スケルトン");
        m.put("SPIDER", "クモ");
        m.put("CREEPER", "クリーパー");
        m.put("SLIME", "スライム");
        m.put("ENDERMAN", "エンダーマン");
        m.put("CAVE_SPIDER", "洞窟グモ");
        m.put("DROWNED", "ドラウンド");
        m.put("HUSK", "ハスク");
        m.put("STRAY", "ストレイ");
        m.put("PHANTOM", "ファントム");
        m.put("PILLAGER", "ピリジャー");
        m.put("VINDICATOR", "ヴィンディケーター");
        m.put("WITCH", "ウィッチ");
        m.put("MAGMA_CUBE", "マグマキューブ");
        m.put("HOGLIN", "ホグリン");
        m.put("PIGLIN", "ピグリン");
        m.put("PIGLIN_BRUTE", "ピグリンブルート");
        m.put("ZOMBIFIED_PIGLIN", "ゾンビピグリン");
        m.put("ZOMBIE_VILLAGER", "村人ゾンビ");
        m.put("BOGGED", "ボグド");
        m.put("ZOGLIN", "ゾグリン");
        // Tier 4
        m.put("BLAZE", "ブレイズ");
        m.put("GHAST", "ガスト");
        m.put("WITHER_SKELETON", "ウィザースケルトン");
        m.put("EVOKER", "エヴォーカー");
        m.put("RAVAGER", "ラヴェジャー");
        m.put("GUARDIAN", "ガーディアン");
        m.put("SHULKER", "シュルカー");
        m.put("VEX", "ヴェックス");
        m.put("BREEZE", "ブリーズ");
        m.put("CREAKING", "クリーキング");
        m.put("IRON_GOLEM", "アイアンゴーレム");
        m.put("COPPER_GOLEM", "銅ゴーレム");
        // Tier 5
        m.put("ELDER_GUARDIAN", "エルダーガーディアン");
        m.put("WARDEN", "ウォーデン");
        m.put("WITHER", "ウィザー");
        MOB_NAME_JA = Map.copyOf(m);
    }

    private MobNameTranslator() {}

    /**
     * Translates an entity type name to Japanese.
     * Returns the original name if no translation exists.
     */
    @NotNull
    public static String translate(@NotNull String mobType) {
        return MOB_NAME_JA.getOrDefault(mobType.toUpperCase(Locale.ROOT), mobType);
    }
}
