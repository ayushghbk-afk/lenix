#!/bin/bash
# Lenix VNC Setup Script
# Sets up VNC server for remote desktop access
# Works both on host and inside proot/Debian environment

set -e

VNC_PORT=${1:-5902}
VNC_WIDTH=${2:-640}
VNC_HEIGHT=${3:-480}

echo "=== Lenix VNC Setup ==="
echo ""
echo "Port: $VNC_PORT"
echo "Resolution: ${VNC_WIDTH}x${VNC_HEIGHT}"
echo ""

# Detect environment - are we inside proot or on host?
if [ -f /proc/version ] && grep -q "proot" /proc/version 2>/dev/null; then
    INSIDE_PROT=true
    echo "Detected: Running inside proot environment"
else
    INSIDE_PROT=false
    echo "Detected: Running on host system"
fi

# Create bin directory if not exists
mkdir -p ~/bin

# Create VNC server script (standalone Python implementation)
if [ ! -f ~/bin/vncserver.py ]; then
    echo "Creating VNC server script..."
    cat > ~/bin/vncserver.py << 'VNCSERVER'
#!/usr/bin/env python3
"""
Lenix VNC Server - RFB Protocol Implementation
Works without X11, provides virtual framebuffer

Usage:
    python3 vncserver.py [--port N] [--width W] [--height H] [--password P]
"""

import socket
import struct
import threading
import time
import sys
import os
import signal
from typing import List, Optional, Tuple

RFB_VERSION_3_8 = b"RFB 003.008\n"


