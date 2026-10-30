#!/usr/bin/env bash
# Установка сервера Bearite на Ubuntu/Debian VPS одной командой (под root):
#   curl -fsSL https://raw.githubusercontent.com/Sp1KLe/Bearite/main/server/install.sh | bash
# Ставит Node.js, кладёт сервер в /opt/bearite, запускает как службу (перезапуск при сбое и после перезагрузки), открывает порт 8080.
set -e
PORT="${PORT:-8080}"
if ! command -v node >/dev/null; then
  apt-get update -y && apt-get install -y curl ca-certificates
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && apt-get install -y nodejs
fi
mkdir -p /opt/bearite
curl -fsSL https://raw.githubusercontent.com/Sp1KLe/Bearite/main/server/bearite-server.js -o /opt/bearite/bearite-server.js
cat > /etc/systemd/system/bearite.service <<EOF
[Unit]
Description=Bearite server
After=network.target
[Service]
Environment=PORT=$PORT
Environment=SERVER_NAME=${SERVER_NAME:-Bearite сервер}
ExecStart=/usr/bin/env node /opt/bearite/bearite-server.js
Restart=always
RestartSec=2
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload && systemctl enable --now bearite
# у Oracle Cloud порт закрыт ещё и в iptables образа
iptables -I INPUT -p tcp --dport "$PORT" -j ACCEPT 2>/dev/null || true
command -v netfilter-persistent >/dev/null && netfilter-persistent save || true
echo "Готово. Адрес для лаунчера: http://$(curl -s ifconfig.me):$PORT"
