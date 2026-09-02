#!/bin/bash
# Lenix VNC Setup Script
# Sets up VNC server for remote desktop access

set -e

VNC_PORT=${1:-5902}
VNC_WIDTH=${2:-640}
VNC_HEIGHT=${3:-480}

echo "=== Lenix VNC Setup ==="
echo ""
echo "Port: $VNC_PORT"
echo "Resolution: ${VNC_WIDTH}x${VNC_HEIGHT}"
echo ""

# Create bin directory if not exists
mkdir -p ~/bin

# Create VNC server script
if [ ! -f ~/bin/vncserver.py ]; then
    echo "Creating VNC server script..."
    cat > ~/bin/vncserver.py << 'VNCSERVER'
#!/usr/bin/env python3
"""Minimal VNC Server - RFB Protocol"""
import socket, struct, threading, sys, signal, time

RFB_VERSION = b"RFB 003.008\n"

class Client:
    def __init__(self, conn, addr, w, h, fb):
        self.conn, self.addr = conn, addr
        self.w, self.h, self.fb = w, h, fb
        self.alive = True
        self.server = None

    def send_all(self, data):
        try: self.conn.sendall(data)
        except: self.alive = False

    def send_msg(self, t, data):
        msg = bytes([t]) + data
        ln = (len(msg) + 3) & ~3
        self.send_all(struct.pack("!I", ln) + msg)

    def handshake(self):
        try:
            self.send_all(RFB_VERSION)
            v = self.conn.recv(12)
            if not v.startswith(b"RFB"): return False
            self.send_all(struct.pack("!I", 1) + b"\x00")
            s = struct.unpack("!I", self.conn.recv(4))[0]
            if s != 0: return False
            name = b"PythonVNC"
            np = (4 - len(name) % 4) % 4
            fmt = struct.pack("!BB", 0, 1) + struct.pack("!BBBB", 16, 0, 0, 0) + struct.pack("!BBBB", 5, 11, 0, 0)
            init = struct.pack("!HH", self.w, self.h) + fmt + struct.pack("!I", len(name)+np) + name + b"\x00"*np
            self.send_msg(0, init)
            ci = self.conn.recv(4)
            return len(ci) >= 4
        except: return False

    def handle(self):
        try:
            while self.alive:
                h = self.conn.recv(4)
                if len(h) < 4: break
                t = h[0]
                if t == 0: self.conn.recv(12)
                elif t == 1: n = struct.unpack("!I", self.conn.recv(4))[0]; self.conn.recv(4*n)
                elif t == 2: self.conn.recv(12); self.server.notify()
                elif t == 3: n = struct.unpack("!I", self.conn.recv(4))[0]; self.conn.recv((n+3)&~3)
                else: self.conn.recv(1024)
        except: pass
        finally: self.alive = False