class VncServer:
    """Standalone VNC server with virtual framebuffer"""
    
    def __init__(self, width: int = 800, height: int = 600, 
                 port: int = 5900, password: Optional[str] = None):
        self.width = width
        self.height = height
        self.port = port
        self.password = password
        self.framebuffer = bytearray(width * height * 2)  # RGB565 format
        self.clients: List[socket.socket] = []
        self.running = False
        self.server_socket: Optional[socket.socket] = None
        self._setup_framebuffer()
    
    def _rgb565(self, r: int, g: int, b: int) -> int:
        """Convert RGB to RGB565 format"""
        return ((r & 0x1F) << 11) | ((g & 0x3F) << 5) | (b & 0x1F)
    
    def _setup_framebuffer(self):
        """Initialize framebuffer with gradient background"""
        for y in range(self.height):
            for x in range(self.width):
                idx = (y * self.width + x) * 2
                r = int(30 + (x / self.width) * 30)
                g = int(30 + (y / self.height) * 30)
                b = int(60)
                pixel = self._rgb565(r, g, b)
                self.framebuffer[idx] = (pixel >> 8) & 0xFF
                self.framebuffer[idx + 1] = pixel & 0xFF
    
    def draw_desktop(self, status: str = "Lenix VNC Server"):
        """Draw desktop UI elements"""
        self._setup_framebuffer()
        
        # Title bar
        for x in range(self.width):
            for y in range(30):
                idx = (y * self.width + x) * 2
                pixel = self._rgb565(40, 40, 120)
                self.framebuffer[idx] = (pixel >> 8) & 0xFF
                self.framebuffer[idx + 1] = pixel & 0xFF
        
        # Status text (simple block rendering)
        self._draw_text_simple(status, 10, 10, (255, 255, 255))
        self._draw_text_simple(f"Resolution: {self.width}x{self.height}", 10, 45, (200, 200, 200))
        self._draw_text_simple(f"Port: {self.port}", 10, 60, (200, 200, 200))
    
    def _draw_text_simple(self, text: str, x: int, y: int, color: Tuple[int, int, int]):
        """Simple text rendering using block characters"""
        for i, char in enumerate(text):
            char_x = x + (i * 10)
            if char_x >= self.width:
                break
            for dy in range(12):
                for dx in range(8):
                    px = char_x + dx
                    py = y + dy
                    if px >= self.width or py >= self.height:
                        continue
                    idx = (py * self.width + px) * 2
                    if char != ' ':
                        pixel = self._rgb565(*color)
                        self.framebuffer[idx] = (pixel >> 8) & 0xFF
                        self.framebuffer[idx + 1] = pixel & 0xFF
    
    def _handle_client(self, client_socket: socket.socket, address: tuple):
        """Handle VNC client connection"""
        try:
            client_socket.settimeout(30)
            
            # RFB handshake
            client_socket.sendall(RFB_VERSION_3_8)
            version = client_socket.recv(12)
            if not version.startswith(b"RFB"):
                return
            
            # Security negotiation
            if self.password:
                client_socket.sendall(struct.pack("!I", 1))  # VNC auth
                client_socket.recv(4)
                challenge = os.urandom(16)
                client_socket.sendall(challenge)
                client_socket.recv(16)  # Would verify response in production
            else:
                client_socket.sendall(struct.pack("!I", 1))
                client_socket.recv(4)
                client_socket.sendall(struct.pack("!I", 0))  # No auth
            
            # Server initialization
            pixel_format = (
                struct.pack("!B", 0) + struct.pack("!B", 1) +
                struct.pack("!B", 16) + struct.pack("!B", 16) +
                struct.pack("!H", 31) + struct.pack("!H", 63) +
                struct.pack("!H", 31) + struct.pack("!B", 11) +
                struct.pack("!B", 5) + struct.pack("!B", 0) +
                struct.pack("!B", 0)
            )
            
            server_name = b"Lenix VNC Server"
            server_init = (
                struct.pack("!HH", self.width, self.height) +
                pixel_format +
                struct.pack("!I", len(server_name)) +
                server_name
            )
            
            msg = b'\x00' + server_init
            msg = struct.pack("!I", len(msg) + 4) + msg
            client_socket.sendall(msg)
            
            client_socket.recv(4)  # Client init
            
            # Main loop
            while self.running:
                try:
                    client_socket.settimeout(1.0)
                    header = client_socket.recv(4)
                    if not header:
                        break
                    
                    msg_type = header[0]
                    
                    if msg_type == 0:  # SetPixelFormat
                        client_socket.recv(20)
                    elif msg_type == 1:  # SetEncodings
                        count = struct.unpack("!I", client_socket.recv(4))[0]
                        client_socket.recv(count * 4)
                    elif msg_type == 2:  # FramebufferUpdateRequest
                        req = client_socket.recv(12)
                        x = struct.unpack("!H", req[2:4])[0]
                        y = struct.unpack("!H", req[4:6])[0]
                        w = struct.unpack("!H", req[6:8])[0]
                        h = struct.unpack("!H", req[8:10])[0]
                        self._send_framebuffer_update(client_socket, x, y, w, h)
                    elif msg_type == 5:  # Cursor
                        client_socket.recv(24)
                        
                except socket.timeout:
                    continue
                except Exception:
                    break
                    
        except Exception as e:
            print(f"Client error: {e}", file=sys.stderr)
        finally:
            try:
                client_socket.close()
                self.clients.remove(client_socket)
            except:
                pass
    
    def _send_framebuffer_update(self, client_socket: socket.socket, 
                                   x: int, y: int, w: int, h: int):
        """Send framebuffer update"""
        rect_count = 1
        msg = struct.pack("!BBB", 2, 0, rect_count)
        msg += struct.pack("!HHHH", x, y, w, h)
        msg += struct.pack("!I", 2)  # RGB565 encoding
        
        for row in range(h):
            src_y = y + row
            if src_y >= self.height:
                break
            src_start = src_y * self.width * 2
            dst_start = x * 2
            chunk = self.framebuffer[src_start + dst_start: src_start + dst_start + w * 2]
            msg += chunk
        
        padding = (4 - len(msg) % 4) % 4
        msg += b'\x00' * padding
        
        try:
            client_socket.sendall(struct.pack("!I", len(msg)) + msg)
        except:
            pass
    
    def start(self):
        """Start VNC server"""
        self.running = True
        
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind(('0.0.0.0', self.port))
        self.server_socket.listen(5)
        self.server_socket.settimeout(1.0)
        
        print(f"VNC Server started on port {self.port}")
        print(f"Resolution: {self.width}x{self.height}")
        print(f"Connect: 0.0.0.0:{self.port}")
        
        self.draw_desktop("Lenix VNC Server - Running")
        
        try:
            while self.running:
                try:
                    client_socket, address = self.server_socket.accept()
                    print(f"Client connected: {address[0]}:{address[1]}")
                    self.clients.append(client_socket)
                    
                    thread = threading.Thread(
                        target=self._handle_client,
                        args=(client_socket, address),
                        daemon=True
                    )
                    thread.start()
                except socket.timeout:
                    continue
                except Exception as e:
                    if self.running:
                        print(f"Error: {e}", file=sys.stderr)
        except KeyboardInterrupt:
            print("\nShutting down...")
        finally:
            self.stop()
    
    def stop(self):
        """Stop VNC server"""
        self.running = False
        if self.server_socket:
            try:
                self.server_socket.close()
            except:
                pass


