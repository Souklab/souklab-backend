#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# SOUKLAB NGROK ACCESS HELPER
# Exposes the unified Nginx gateway (or frontend) over a secure public tunnel.
# Supports both host ngrok CLI and Docker containerized ngrok service.
# ==============================================================================

PORT="${1:-80}"
MODE="${2:-cli}" # cli or docker

echo "===================================================================="
echo " Souklab Ngrok Tunnel Launcher"
echo " Target Port: ${PORT} (Nginx reverse proxy on 80 proxies frontend, backend API & WebSockets)"
echo " Local Network IP: $(ip route get 1.1.1.1 2>/dev/null | awk '{print $7}' || hostname -I | awk '{print $1}')"
echo "===================================================================="

if [ "$MODE" = "docker" ]; then
    echo "Starting ngrok via Docker Compose..."
    docker compose --profile ngrok up -d ngrok
    echo "Waiting for ngrok tunnel to be established..."
    sleep 3

    TUNNEL_URL=$(curl -s http://localhost:4040/api/tunnels | grep -o 'https://[^"]*\.ngrok-free\.app' | head -n 1 || true)
    if [ -n "$TUNNEL_URL" ]; then
        echo ""
        echo "===================================================================="
        echo " Public Ngrok URL: $TUNNEL_URL"
        echo " Web Dashboard:   http://localhost:4040"
        echo "===================================================================="
    else
        echo "Check http://localhost:4040 for active tunnel details or add NGROK_AUTHTOKEN in .env"
    fi
else
    if command -v ngrok >/dev/null 2>&1; then
        echo "Starting ngrok on port ${PORT} using local CLI..."
        echo "Press Ctrl+C to stop the tunnel."
        exec ngrok http "${PORT}" --host-header=rewrite
    else
        echo "Local ngrok CLI not found, falling back to Docker compose ngrok service..."
        docker compose --profile ngrok up -d ngrok
        sleep 3
        curl -s http://localhost:4040/api/tunnels
    fi
fi