class Server:
    def __init__(self, w=640, h=480, port=5902):
        self.w, self.h, self.port = w, h, port
        self.running, self.sock = False, None
        self.fb = bytearray(w*h*4)
        self.clients, self.lock = [], threading.Lock()
        self.update = threading.Event()

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("0.0.0.0", self.port))
        self.sock.listen(5)
        self.sock.settimeout(1.0)
        self.running = True
        print(f"VNC Server started on port {self.port}")
        print(f"Resolution: {self.w}x{self.h}")
        threading.Thread(target=self.accept_loop, daemon=True).start()
        threading.Thread(target=self.render_loop, daemon=True).start()
        signal.signal(signal.SIGINT, self._stop)
        signal.signal(signal.SIGTERM, self._stop)
        try:
            while self.running: time.sleep(1)
        except KeyboardInterrupt: self.stop()

    def accept_loop(self):
        while self.running:
            try:
                conn, addr = self.sock.accept()
                c = Client(conn, addr, self.w, self.h, self.fb)
                if c.handshake():
                    c.server = self
                    with self.lock: self.clients.append(c)
                    print(f"Client connected: {addr[0]}:{addr[1]}")
                    threading.Thread(target=c.handle, daemon=True).start()
                else:
                    try: conn.close()
                    except: pass
            except socket.timeout: continue
            except Exception as e:
                if self.running: print(f"Error: {e}")

    def render_loop(self):
        while self.running:
            self.draw()
            self.update.set()
            with self.lock:
                for c in list(self.clients):
                    if not c.alive: self.clients.remove(c); continue
                    try:
                        c.fb = self.fb
                        self.send_fb(c)
                    except: c.alive = False
            self.update.wait(0.1)
            self.update.clear()

    def send_fb(self, c):
        data = bytes(c.fb)
        msg = struct.pack("!BBB", 2, 0, 1) + struct.pack("!HHHH", 0, 0, self.w, self.h) + struct.pack("!I", 0) + data
        ln = (len(msg) + 3) & ~3
        c.send_all(struct.pack("!I", ln) + msg)

    def notify(self): self.update.set()

    def draw(self):
        for y in range(self.h):
            for x in range(self.w):
                i = y*self.w + x
                if y < 25: col = [60,60,140] if x<40 or x>=self.w-40 else [50,50,120]
                elif y >= self.h-15: col = [40,40,60]
                elif (y//15)%2==0: col = [30,30,45]
                else: col = [25,25,40]
                self.fb[i*4] = col[0]; self.fb[i*4+1] = col[1]; self.fb[i*4+2] = col[2]; self.fb[i*4+3] = 255

    def _stop(self, s, f): self.stop()
    def stop(self):
        self.running = False
        if self.sock:
            try: self.sock.close()
            except: pass
        print("\nVNC Server stopped")

def main():
    port, w, h = 5902, 640, 480
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        if args[i] in ("--port","-p") and i+1<len(args): port=int(args[i+1]); i+=2
        elif args[i] in ("--width","-w") and i+1<len(args): w=int(args[i+1]); i+=2
        elif args[i] in ("--height","-h") and i+1<len(args): h=int(args[i+1]); i+=2
        elif args[i] in ("--help","-h"): print("Usage: vncserver.py [--port N] [--width W] [--height H]"); return
        else: i+=1
    Server(w, h, port).start()

if __name__ == "__main__": main()
VNCSERVER
    chmod +x ~/bin/vncserver.py
    echo "   Created ~/bin/vncserver.py"
fi

# Create vnc command
if [ ! -f ~/bin/vnc ]; then
    echo "Creating vnc command..."
    cat > ~/bin/vnc << 'VNC_SCRIPT'
#!/bin/bash
# VNC management command

 case "${1:-}" in
     start|auto)
         if ! pgrep -f "vncserver.py" > /dev/null 2>&1; then
             nohup python3 ~/bin/vncserver.py --port 5902 --width 640 --height 480 > ~/vnc.log 2>&1 &
             echo "VNC server starting..."
             sleep 2
         fi
         ;;
     stop)
         pkill -f "vncserver.py" 2>/dev/null
         echo "VNC server stopped"
         ;;
     status)
         if pgrep -f "vncserver.py" > /dev/null 2>&1; then
             echo "VNC Server: RUNNING"
             pgrep -af vncserver.py | grep python
         else
             echo "VNC Server: STOPPED"
         fi
         ;;
     *)
         if ! pgrep -f "vncserver.py" > /dev/null 2>&1; then
             nohup python3 ~/bin/vncserver.py --port 5902 --width 640 --height 480 > ~/vnc.log 2>&1 &
             sleep 2
         fi
         echo "VNC Address: 0.0.0.0:5902"
         echo "Resolution: 640x480"
         ;;
 esac
VNC_SCRIPT
    chmod +x ~/bin/vnc
    echo "   Created ~/bin/vnc"
fi

# Add to PATH if not already
if ! grep -q 'export PATH=.*HOME/bin' ~/.bashrc; then
    echo '' >> ~/.bashrc
    echo '# VNC and tools' >> ~/.bashrc
    echo 'export PATH="$HOME/bin:$PATH"' >> ~/.bashrc
    echo "   Added ~/bin to PATH"
fi

# Add VNC auto-start
if ! grep -q 'vncserver.py' ~/.bashrc; then
    cat >> ~/.bashrc << 'AUTOSTART'

# Auto-start VNC
if [ -x ~/bin/vncserver.py ] && ! pgrep -f "vncserver.py" > /dev/null 2>&1; then
    nohup python3 ~/bin/vncserver.py --port 5902 --width 640 --height 480 > ~/vnc.log 2>&1 &
fi
AUTOSTART
    echo "   Added VNC auto-start to ~/.bashrc"
fi

# Add aliases
if ! grep -q 'alias vnc=' ~/.bashrc; then
    cat >> ~/.bashrc << 'ALIASES'

# VNC aliases
alias vnc='~/bin/vnc'
alias vnc-start='~/bin/vnc start'
alias vnc-stop='~/bin/vnc stop'
alias vnc-status='~/bin/vnc status'
ALIASES
    echo "   Added VNC aliases"
fi

echo ""
echo "=== VNC Setup Complete ==="
echo ""
echo "Usage:"
echo "  vnc        Start VNC and show connection info"
echo "  vnc start  Start VNC server"
echo "  vnc stop   Stop VNC server"
echo "  vnc status Check VNC status"
echo ""
echo "Connect VNC client to: 0.0.0.0:5902"
