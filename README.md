[![License: FCL](https://img.shields.io/badge/License-Fair_Core-blue.svg)](LICENSE)
[![Paper](https://img.shields.io/badge/Paper-1.21.1-orange.svg)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org)
[![Version](https://img.shields.io/badge/Version-1.0.0-green.svg)](https://github.com/Crafteria-dev/UltimateBoxed/releases)

> 🇫🇷 [Français](#ultimateboxed-fr) · 🇬🇧 [English](#ultimateboxed-en)

---

> 💡 **Usage Commercial :** Ce plugin est gratuit pour les particuliers.
> Si vous êtes une entreprise ou que vous générez des revenus avec ce plugin,
> vous **devez** me contacter pour obtenir une autorisation.

# UltimateBoxed (FR)

**UltimateBoxed** est un plugin Paper 1.21.1 qui transforme votre serveur Minecraft en expérience de jeu **Boxed** : chaque joueur évolue dans son propre monde isolé, limité à une petite zone qu'il doit **agrandir en complétant des quêtes**.

Inspiré du mode "Boxed" popularisé par Grian, ce plugin offre une implémentation complète, légère et configurable — pensée aussi bien pour les petits serveurs privés que pour les réseaux plus grands.

---

## ✨ Fonctionnalités

| Catégorie | Détail |
|---|---|
| 🌍 **Monde par joueur** | Chaque joueur reçoit sa propre copie d'une map template au premier `/boxed play` |
| 🔒 **Isolation totale** | Portails bloqués, passage entre mondes interdit — aucun joueur ne peut entrer dans le monde d'un autre |
| 📦 **Zone progressive** | Zone de départ 1 chunk (16×16) agrandie via les récompenses de quêtes |
| 📜 **Système de quêtes** | 8 types de conditions YAML, système de paliers, prérequis entre quêtes |
| 🎁 **Récompenses** | XP, items, agrandissement de zone |
| 🖥️ **Interface** | Menu coffre des quêtes avec statut visuel, barre d'action animée |
| ⚡ **Optimisé** | Sauvegardes différées, index de conditions précalculé, déchargement automatique des mondes vides |
| 🔔 **Mises à jour** | Vérification automatique au démarrage via l'API GitHub |

---

## 📋 Prérequis

- **Serveur** : [Paper](https://papermc.io) 1.21.1 (Spigot non supporté)
- **Java** : 21 ou supérieur
- **Map template** : un monde Minecraft standard à placer dans le dossier du plugin

---

## 🚀 Installation

### 1. Placer le JAR

Déposez `ultimateboxed-x.x.x.jar` dans le dossier `plugins/` de votre serveur.

### 2. Préparer la map template

La map template est le monde de départ copié pour chaque joueur.

```
plugins/
└── UltimateBoxed/
    └── template/          ← copiez ici votre monde Minecraft
        ├── level.dat      ← obligatoire
        ├── region/
        └── ...
```

> **Comment créer une bonne template ?**
> 1. Créez un monde normal sur votre serveur (ou en local avec MCA Selector).
> 2. Aménagez la zone de spawn : les joueurs arrivent au spawn du monde.
> 3. Copiez le dossier du monde dans `plugins/UltimateBoxed/template/`.
> 4. Le plugin recopie automatiquement `playerdata/`, `stats/` et `advancements/` pour que chaque joueur parte de zéro.

> ⚠️ Si le dossier `template/` est absent ou ne contient pas de `level.dat`, le plugin affiche un avertissement dans la console et bloque `/boxed play` jusqu'à résolution.

### 3. Configurer le lobby

Indiquez dans `config.yml` quel monde sert de lobby (où les joueurs attendent avant de jouer) :

```yaml
world:
  lobby-world: "world"   # nom de votre monde principal
```

### 4. Démarrer le serveur

Au démarrage, la console affiche :

```
[UltimateBoxed] UltimateBoxed v1.0.0 activé avec succès — by ZO3N
[UltimateBoxed] UltimateBoxed est à jour (v1.0.0).
```

---

## ⚙️ Configuration

### `config.yml`

```yaml
zone:
  start-size: 1                  # Taille de départ en chunks (1 = 16×16 blocs)
  expand-animation-duration: 3   # Durée de l'animation d'agrandissement (secondes)
  damage-amount: 0.2             # Dégâts par seconde hors zone
  damage-buffer: 0.5             # Zone tampon avant les dégâts (blocs)
  warning-distance: 5            # Distance d'avertissement visuel (blocs)

world:
  lobby-world: "world"           # Monde lobby (avant /boxed play et après /boxed leave)
  unload-when-empty: true        # Décharge les mondes vides (économise la RAM)

optimization:
  save-delay-ticks: 100          # Délai avant écriture disque (100 ticks = 5 s)
  auto-save-interval-ticks: 6000 # Sauvegarde auto périodique (6000 ticks = 5 min)

update-check: true               # Vérification des mises à jour au démarrage
```

Tous les messages affichés aux joueurs sont personnalisables dans la section `messages:` — les codes couleur `§` sont supportés.

---

### `quests.yml`

Les quêtes sont entièrement définies en YAML. Format complet :

```yaml
quests:
  id_de_la_quete:
    name: "§6Nom affiché"         # Codes §-couleur supportés
    description: "Courte description"
    tier: 1                        # Numéro de palier (1 = départ)
    icon: OAK_LOG                  # Matériau affiché dans le menu
    prerequisites:                 # IDs des quêtes à compléter avant
      - autre_quete
    conditions:
      - type: BREAK_BLOCK          # Voir tableau des types ci-dessous
        material: OAK_LOG
        amount: 10
    rewards:
      - type: XP
        amount: 50
      - type: ZONE_EXPAND
        amount: 1
```

#### Types de conditions

| Type | Description | Champs requis |
|---|---|---|
| `BREAK_BLOCK` | Casser un bloc | `material`, `amount` |
| `PLACE_BLOCK` | Poser un bloc | `material`, `amount` |
| `KILL_MOB` | Tuer une entité | `entity`, `amount` |
| `CRAFT_ITEM` | Fabriquer un objet | `material`, `amount` |
| `SMELT_ITEM` | Fondre dans un four | `material`, `amount` |
| `FISH` | Pêcher | `amount` |
| `SLEEP` | Dormir dans un lit | `amount` |
| `TRADE_VILLAGER` | Commercer avec un villageois | `amount` |

#### Types de récompenses

| Type | Description | Champs requis |
|---|---|---|
| `XP` | Points d'expérience | `amount` |
| `ITEM` | Item dans l'inventaire | `material`, `amount` |
| `ZONE_EXPAND` | Agrandit la zone de X chunks | `amount` |

---

## 🕹️ Commandes & Permissions

### Commandes joueur

| Commande | Description |
|---|---|
| `/boxed play` | Rejoint (ou crée) votre monde Boxed |
| `/boxed leave` | Retourne au lobby |
| `/boxed quests` | Ouvre le menu des quêtes |
| `/boxed info` | Affiche votre zone, palier et progression |

### Commandes administrateur

| Commande | Description |
|---|---|
| `/boxed setzone <joueur> <n>` | Définit la taille de zone d'un joueur (en chunks) |
| `/boxed reset <joueur>` | Réinitialise toute la progression d'un joueur |
| `/boxed reload` | Recharge `config.yml` et `quests.yml` à chaud |
| `/boxed status` | Tableau de bord : template, quêtes, mondes actifs, sauvegardes |

### Permissions

| Permission | Défaut | Description |
|---|---|---|
| `boxed.use` | `true` (tous) | Accès aux commandes joueur |
| `boxed.admin` | `op` | Accès aux commandes administrateur |

---

## 🎮 Comment jouer

### Première connexion

1. Le joueur arrive dans le **lobby** (monde principal).
2. Un message cliquable `[▶ Démarrer]` s'affiche dans le chat.
3. Il clique ou tape `/boxed play`.

### Création du monde

- Le plugin **copie la map template** de façon asynchrone (aucun lag serveur).
- Une barre d'animation `[█░░░] → [████]` s'affiche pendant la génération.
- Le joueur est téléporté dans son monde dès que celui-ci est prêt.

### Progression

```
Zone 1×1 chunk (16×16 blocs)
     │
     ├─ Quête "Récolte de bois"    → +XP, table de craft
     ├─ Quête "Première nuit"      → +XP, torches
     ├─ Quête "Survivant"          → +XP, épée
     └─ Quête "Artisan débutant"   → +1 chunk de zone ◄── la zone grandit !
          │
          └─ Quête "Mineur"        → +XP, lingots de fer
               └─ Quête "Forgeron" → +1 chunk, diamants...
```

- Chaque condition validée affiche une **barre de progression** dans la barre d'action : `Récolte de bois [████░░░░] 4/10`.
- La zone s'agrandit avec une animation fluide dès qu'une récompense `ZONE_EXPAND` est accordée.

### Retour en session

Les joueurs qui se reconnectent sont **automatiquement téléportés** dans leur monde Boxed. Le monde est chargé depuis le disque si nécessaire, puis déchargé automatiquement quand ils se déconnectent (`unload-when-empty: true`).

---

## 🛡️ Isolation des mondes

Le plugin garantit une séparation totale entre les joueurs :

- **Portails désactivés** dans tous les mondes Boxed (nether et end bloqués).
- **Téléportation inter-monde refusée** : un joueur ne peut pas entrer dans le monde d'un autre, même via commande.
- **Filet de sécurité** : tout joueur qui se retrouverait dans un monde étranger est renvoyé au lobby instantanément.
- Les téléportations gérées par le plugin (`/boxed play`, `/boxed leave`) contournent ces protections de façon interne.

---

## 🔔 Mises à jour automatiques

Au démarrage, le plugin contacte l'API GitHub pour vérifier si une nouvelle version est disponible :

```
[UltimateBoxed] === UltimateBoxed — Mise à jour disponible ! ===
[UltimateBoxed]   Version actuelle : v1.0.0
[UltimateBoxed]   Nouvelle version : v1.1.0
[UltimateBoxed]   Téléchargez : https://github.com/Crafteria-dev/UltimateBoxed/releases/latest
```

Les administrateurs connectés reçoivent également un message en jeu avec un bouton cliquable.

Désactivez la vérification avec `update-check: false` dans `config.yml` (serveurs sans accès internet).

---

## ❓ Problèmes fréquents

**`/boxed play` ne fonctionne pas**
> Vérifiez que `plugins/UltimateBoxed/template/level.dat` existe.
> Lancez `/boxed status` pour voir l'état de la template.

**Le monde ne se génère pas**
> Consultez la console pour un éventuel message `[SEVERE]`.
> Assurez-vous que le serveur a les droits d'écriture dans son dossier racine.

**Les quêtes ne progressent pas**
> Vérifiez que le joueur est bien **dans son monde Boxed** (`/boxed info`).
> Vérifiez les IDs de matériaux et d'entités dans `quests.yml` (majuscules, noms Bukkit).

**Beaucoup de mondes chargés en RAM**
> Activez `world.unload-when-empty: true` dans `config.yml`.
> Les mondes sont automatiquement déchargés 1 seconde après le départ du joueur.

---

## 📜 Licence

Ce projet est sous licence **Fair Core License (FCL)**.

- ✅ Utilisation **gratuite** pour usage personnel et serveurs non commerciaux
- ✅ Modification et redistribution du code source **avec attribution**
- ❌ Utilisation **commerciale** (génération de revenus) **sans autorisation** interdite

Pour toute demande commerciale, contactez **ZO3N** sur GitHub.

---

<div align="center">
  Fait avec ❤️ par <strong>ZO3N</strong><br>
  <a href="https://github.com/Crafteria-dev/UltimateBoxed">github.com/Crafteria-dev/UltimateBoxed</a>
</div>

---
---

> 💡 **Commercial Use:** This plugin is free for individuals.
> If you are a company or generating revenue with this plugin,
> you **must** contact me to obtain authorization.

# UltimateBoxed (EN)

**UltimateBoxed** is a Paper 1.21.1 plugin that transforms your Minecraft server into a **Boxed** game experience: each player evolves in their own isolated world, limited to a small zone they must **expand by completing quests**.

Inspired by the "Boxed" mode popularised by Grian, this plugin offers a complete, lightweight, and configurable implementation — designed for both small private servers and larger networks.

---

## ✨ Features

| Category | Detail |
|---|---|
| 🌍 **Per-player world** | Each player receives their own copy of a template map on first `/boxed play` |
| 🔒 **Full isolation** | Portals blocked, cross-world travel forbidden — no player can enter another's world |
| 📦 **Progressive zone** | Starting zone of 1 chunk (16×16) expanded via quest rewards |
| 📜 **Quest system** | 8 YAML condition types, tier system, quest prerequisites |
| 🎁 **Rewards** | XP, items, zone expansion |
| 🖥️ **Interface** | Quest chest GUI with visual status, animated action bar |
| ⚡ **Optimised** | Deferred saves, pre-computed condition index, automatic unloading of empty worlds |
| 🔔 **Updates** | Automatic check at startup via the GitHub API |

---

## 📋 Requirements

- **Server**: [Paper](https://papermc.io) 1.21.1 (Spigot not supported)
- **Java**: 21 or higher
- **Template map**: a standard Minecraft world to place in the plugin folder

---

## 🚀 Installation

### 1. Place the JAR

Drop `ultimateboxed-x.x.x.jar` into your server's `plugins/` folder.

### 2. Prepare the template map

The template map is the starting world copied for each player.

```
plugins/
└── UltimateBoxed/
    └── template/          ← copy your Minecraft world here
        ├── level.dat      ← required
        ├── region/
        └── ...
```

> **How to create a good template?**
> 1. Create a normal world on your server (or locally with MCA Selector).
> 2. Set up the spawn area: players arrive at the world spawn.
> 3. Copy the world folder into `plugins/UltimateBoxed/template/`.
> 4. The plugin automatically resets `playerdata/`, `stats/` and `advancements/` so each player starts fresh.

> ⚠️ If the `template/` folder is missing or contains no `level.dat`, the plugin logs a warning and blocks `/boxed play` until resolved.

### 3. Configure the lobby

Specify in `config.yml` which world serves as the lobby (where players wait before playing):

```yaml
world:
  lobby-world: "world"   # name of your main world
```

### 4. Start the server

On startup, the console displays:

```
[UltimateBoxed] UltimateBoxed v1.0.0 enabled successfully — by ZO3N
[UltimateBoxed] UltimateBoxed is up to date (v1.0.0).
```

---

## ⚙️ Configuration

### `config.yml`

```yaml
zone:
  start-size: 1                  # Starting size in chunks (1 = 16×16 blocks)
  expand-animation-duration: 3   # Zone expansion animation duration (seconds)
  damage-amount: 0.2             # Damage per second outside the zone
  damage-buffer: 0.5             # Buffer zone before damage (blocks)
  warning-distance: 5            # Visual warning distance (blocks)

world:
  lobby-world: "world"           # Lobby world (before /boxed play and after /boxed leave)
  unload-when-empty: true        # Unload empty worlds (saves RAM)

optimization:
  save-delay-ticks: 100          # Delay before disk write (100 ticks = 5 s)
  auto-save-interval-ticks: 6000 # Periodic auto-save (6000 ticks = 5 min)

update-check: true               # Check for updates on startup
```

All messages displayed to players are customisable in the `messages:` section — `§` colour codes are supported.

---

### `quests.yml`

Quests are fully defined in YAML. Full format:

```yaml
quests:
  quest_id:
    name: "§6Displayed Name"      # §-colour codes supported
    description: "Short description"
    tier: 1                        # Tier number (1 = starting tier)
    icon: OAK_LOG                  # Material displayed in the menu
    prerequisites:                 # IDs of quests to complete first
      - another_quest
    conditions:
      - type: BREAK_BLOCK          # See condition types table below
        material: OAK_LOG
        amount: 10
    rewards:
      - type: XP
        amount: 50
      - type: ZONE_EXPAND
        amount: 1
```

#### Condition types

| Type | Description | Required fields |
|---|---|---|
| `BREAK_BLOCK` | Break a block | `material`, `amount` |
| `PLACE_BLOCK` | Place a block | `material`, `amount` |
| `KILL_MOB` | Kill an entity | `entity`, `amount` |
| `CRAFT_ITEM` | Craft an item | `material`, `amount` |
| `SMELT_ITEM` | Smelt in a furnace | `material`, `amount` |
| `FISH` | Go fishing | `amount` |
| `SLEEP` | Sleep in a bed | `amount` |
| `TRADE_VILLAGER` | Trade with a villager | `amount` |

#### Reward types

| Type | Description | Required fields |
|---|---|---|
| `XP` | Experience points | `amount` |
| `ITEM` | Item in inventory | `material`, `amount` |
| `ZONE_EXPAND` | Expands the zone by X chunks | `amount` |

---

## 🕹️ Commands & Permissions

### Player commands

| Command | Description |
|---|---|
| `/boxed play` | Join (or create) your Boxed world |
| `/boxed leave` | Return to the lobby |
| `/boxed quests` | Open the quest menu |
| `/boxed info` | Display your zone, tier and progression |

### Admin commands

| Command | Description |
|---|---|
| `/boxed setzone <player> <n>` | Set a player's zone size (in chunks) |
| `/boxed reset <player>` | Reset all of a player's progression |
| `/boxed reload` | Hot-reload `config.yml` and `quests.yml` |
| `/boxed status` | Dashboard: template, quests, active worlds, saves |

### Permissions

| Permission | Default | Description |
|---|---|---|
| `boxed.use` | `true` (all) | Access to player commands |
| `boxed.admin` | `op` | Access to admin commands |

---

## 🎮 How to play

### First connection

1. The player arrives in the **lobby** (main world).
2. A clickable `[▶ Start]` message appears in chat.
3. They click it or type `/boxed play`.

### World creation

- The plugin **copies the template map** asynchronously (no server lag).
- An animation bar `[█░░░] → [████]` is displayed during generation.
- The player is teleported into their world as soon as it is ready.

### Progression

```
Zone 1×1 chunk (16×16 blocks)
     │
     ├─ Quest "Wood Gathering"     → +XP, crafting table
     ├─ Quest "First Night"        → +XP, torches
     ├─ Quest "Survivor"           → +XP, sword
     └─ Quest "Apprentice Crafter" → +1 chunk of zone ◄── the zone grows!
          │
          └─ Quest "Miner"         → +XP, iron ingots
               └─ Quest "Blacksmith" → +1 chunk, diamonds...
```

- Each validated condition displays a **progress bar** on the action bar: `Wood Gathering [████░░░░] 4/10`.
- The zone expands with a smooth animation whenever a `ZONE_EXPAND` reward is granted.

### Returning players

Players who reconnect are **automatically teleported** into their Boxed world. The world is loaded from disk if needed, then automatically unloaded when they disconnect (`unload-when-empty: true`).

---

## 🛡️ World isolation

The plugin guarantees total separation between players:

- **Portals disabled** in all Boxed worlds (nether and end blocked).
- **Cross-world teleportation refused**: a player cannot enter another player's world, even via command.
- **Safety net**: any player found in a foreign world is instantly sent back to the lobby.
- Teleportations managed by the plugin (`/boxed play`, `/boxed leave`) bypass these protections internally.

---

## 🔔 Automatic updates

On startup, the plugin contacts the GitHub API to check if a new version is available:

```
[UltimateBoxed] === UltimateBoxed — Update available! ===
[UltimateBoxed]   Current version : v1.0.0
[UltimateBoxed]   New version     : v1.1.0
[UltimateBoxed]   Download        : https://github.com/Crafteria-dev/UltimateBoxed/releases/latest
```

Connected admins also receive an in-game message with a clickable button.

Disable the check with `update-check: false` in `config.yml` (servers without internet access).

---

## ❓ Common issues

**`/boxed play` does not work**
> Check that `plugins/UltimateBoxed/template/level.dat` exists.
> Run `/boxed status` to see the template status.

**The world does not generate**
> Check the console for a `[SEVERE]` message.
> Make sure the server has write permissions in its root folder.

**Quests are not progressing**
> Check that the player is **inside their Boxed world** (`/boxed info`).
> Verify material and entity IDs in `quests.yml` (uppercase, Bukkit names).

**Too many worlds loaded in RAM**
> Enable `world.unload-when-empty: true` in `config.yml`.
> Worlds are automatically unloaded 1 second after the player leaves.

---

## 📜 License

This project is licensed under the **Fair Core License (FCL)**.

- ✅ **Free** for personal use and non-commercial servers
- ✅ Modification and redistribution of source code **with attribution**
- ❌ **Commercial use** (revenue generation) **without authorisation** is prohibited

For any commercial request, contact **ZO3N** on GitHub.

---

<div align="center">
  Made with ❤️ by <strong>ZO3N</strong><br>
  <a href="https://github.com/Crafteria-dev/UltimateBoxed">github.com/Crafteria-dev/UltimateBoxed</a>
</div>
