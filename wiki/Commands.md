# コマンド一覧

MyPetAddon で使用できる全コマンドとパーミッションの一覧です。

---

## プレイヤーコマンド

| コマンド | エイリアス | 説明 | パーミッション |
|----------|----------|------|--------------|
| `/pettame <名前>` | `/petclaim` | テイミング中のペットに名前を付ける | `mypetaddon.tame` |
| `/petstatus` | - | ペットの詳細ステータスを表示 | `mypetaddon.status` |
| `/petevolve` | - | ペットを進化させる（GUI表示） | `mypetaddon.evolve` |
| `/petequip` | - | ペット装備GUIを開く | `mypetaddon.equip` |
| `/petdex` | - | ペット図鑑GUIを開く | `mypetaddon.encyclopedia` |
| `/petrelease` | `/mprelease`, `/petfree` | ペットを解放する（確認GUI付き） | `mypetaddon.release` |
| `/petst` | `/mpskill` | ペットのスキルツリー・スキル一覧を表示 | `mypetaddon.skill` |

> すべてのプレイヤーコマンドはデフォルトで全プレイヤーが使用可能です。

---

## 管理者コマンド

| コマンド | 説明 | パーミッション |
|----------|------|--------------|
| `/petadmin reload` | 設定ファイルをリロードし、全ペットのステータスを再計算 | `mypetaddon.admin` |
| `/petadmin setrarity <プレイヤー> <レアリティ>` | 指定プレイヤーのペットのレアリティを変更 | `mypetaddon.admin` |
| `/petadmin setbond <プレイヤー> <EXP値>` | 指定プレイヤーのペットの絆EXPを設定 | `mypetaddon.admin` |
| `/petadmin migrate` | レガシーデータの移行を実行 | `mypetaddon.admin` |

> 管理者コマンドはデフォルトでOP権限が必要です。

---

## パーミッション一覧

| パーミッション | 説明 | デフォルト |
|--------------|------|----------|
| `mypetaddon.*` | 全権限 | OP |
| `mypetaddon.tame` | テイミング権限 | 全プレイヤー |
| `mypetaddon.status` | ステータス表示権限 | 全プレイヤー |
| `mypetaddon.equip` | 装備GUI権限 | 全プレイヤー |
| `mypetaddon.encyclopedia` | 図鑑GUI権限 | 全プレイヤー |
| `mypetaddon.evolve` | 進化権限 | 全プレイヤー |
| `mypetaddon.release` | ペット解放権限 | 全プレイヤー |
| `mypetaddon.skill` | スキルツリー表示権限 | 全プレイヤー |
| `mypetaddon.admin` | 管理者コマンド権限 | OP |

---

## コマンド使用例

### ペットのテイミングから名前付けまで

```
1. 金リンゴを持って動物を右クリック
2. テイミング成功！
3. /pettame ポチ
```

### ペットのステータス確認

```
/petstatus
```

体力・攻撃力・速度の内訳（基礎値、レアリティ倍率、性格補正、絆ボーナス）が詳細に表示されます。

### レアリティの管理者変更

```
/petadmin setrarity Steve mythic
```

Steve のアクティブペットのレアリティを MYTHIC に変更します。

### 絆EXPの管理者設定

```
/petadmin setbond Steve 2000
```

Steve のアクティブペットの絆EXPを 2000（Lv.5 魂の絆）に設定します。