def main():
    import argparse
    
    parser = argparse.ArgumentParser(description='Lenix VNC Server')
    parser.add_argument('--port', '-p', type=int, default=5900)
    parser.add_argument('--width', '-w', type=int, default=800)
    parser.add_argument('--height', '-h', type=int, default=600)
    parser.add_argument('--password', type=str, default=None)
    
    args = parser.parse_args()
    
    server = VncServer(
        width=args.width,
        height=args.height,
        port=args.port,
        password=args.password
    )
    
    def signal_handler(sig, frame):
        server.stop()
        sys.exit(0)
    
    signal.signal(signal.SIGTERM, signal_handler)
    signal.signal(signal.SIGINT, signal_handler)
    
    server.start()


if __name__ == '__main__':
    main()
VNCSERVER
    chmod +x ~/bin/vncserver.py
    echo "   Created ~/bin/vncserver.py"
fi

# Create unified vnc command with auto-install support
if [ ! -f ~/bin/vnc ]; then
    echo "Creating vnc command with auto-install..."
    cat > ~/bin/vnc << VNC_SCRIPT
#!/bin/bash
# VNC management with auto-install support
# Works on host and inside proot/Debian

set -e

VNC_PORT=\${1:-5902}
ACTION=\${2:-auto}

# Detect environment
if [ -f /proc/version ] && grep -q "proot" /proc/version 2>/dev/null; then
    INSIDE_PROT=true
else
    INSIDE_PROT=false
fi

start_vnc() {
    echo "Starting VNC Server..."
    
    # Check if already running
    if pgrep -f "vncserver.py" > /dev/null 2>&1; then
        echo "VNC Server already running"
        return 0
    fi
    
    # Start VNC server
    nohup python3 ~/bin/vncserver.py --port \$VNC_PORT --width 640 --height 480 > ~/vnc.log 2>&1 &
    VNC_PID=\$!
    
    # Wait for server to be ready
    for i in {1..10}; do
        sleep 0.5
        if pgrep -f "vncserver.py" > /dev/null 2>&1; then
            # Verify port is listening
            if python3 -c "import socket; s=socket.socket(); s.settimeout(1)
try:
    s.connect(('127.0.0.1',\$VNC_PORT))
    s.close()
    exit(0)
except:
    exit(1)" 2>/dev/null; then
                echo "VNC Server started (PID: \$VNC_PID)"
                echo "Connect to: 0.0.0.0:\$VNC_PORT"
                return 0
            fi
        fi
    done
    
    echo "Failed to start VNC Server. Check ~/vnc.log"
    return 1
}

stop_vnc() {
    echo "Stopping VNC Server..."
    pkill -f "vncserver.py" 2>/dev/null
    echo "VNC Server stopped"
}

