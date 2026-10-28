#!/usr/bin/env bash
# Legacy Mobile : serveur des salons (iw4l-master = liste des parties + relais) sur le VPS.
# Même installation que `cargo xtask master install`, mais faite directement sur le VPS (pas besoin de PC).
# Le serveur ne fait pas tourner les parties (c'est le téléphone de l'hôte) : pas de fichiers du jeu ici.
#
# A lancer sur le VPS, en root :
#   bash legacy-master.sh install <nom> <port> "<titre>"   installe ou réinstalle (ex. mw2 4433 "Legacy Warfare")
#   bash legacy-master.sh update  <nom>                     nouvelle version du programme, certificats gardés
#   bash legacy-master.sh status  <nom>                     tourne ? + test de connexion
#   bash legacy-master.sh logs    <nom> [durée]             journal (ex. 30min, 2h, 1day)
#   bash legacy-master.sh descriptor <nom>                  affiche le fichier .iw4l-server à donner aux joueurs
#   bash legacy-master.sh charge                            mémoire, processeur, trafic réseau du VPS
set -euo pipefail

URL="https://github.com/zyproz/IW4LJ/releases/download/iw4l-master-vps"
die() { echo "ERREUR : $*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || die "à lancer en root"

cmd="${1:-}"; name="${2:-}"
paths() {
  [[ "$name" =~ ^[a-z0-9-]+$ ]] || die "nom attendu en minuscules, ex. mw2"
  LIB="/usr/local/lib/iw4l-$name"; ETC="/etc/iw4l-$name"; UNIT="iw4l-master-$name.service"
  BIN="$LIB/iw4l-master"; LABEL="iw4l-$name"; CAKEY="/root/iw4l-ca-$name/ca-key.pem"
}
public_ip() { ip -4 route get 1.1.1.1 | sed -n 's/.* src \([0-9.]*\).*/\1/p'; }
port_of() { sed -n 's/.*--bind 0\.0\.0\.0:\([0-9]*\).*/\1/p' "/etc/systemd/system/$UNIT"; }

download() {
  local tmp; tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/iw4l-master" "$URL/iw4l-master-x86_64" || die "téléchargement impossible (le build GitHub 'Serveur VPS' a-t-il tourné ?)"
  curl -fsSL -o "$tmp/sum" "$URL/iw4l-master-x86_64.sha256"
  [ "$(sha256sum "$tmp/iw4l-master" | cut -d' ' -f1)" = "$(cut -d' ' -f1 "$tmp/sum")" ] || die "somme de contrôle fausse"
  install -d -m 0755 "$LIB" "$LIB/updates"
  install -m 0755 "$tmp/iw4l-master" "$BIN"
  rm -rf "$tmp"
  echo "programme : $BIN ($(sha256sum "$BIN" | cut -c1-12))"
}

certs() {
  local ip="$1" dir; dir="$(dirname "$CAKEY")"
  install -d -m 0700 "$dir"; install -d -m 0755 "$ETC"
  if [ ! -f "$CAKEY" ]; then
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$CAKEY" 2>/dev/null
    openssl req -x509 -new -sha256 -days 3650 -key "$CAKEY" -out "$ETC/ca.pem" -subj "/CN=Legacy Mobile $name CA"
    echo "nouvelle autorité de certificats (10 ans)"
  fi
  if [ ! -f "$ETC/server-cert.pem" ]; then
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$ETC/server-key.pem" 2>/dev/null
    openssl req -new -key "$ETC/server-key.pem" -out "$dir/server.csr" -subj "/CN=$LABEL"
    printf 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:%s,IP:%s\n' \
      "$LABEL" "$ip" > "$dir/server.ext"
    openssl x509 -req -sha256 -days 825 -in "$dir/server.csr" -CA "$ETC/ca.pem" -CAkey "$CAKEY" \
      -CAcreateserial -CAserial "$dir/ca.srl" -extfile "$dir/server.ext" -out "$ETC/server-cert.pem" 2>/dev/null
    rm -f "$dir/server.csr" "$dir/server.ext"
    echo "nouveau certificat serveur (825 jours) pour $LABEL et $ip"
  fi
  openssl verify -CAfile "$ETC/ca.pem" "$ETC/server-cert.pem" >/dev/null || die "certificat invalide"
  chmod 0644 "$ETC/ca.pem" "$ETC/server-cert.pem"
  chown root:iw4l "$ETC/server-key.pem"; chmod 0640 "$ETC/server-key.pem"
}

descriptor() {
  local port ip title
  port="$(port_of)"; ip="$(public_ip)"; title="$(cat "$ETC/title" 2>/dev/null || echo "Legacy $name")"
  printf 'schema = 1\nname = "%s"\n\n[master]\naddress = "%s:%s"\nserver_name = "%s"\n\n[updates]\nurl = "https://%s:%s/updates/manifest.toml"\nca_pem = """\n%s"""\n' \
    "$title" "$ip" "$port" "$LABEL" "$ip" "$port" "$(cat "$ETC/ca.pem")" > "/root/$name.iw4l-server"
}

status() {
  systemctl --no-pager --lines=5 status "$UNIT" || true
  echo "---- test de connexion ----"
  "$BIN" status --connect "127.0.0.1:$(port_of)" --server-name "$LABEL" --ca-cert "$ETC/ca.pem"
}

case "$cmd" in
  install)
    paths; port="${3:-}"; title="${4:-Legacy $name}"
    [[ "$port" =~ ^[0-9]+$ ]] || die "port attendu, ex. 4433"
    for f in /etc/systemd/system/iw4l-master-*.service; do
      [ -f "$f" ] && [ "$(basename "$f")" != "$UNIT" ] && grep -q "0\.0\.0\.0:$port " "$f" && die "le port $port est déjà pris par $(basename "$f")"
    done
    command -v openssl >/dev/null && command -v curl >/dev/null || { apt-get update -q && apt-get install -y -q openssl curl; }
    getent group iw4l >/dev/null || groupadd --system iw4l
    id iw4l >/dev/null 2>&1 || useradd --system --gid iw4l --home-dir /nonexistent --shell /usr/sbin/nologin iw4l
    download
    certs "$(public_ip)"
    echo "$title" > "$ETC/title"
    "$BIN" print-unit --name "$name" --port "$port" --exec "$BIN" --cert "$ETC/server-cert.pem" \
      --key "$ETC/server-key.pem" --updates "$LIB/updates" --user iw4l --group iw4l > "/etc/systemd/system/$UNIT"
    install -d -m 0755 /etc/systemd/journald.conf.d
    printf '[Journal]\nSystemMaxUse=300M\nMaxRetentionSec=14day\n' > /etc/systemd/journald.conf.d/iw4l.conf
    if command -v ufw >/dev/null && ufw status | grep -q '^Status: active'; then
      ufw allow "$port/udp"; ufw allow "$port/tcp"
    fi
    systemctl daemon-reload; systemctl restart systemd-journald
    systemctl enable --now "$UNIT" >/dev/null; systemctl restart "$UNIT"; sleep 2
    descriptor
    status
    echo; echo "INSTALLÉ : $title sur $(public_ip):$port (UDP et TCP)."
    echo "Fichier pour les joueurs : /root/$name.iw4l-server"
    ;;
  update)
    paths; [ -f "/etc/systemd/system/$UNIT" ] || die "$name n'est pas installé"
    download; systemctl restart "$UNIT"; sleep 2; status ;;
  status)
    paths; status ;;
  logs)
    paths; journalctl -u "$UNIT" --since "-${3:-1h}" --no-pager ;;
  descriptor)
    paths; descriptor; cat "/root/$name.iw4l-server" ;;
  charge)
    uptime; free -h; echo; ip -s link show eth0 | sed -n '1,8p'
    for u in /etc/systemd/system/iw4l-master-*.service; do [ -f "$u" ] && echo "$(basename "$u") : $(systemctl is-active "$(basename "$u")")"; done ;;
  *)
    sed -n '2,13p' "$0"; exit 1 ;;
esac
