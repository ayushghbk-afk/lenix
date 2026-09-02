#!/bin/bash
# Lenix VNC Auto-Install Script
# This script installs and configures VNC server inside the proot/Debian environment
# Called by the Android app during desktop startup

set -e

VNC_PORT=${1:-5901}
DISPLAY_NUM=${2:-1}
GEOMETRY=${3:-1280x720}

echo "=== Lenix VNC Auto-Install ==="
echo "Port: $VNC_PORT, Display: :$DISPLAY_NUM, Geometry: $GEOMETRY"
echo ""

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Install Xvnc if not available
if ! command_exists Xvnc; then
    echo "Xvnc not found - installing..."
    
    # Update package lists
    apt-get update -qq 2>/dev/null || {
        echo "Warning: apt-get update failed, continuing anyway..."
    }
    
    # Try to install Xvnc/TigerVNC
    if command_exists apt-get; then
        apt-get install -y -qq tigervnc-standalone-server 2>/dev/null || {
            echo "TigerVNC not available, trying x11vnc..."
            apt-get install -y -qq x11vnc 2>/dev/null || {
                echo "Warning: Could not install VNC server packages"
                echo "Using Python fallback VNC server..."
                
                # Use Python VNC server as fallback
                if [ ! -f ~/bin/vncserver.py ]; then
                    cat > ~/bin/vncserver.py << 'PYVNC'
#!/usr/bin/env python3
"""Fallback VNC server without X11"""
import socket, struct, threading, sys, os, signal, time

RFB_VERSION = b"RFB 003.008\n"

class VncServer:
    def __init__(self, w=640, h=480, port=5901):
        self.w, self.h, self.port = w, h, port
        self.fb = bytearray(w*h*2)
        self.running = False
        
    def _rgb565(self, r, g, b):
        return ((r & 0x1F) << 11) | ((g & 0x3F) << 5) | (b & 0x1F)
        
    def setup_fb(self):
        for y in range(self.h):
            for x in range(self.w):
                idx = (y*self.w + x) * 2
                r = int(30 + x/self.w * 20)
                g = int(30 + y/self.h * 20)
                b = 50
                p = self._rgb565(r, g, b)
                self.fb[idx] = (p >> 8) & 0xFF
                self.fb[idx+1] = p & 0xFF
                
    def start(self):
        self.running = True
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('0.0.0.0', self.port))
        sock.listen(5)
        sock.settimeout(1.0)
        print(f"VNC Server running on port {self.port}")
        
        while self.running:
            try:
                conn, addr = sock.accept()
                threading.Thread(target=self.handle_client, args=(conn,), daemon=True).start()
            except socket.timeout:
                continue
            except:
                break
        sock.close()
        
    def handle_client(self, conn):
        try:
            conn.sendall(RFB_VERSION)
            if not conn.recv(12).startswith(b"RFB"):
                return
            conn.sendall(struct.pack("!I", 1) + b"\x00")
            if struct.unpack("!I", conn.recv(4))[0] != 0:
                return
            conn.recv(4)
            
            fmt = struct.pack("!BB", 0, 1) + struct.pack("!BBBB", 16, 16, 0, 0) + struct.pack("!BBBB", 31, 63, 31, 11, 5, 0) + struct.pack("!B", 0)
            name = b"PythonVNC"
            init = struct.pack("!HH", self.w, self.h) + fmt + struct.pack("!I", len(name)) + name
            msg = b"\x00" + init
            conn.sendall(struct.pack("!I", len(msg) + 4) + msg)
            conn.recv(4)
            
            while self.running:
                try:
                    conn.settimeout(1.0)
                    h = conn.recv(4)
                    if not h:
                        break
                    t = h[0]
                    if t == 0:
                        conn.recv(20)
                    elif t == 1:
                        n = struct.unpack("!I", conn.recv(4))[0]
                        conn.recv(n*4)
                    elif t == 2:
                        req = conn.recv(12)
                        x, y = struct.unpack("!HH", req[2:6])
                        w, h = struct.unpack("!HH", req[6:10])
                        self.send_update(conn, x, y, w, h)
                    elif t == 5:
                        conn.recv(24)
                except socket.timeout:
                    continue
                except:
                    break
        finally:
            conn.close()
            
    def send_update(self, conn, x, y, w, h):
        msg = struct.pack("!BBB", 2, 0, 1) + struct.pack("!HHHH", x, y, w, h) + struct.pack("!I", 2)
        for row in range(h):
            sy = y + row
            if sy >= self.h:
                break
            s = sy * self.w * 2 + x * 2
            msg += self.fb[s:s + w*2]
        pad = (4 - len(msg) % 4) % 4
        msg += b"\x00" * pad
        conn.sendall(struct.pack("!I", len(msg)) + msg)

if __name__ == "__main__":
    import argparse
    p = argparse.ArgumentParser()
    p.add_argument("-p", "--port", type=int, default=5901)
    p.add_argument("-w", "--width", type=int, default=640)
    p.add_argument("-H", "--height", type=int, default=480)
    a = p.parse_args()
    s = VncServer(a.width, a.height, a.port)
    s.setup_fb()
    s.start()
PYVNC
                    fi
                    chmod +x ~/bin/vncserver.py
                    ln -sf ~/bin/vncserver.py ~/bin/Xvnc 2>/dev/null || true
                    echo "Python VNC server installed as fallback"
                    exit 0
                fi
                
                echo "ERROR: No VNC server available"
                exit 1
            }
        fi
    fi
    
    if command_exists Xvnc; then
        echo "Xvnc installed successfully"
    else
        echo "ERROR: Failed to install Xvnc"
        exit 1
    fi
fi

# Verify Xvnc works
echo "Testing Xvnc..."
if ! Xvnc -version 2>&1 | head -1; then
    echo "Warning: Xvnc version check failed"
fi

# Kill any existing Xvnc on this display
if [ -f /tmp/xvnc.pid ]; then
    OLD_PID=$(cat /tmp/xvnc.pid)
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "Killing existing Xvnc (PID: $OLD_PID)"
        kill "$OLD_PID" 2>/dev/null || true
        sleep 1
    fi
    rm -f /tmp/xvnc.pid
fi

# Test port availability
if python3 -c "import socket; s=socket.socket(); s.settimeout(1); s.bind(('127.0.0.1', $VNC_PORT)); s.close()" 2>/dev/null; then
    echo "Port $VNC_PORT is available"
else
    echo "Error: Port $VNC_PORT is in use"
    exit 1
fi

echo ""
echo "=== VNC Setup Complete ==="
echo "Xvnc will start on display :$DISPLAY_NUM port $VNC_PORT"
echo ""
