# Legacy 4 (CoD4 sur Android) — version 0.1

Moteur : jeiang/cod4-decomp (GPL-3.0), version figée dans `.github/workflows/legacy4-android.yml` (`COD4_COMMIT`).
Le moteur n'est pas copié ici : le build le télécharge puis applique `patch_client.py`.

## Ce que fait ce dossier
- `src/android.rs` : l'entrée Android (trouve les fichiers du jeu, écrit `journal.txt` et `crash.txt`).
- `patch_client.py` : les petites modifications du client pour Android (bibliothèque partagée, doigt = souris
  dans les menus, arrêt propre quand on quitte l'appli).
- `android/` : manifeste et deux écrans Java (lancement et jeu). `res/` : icône (logo Legacy 4 Mobile) et bannière de lancement.

## Sur le téléphone
Dossier : `Android/media/dev.zyproz.legacy4/`
- `cod4/` : copier `main/`, `zone/` et `localization.txt` de Call of Duty 4 PC version 1.7.
- `journal.txt` : tout ce que le jeu a écrit au dernier lancement.
- `crash.txt` : la raison du dernier arrêt (affichée par l'écran de lancement).
- `args.txt` (facultatif) : options en plus, par exemple `--no-sound`, `--no-bc` ou `--listen --bots 1`.

## Limites de la 0.1
- Pas encore de manette ni de contrôles tactiles en jeu : le doigt sert seulement de souris dans les menus.
- Quitter l'appli (bouton accueil) arrête le jeu : il faut le relancer.

## Steam (0.2)
Quand les fichiers manquent, l'écran de lancement propose de se connecter à Steam (QR code ou compte).
Le module est partagé par toutes les applis Legacy Mobile :
- `android/java/dev/zyproz/legacy/steam/SteamActivity.java` : connexion et téléchargement (JavaSteam).
  Rien n'est enregistré : ni mot de passe, ni jeton Steam. Journal : `steam-log.txt` à côté du dossier du jeu.
- `steam/` : récupère JavaSteam et ses dépendances pendant le build (`build.gradle.kts`, `prepare_jars.py`).
- L'appli dit quel jeu prendre avec des « extras » : `app_id` (CoD4 = 7940), `target_dir`, `files` (expressions
  comme `main/.*`), `check_dirs`, `mode` (`qr` ou `account`), `next_activity`.

## Manette (0.3)
`GameActivity.java` garde les entrées (comme Legacy Warfare) et les envoie au jeu par JNI (`android.rs`).
- En partie : les touches manette du moteur (`input/extra_binds.cfg`) : RT tir, LT viser, A sauter, B accroupi,
  X recharger, Y changer d'arme, LB/RB grenades, L3 sprint, R3 couteau, Start menu, Select scores,
  croix : haut utiliser, bas couché, gauche/droite se pencher.
- Dans les menus : croix = flèches, A = valider, B ou Start = retour, stick gauche = curseur, LB/RB = gauche/droite.
- Le doigt sert de souris dans les menus. Bouton retour du téléphone = menu / retour.

## Tactile et menu Legacy (0.4)
- `TouchPad.java` : boutons à l'écran dans une fenêtre transparente au-dessus du jeu (même disposition que Legacy
  Warfare). Stick à gauche, glisser à droite pour viser, FIRE vise aussi en glissant. Ils se cachent quand une manette
  sert, et dans les menus du jeu le doigt redevient la souris (seul MENU reste).
- `ModMenu.java` : le menu Legacy (bouton MENU) : TRICKSHOT, BOTS, LAN, GRAPHICS, CONTROLS, ACCOUNT. Chaque bouton
  envoie une ligne de console au jeu.
