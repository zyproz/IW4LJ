#!/bin/bash
# Legacy 4 online: installs (or updates) the relay on the VPS. Run as root from the folder holding legacy4-relay.py.
# Ports (UDP): 4435 = server list + links of the hosts' phones, 4440-4499 = the matches. 4433/4434 stay MW2/BO2's.
set -euo pipefail
cd "$(dirname "$0")"
IP="${1:-$(curl -4 -s --max-time 5 https://ifconfig.me || hostname -I | awk '{print $1}')}"
install -d /usr/local/lib/legacy4-relay
install -m 0755 legacy4-relay.py /usr/local/lib/legacy4-relay/legacy4-relay.py
cat > /etc/systemd/system/legacy4-relay.service <<UNIT
[Unit]
Description=Legacy 4 online (CoD4 server list + relay)
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/python3 -u /usr/local/lib/legacy4-relay/legacy4-relay.py $IP 4435 4440 4499
Restart=always
RestartSec=2
DynamicUser=yes
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
MemoryMax=256M

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable legacy4-relay >/dev/null 2>&1
systemctl restart legacy4-relay
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
    ufw allow 4435/udp >/dev/null
    ufw allow 4440:4499/udp >/dev/null
    echo "ufw: 4435/udp and 4440-4499/udp opened"
fi
sleep 1
systemctl --no-pager --lines=5 status legacy4-relay | sed -n '1,12p'
echo "Legacy 4 relay ready on $IP (udp 4435, matches 4440-4499)"
