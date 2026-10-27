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