- `src/server_legacy4.rs` → `crates/server/src/legacy4.rs` : commandes serveur `l4save`, `l4load` (tout le monde),
  `l4noclip`, `l4god`, `l4bothere`, `l4botfreeze`, `l4botmove` (l'hôte seulement).

## Correctif boutons + VIP (0.5)
- Boutons tactiles (et manette) muets : le `config_mp.cfg` d'un profil CoD4 commence par `unbindall`, qui effaçait les
  touches manette. Sur Android, elles sont remises après le profil (`patch_client.py`, étape 9).
- VIP : même système que Legacy Warfare (`android.rs`, `license_pub.hex`) : `player-id.txt` dans le dossier de
  l'appli, clé `vip.<ID>.<expiration>.<signature>` créée avec `mw2keys.sh`, à coller dans ACCOUNT.
  Gratuit : SAVE/LOAD POSITION, BOT HERE. VIP : noclip, god mode, geler/relancer les bots.

## 0.6
- AIM tactile : interrupteur (un appui = viser, un autre = arrêter), et on peut viser en glissant le doigt dessus.
  Réglable dans CONTROLS (interrupteur ou maintenu).
- Balles explosives (VIP, hôte) : `l4explosive`, explosion à l'impact (`patch_client.py` étape 10, `fire.rs`).
- UNLOCK ALL (VIP, onglet ACCOUNT) : `l4unlockall`, mêmes déblocages que le profil du harness (`profile::unlocked_stats`).

## Menu aux couleurs de Legacy 4 (0.7 à 0.9)
0.7/0.8 passaient par un `.iwd` : ignoré (nom refusé dans `main`, puis images aussi intégrées aux `.ff`). Depuis 0.9,
le chargeur de textures (`render/src/texture.rs`, `IMAGE_OVERRIDE`, étape 14) demande à `android.rs` (`menu_image`) les
couches du fond : `bg_back` = bannière (`menu/legacy_banner.rgba`, 1024x576 RGBA), `bg_blur_back` = bannière sombre,
soldat, brumes et logo CoD4 transparents. `journal.txt` note chaque image remplacée. Les anciens `.iwd` sont supprimés.

## 0.9
- God mode : liste de joueurs côté serveur, tous les dégâts ignorés dans `g_damage` (balles, explosions, chute),
  même après une mort (étape 12). L'ancien drapeau 8 n'était pas le bon (FL_GODMODE = 1) et ne protégeait pas les joueurs.
- UNLOCK ALL : `l4unlockall` était refusé par la console (« Unknown command ») ; ajouté à ses commandes (étape 13).
- Balles explosives : rayon 260, 300 de dégâts au centre, 160 au bord.
- Manette : sensibilité du stick 1 à 10 dans CONTROLS (`in_gamepad_yawrate` / `pitchrate`) ; bouton Xbox/PS = menu
  Legacy ; dans le menu : croix/stick pour se déplacer, A valider, B/Start/Xbox fermer, LB/RB changer d'onglet.

## 0.10
- Double Y / double triangle (annuler un changement d'arme) : la demande d'arme du client était oubliée pendant que
  l'arme descendait ; elle dure maintenant jusqu'à la fin de la descente (`netplay.rs`, étape 15).
- God mode et balles explosives ne s'appliquent jamais à un bot (listes par place de joueur côté serveur).
- À savoir : en noclip, comme dans le CoD4 d'origine, on ne tire pas (pas d'arme dans `PmType::Noclip`) et on ne
  prend pas de dégâts. Sortir du noclip avant de tirer.

## 0.11
- UNLOCK ALL : le serveur d'une partie garde sa copie des stats du joueur d'une map à l'autre et la renvoie, ce qui
  annulait le déblocage fait côté client. Nouvelle commande serveur `l4unlock` : mêmes stats posées côté serveur
  (comme `setStat` des scripts), chaque changement renvoyé au client et sauvé dans le profil. Visible à la map suivante.
- Logo « Call of Duty 4 » du menu (image `cod4`) masqué.
- Diagnostic du bot increvable : `journal.txt` note chaque balle qui atteint un bot (« legacy4: a bullet reached a
  bot ») et chaque dégât refusé à un bot avec la raison (« legacy4: damage to a bot refused »).

## Killcam finale (0.12)
Le CoD4 d'origine n'en a pas ; elle est faite par le serveur seul (`src/server_legacy4.rs`, étape 17), avec l'historique
que rejoue la killcam normale (`archive.rs`, 15 s) :
- `legacy4_on_kill` (appelé par `player_die`) : en Recherche et Destruction, un kill qui ne laisse personne en vie dans
  l'équipe de la victime est le dernier de la manche. Respecte « Allow KillCam » (`scr_game_allowkillcam`).
- `legacy4_final_frame` (chaque image, avant l'envoi aux joueurs) : 1,5 s après le kill, chaque joueur humain (pas les
  bots) voit « FINAL KILLCAM » et la scène par les yeux du tueur, de 3,5 s avant le kill à 1,5 s après ; puis tout
  revient comme avant.
- La manche suivante (`map_restart`) et la fin du match (`exit_requested`) attendent la fin de la killcam finale.
- `netsv.rs` : un joueur peut se suivre lui-même pendant un replay (le tueur revoit son tir).

## 0.13
- UNLOCK ALL : les déblocages sont pris dans la table du jeu (`mp/statstable.csv`, colonne 1 = stat de l'objet) :
  atouts et objets (150..298, bit 0), armes (3000 et plus : bit 0 et tous les bits d'accessoires), plus Create a Class,
  emplacements de classe et rang max. Le client envoie la même liste au serveur (`l4unlock index valeur ...`).
  `journal.txt` : « legacy4: unlock all from mp/statstable.csv » avec les nombres trouvés.
- Killcam finale : 4 s avant le kill, 2 s après, et ralenti (x0,35) de 1 s avant à 1 s après le kill (environ 9,7 s).
- Wallbangs : les fusils de précision (classe rifle, pénétration large) traversent 8 fois plus, et même un peu ce que
  la table rend impénétrable (étape 19, `bullet.rs`).
- Double Y rapide : un second appui arrivé avant que le serveur commence le changement est gardé et devient
  l'annulation dès que l'arme descend (étape 18).
- Manette : sensibilité 1 à 20 (8 par défaut, 560 °/s ; 20 = 1260 °/s) ; en visée, le stick ralentit avec le zoom
  comme la souris, fois le réglage « AIM » de CONTROLS (`l4_ads_sens`, 60 % par défaut).
- Tactile : second bouton FIRE à gauche, sous l'ancienne mini-carte.

## 0.14
- UNLOCK ALL : les armes principales restaient verrouillées. Le menu Create a Class teste `stat(3000 + numéro de
  l'arme) & 1` (`ui_mp/cac.inc`) ; les stats 3000..3089 sont maintenant posées directement (bit 0 + accessoires et
  camos), sans dépendre de la table. Une fois UNLOCK ALL utilisé, `unlock-all.txt` (dossier de l'appli) le garde
  actif : le client remet ces bits à chaque image (`patch_client.py` étapes 5 et 11).
- Perk 1 grisé avec le M16 de la classe 1 : normal dans CoD4, le lance-grenades prend la place du perk 1. Enlever
  l'accessoire pour choisir un perk 1.
- Double Y : le serveur gardait la demande d'arme du premier appui et finissait le changement (pistolet), puis
  revenait au sniper. Redemander l'arme en main pendant qu'elle descend annule maintenant le changement côté serveur,
  comme `PM_Weapon_CheckForChangeWeapon` d'origine (étape 18, `server/src/client.rs`) ; le second appui part tout de
  suite et attend que le serveur montre la descente (0,7 s au plus).
- Killcam fluide : le serveur rejoue les images enregistrées en mélangeant les deux autour du moment montré
  (`archive.rs` `at_smooth`, étape 20), et le client dessine la vue du joueur suivi entre deux snapshots, au rythme
  de l'écran (étape 21). Le ralenti ne saccade plus.
- Killcam : les deux bandes rouges du hud d'origine (menu `killcam`) sont retirées ; le titre reste (étape 22).

## 0.15
- Create a Class : toutes les armes principales restaient grises, même débloquées. Le bouton « Primary Weapon » fait
  `set ui_primary_selected Primary Weapon` et une arme ne se choisit que si ce dvar vaut « Primary Weapon » ; le `set`
  du client ne gardait que le premier mot (« Primary »). Il garde maintenant tous les mots, comme `Cmd_Set_f`
  (étape 23, `input/mod.rs`).
- Clavier : quand un champ texte du jeu est en cours (nom d'un nouveau profil, mot de passe...), une petite fenêtre
  Android s'ouvre avec le clavier du téléphone ; OK remplace le texte du champ puis valide (Entrée), Annuler fait
  Échap. `android.rs` : `set_text_field`, `nativeTextField`, `nativeTextKey` ; `ui/mod.rs` : `l4_editing` (étape 24).

## 0.16
- Steam (écran partagé Legacy Mobile) : la connexion (QR et compte) échouait avec « ClassNotFoundException:
  org.bouncycastle.jce.provider.BouncyCastleProvider ». JavaSteam 1.8.0 charge Bouncy Castle par son nom sans
  l'inclure : `steam/build.gradle.kts` ajoute `org.bouncycastle:bcprov-jdk18on:1.80`, et `SteamActivity`
  (`installCrypto`) remplace le fournisseur « BC » réduit d'Android par le complet, dans le processus de l'appli
  seulement. Les champs de l'écran Steam sont exclus du remplissage automatique. Même correctif que Legacy Warfare 3.3.