status_vnc() {
    if pgrep -f "vncserver.py" > /dev/null 2>&1; then
        echo "VNC Server: RUNNING"
        pgrep -af vncserver.py | grep python
        echo ""
        echo "Connection: 0.0.0.0:\$VNC_PORT"
        
        # Test connection
        if python3 -c "import socket; s=socket.socket(); s.settimeout(2)
try:
    s.connect(('127.0.0.1',\$VNC_PORT))
    print('Port \$VNC_PORT: OPEN')
    s.close()
except Exception as e:
    print(f'Port \$VNC_PORT: CLOSED - {e}')" 2>/dev/null; then
            :
        fi
    else
        echo "VNC Server: STOPPED"
    fi
}

case "\$ACTION" in
    start)
        start_vnc
        ;;
    stop)
        stop_vnc
        ;;
    status)
        status_vnc
        ;;
    auto|"")
        # Auto-start: check if running, start if not
        if ! pgrep -f "vncserver.py" > /dev/null 2>&1; then
            start_vnc
        else
            echo "VNC Server already running"
        fi
        status_vnc
        ;;
    install)
        # Auto-install VNC dependencies inside proot
        if \$INSIDE_PROT; then
            echo "Installing VNC dependencies inside proot..."
            apt-get update -qq 2>/dev/null || true
            apt-get install -y -qq python3 2>/dev/null || true
            echo "Dependencies installed"
        else
            echo "Not inside proot - no installation needed"
        fi
        start_vnc
        ;;
    *)
        echo "Usage: vnc [PORT] [ACTION]"
        echo ""
        echo "Actions:"
        echo "  start    - Start VNC server"
        echo "  stop     - Stop VNC server"
        echo "  status   - Check VNC status"
        echo "  auto     - Auto-start (default)"
        echo "  install  - Install dependencies and start"
        echo ""
        echo "Examples:"
        echo "  vnc           - Auto-start VNC"
        echo "  vnc 5900      - Start on port 5900"
        echo "  vnc 5900 start - Start on port 5900"
        ;;
esac
VNC_SCRIPT
    chmod +x ~/bin/vnc
    echo "   Created ~/bin/vnc with auto-install support"
fi

# Add to PATH if not already
if ! grep -q 'export PATH=.*HOME/bin' ~/.bashrc; then
    echo '' >> ~/.bashrc
    echo '# VNC and tools' >> ~/.bashrc
    echo 'export PATH="$HOME/bin:$PATH"' >> ~/.bashrc
    echo "   Added ~/bin to PATH"
fi

# Add VNC auto-start to bashrc
if ! grep -q 'vncserver.py' ~/.bashrc 2>/dev/null; then
    cat >> ~/.bashrc << 'AUTOSTART'

# Auto-start VNC on shell initialization
if [ -x ~/bin/vncserver.py ] && [ -z "\$SSH_TTY" ]; then
    # Only auto-start for local shells, not SSH
    if ! pgrep -f "vncserver.py" > /dev/null 2>&1; then
        nohup python3 ~/bin/vncserver.py --port 5902 --width 640 --height 480 > ~/vnc.log 2>&1 &
    fi
fi
AUTOSTART
    echo "   Added VNC auto-start to ~/.bashrc"
fi

# Add aliases
if ! grep -q 'alias vnc=' ~/.bashrc; then
    cat >> ~/.bashrc << 'ALIASES'

# VNC aliases
alias vnc='~/bin/vnc'
alias vnc-start='~/bin/vnc 5902 start'
alias vnc-stop='~/bin/vnc 5902 stop'
alias vnc-status='~/bin/vnc 5902 status'
ALIASES
    echo "   Added VNC aliases"
fi

echo ""
echo "=== VNC Setup Complete ==="
echo ""
echo "Usage:"
echo "  vnc           - Auto-start VNC and show status"
echo "  vnc start     - Start VNC server"
echo "  vnc stop      - Stop VNC server"
echo "  vnc status    - Check VNC status"
echo "  vnc install   - Install dependencies and start"
echo ""
echo "Connect VNC client to: 0.0.0.0:5902"
