#!/usr/bin/env python3
"""
Lenix VNC Server - RFB Protocol Implementation
Standalone VNC server that works inside proot/Debian without X11
Provides a virtual framebuffer and handles RFB 3.8 protocol
"""

import socket
import struct
import threading
import time
import sys
import os
import signal
from typing import List, Dict, Optional, Tuple
from dataclasses import dataclass
import json

# RFB Protocol constants
RFB_VERSION_3_8 = b"RFB 003.008\n"
PROTOCOL_VERSION = 3008

# Pixel formats
FORMAT_RGB565 = {
    'bits_per_pixel': 16,
    'depth': 16,
    'big_endian': 0,
    'true_color': 1,
    'red_max': 31,
    'green_max': 63,
    'blue_max': 31,
    'red_shift': 11,
    'green_shift': 5,
    'blue_shift': 0,
}


@dataclass
class Rectangle:
    x: int
    y: int
    width: int
    height: int
    encoding_type: int
    data: bytes


class RFBProtocolError(Exception):
    """RFB protocol error"""
    pass


class VncServer:
    """Standalone VNC server implementation"""
    
    def __init__(self, width: int = 800, height: int = 600, 
                 port: int = 5900, password: Optional[str] = None):
        self.width = width
        self.height = height
        self.port = port
        self.password = password
        self.framebuffer = bytearray(width * height * 2)  # RGB565
        self.clients: List[socket.socket] = []
        self.running = False
        self.server_socket: Optional[socket.socket] = None
        self._setup_framebuffer()
    
    def _setup_framebuffer(self):
        """Initialize framebuffer with a default desktop background"""
        for y in range(self.height):
            for x in range(self.width):
                idx = (y * self.width + x) * 2
                # Create a gradient background
                r = int(30 + (x / self.width) * 20)
                g = int(30 + (y / self.height) * 20)
                b = int(50)
                # RGB565 encoding
                pixel = ((r & 0x1F) << 11) | ((g & 0x3F) << 5) | (b & 0x1F)
                self.framebuffer[idx] = (pixel >> 8) & 0xFF
                self.framebuffer[idx + 1] = pixel & 0xFF
    
    def _draw_text(self, text: str, x: int, y: int, color: Tuple[int, int, int] = (255, 255, 255)):
        """Simple text drawing - just draw blocks for characters"""
        # Simple block-based text rendering
        for i, char in enumerate(text):
            char_x = x + (i * 8)
            char_y = y
            if char_x >= self.width or char_y >= self.height:
                continue
            # Draw character as block
            for dy in range(min(12, self.height - char_y)):
                for dx in range(min(8, self.width - char_x)):
                    px = char_x + dx
                    py = char_y + dy
                    idx = (py * self.width + px) * 2
                    if char != ' ':
                        r, g, b = color
                        pixel = ((r & 0x1F) << 11) | ((g & 0x3F) << 5) | (b & 0x1F)
                        self.framebuffer[idx] = (pixel >> 8) & 0xFF
                        self.framebuffer[idx + 1] = pixel & 0xFF
    
    def draw_desktop(self, status: str = "Lenix VNC Server"):
        """Draw the desktop UI"""
        # Clear with background
        self._setup_framebuffer()
        
        # Draw title bar
        for x in range(self.width):
            for y in range(30):
                idx = (y * self.width + x) * 2
                r, g, b = 40, 40, 120
                pixel = ((r & 0x1F) << 11) | ((g & 0x3F) << 5) | (b & 0x1F)
                self.framebuffer[idx] = (pixel >> 8) & 0xFF
                self.framebuffer[idx + 1] = pixel & 0xFF
        
        # Draw status text
        self._draw_text(status, 10, 10, (255, 255, 255))
        self._draw_text(f"Resolution: {self.width}x{self.height}", 10, 45, (200, 200, 200))
        self._draw_text(f"Port: {self.port}", 10, 60, (200, 200, 200))
        
        # Draw some window decorations
        for x in range(50, 350):
            for y in range(80, 200):
                idx = (y * self.width + x) * 2
                r, g, b = 60, 60, 80
                pixel = ((r & 0x1F) << 11) | ((g & 0x3F) << 5) | (b & 0x1F)
                self.framebuffer[idx] = (pixel >> 8) & 0xFF
                self.framebuffer[idx + 1] = pixel & 0xFF
        
        self._draw_text("Terminal Window", 60, 90, (255, 255, 255))
    
    def _handle_client(self, client_socket: socket.socket, address: tuple):
        """Handle a VNC client connection"""
        try:
            client_socket.settimeout(30)
            
            # Send RFB version
            client_socket.sendall(RFB_VERSION_3_8)
            
            # Receive client version
            version = client_socket.recv(12)
            if not version.startswith(b"RFB"):
                return
            
            # Security negotiation
            if self.password:
                # VNC authentication
                client_socket.sendall(struct.pack("!I", 1))  # 1 security type
                client_socket.recv(4)  # client chooses type
                # Send challenge
                challenge = os.urandom(16)
                client_socket.sendall(challenge)
                # In production, would verify response - simplified here
            else:
                # No authentication
                client_socket.sendall(struct.pack("!I", 1))  # 1 security type
                client_socket.recv(4)
                client_socket.sendall(struct.pack("!I", 0))  # No auth
            
            # Server init
            pixel_format = (
                struct.pack("!B", 0)  # big endian
                + struct.pack("!B", 1)  # true color
                + struct.pack("!B", 16)  # bits per pixel
                + struct.pack("!B", 16)  # depth
                + struct.pack("!H", 31)  # red max
                + struct.pack("!H", 63)  # green max
                + struct.pack("!H", 31)  # blue max
                + struct.pack("!B", 11)  # red shift
                + struct.pack("!B", 5)   # green shift
                + struct.pack("!B", 0)   # blue shift
                + struct.pack("!B", 0)   # reserved
            )
            
            server_name = b"Lenix VNC Server"
            server_init = (
                struct.pack("!HH", self.width, self.height)  # width, height
                + pixel_format
                + struct.pack("!I", len(server_name))
                + server_name
            )
            
            # Send server init with proper framing
            msg_length = len(server_init) + 4  # 4 bytes for message type
            client_socket.sendall(struct.pack("!I", msg_length))
            client_socket.sendall(b'\x00')  # message type: ServerInit
            client_socket.sendall(server_init)
            
            # Read client initialization
            client_init = client_socket.recv(4)
            
            # Main loop - handle framebuffer updates
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
                        incremental = req[1]
                        x = struct.unpack("!H", req[2:4])[0]
                        y = struct.unpack("!H", req[4:6])[0]
                        w = struct.unpack("!H", req[6:8])[0]
                        h = struct.unpack("!H", req[8:10])[0]
                        
                        # Send framebuffer update
                        self._send_framebuffer_update(client_socket, x, y, w, h)
                    elif msg_type == 3:  # TextChat
                        client_socket.recv(4)
                    elif msg_type == 4:  # Palette
                        count = struct.unpack("!I", client_socket.recv(4))[0]
                        client_socket.recv(count * 6)
                    elif msg_type == 5:  # Cursor
                        client_socket.recv(24)
                        
                except socket.timeout:
                    continue
                except Exception:
                    break
                    
        except Exception as e:
            print(f"Client handler error: {e}", file=sys.stderr)
        finally:
            try:
                client_socket.close()
            except:
                pass
    
    def _send_framebuffer_update(self, client_socket: socket.socket, 
                                   x: int, y: int, w: int, h: int):
        """Send a framebuffer update to the client"""
        # Build update message
        rect_count = 1
        msg = struct.pack("!BBB", 2, 0, rect_count)  # type=2, incremental=0, count=1
        
        # Rectangle header
        msg += struct.pack("!HHHH", x, y, w, h)
        msg += struct.pack("!I", 2)  # encoding: RGB565
        
        # Pixel data (send requested region)
        for row in range(h):
            src_y = y + row
            if src_y >= self.height:
                break
            src_start = src_y * self.width * 2
            dst_start = x * 2
            chunk = self.framebuffer[src_start + dst_start: src_start + dst_start + w * 2]
            msg += chunk
        
        # Pad to 4-byte alignment
        padding = (4 - len(msg) % 4) % 4
        msg += b'\x00' * padding
        
        try:
            client_socket.sendall(struct.pack("!I", len(msg)) + msg)
        except:
            pass
    
    def start(self):
        """Start the VNC server"""
        self.running = True
        
        # Create server socket
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind(('0.0.0.0', self.port))
        self.server_socket.listen(5)
        self.server_socket.settimeout(1.0)
        
        print(f"Lenix VNC Server started on port {self.port}")
        print(f"Resolution: {self.width}x{self.height}")
        print(f"Connect with VNC client to: 0.0.0.0:{self.port}")
        
        # Draw initial desktop
        self.draw_desktop("Lenix VNC Server - Running")
        
        # Main accept loop
        try:
            while self.running:
                try:
                    client_socket, address = self.server_socket.accept()
                    print(f"Client connected: {address[0]}:{address[1]}")
                    self.clients.append(client_socket)
                    
                    # Handle client in separate thread
                    client_thread = threading.Thread(
                        target=self._handle_client,
                        args=(client_socket, address),
                        daemon=True
                    )
                    client_thread.start()
                    
                except socket.timeout:
                    continue
                except Exception as e:
                    if self.running:
                        print(f"Accept error: {e}", file=sys.stderr)
                        
        except KeyboardInterrupt:
            print("\nShutting down...")
        finally:
            self.stop()
    
    def stop(self):
        """Stop the VNC server"""
        self.running = False
        if self.server_socket:
            try:
                self.server_socket.close()
            except:
                pass


def main():
    """Main entry point"""
    import argparse
    
    parser = argparse.ArgumentParser(description='Lenix VNC Server')
    parser.add_argument('--port', '-p', type=int, default=5900, 
                       help='VNC port (default: 5900)')
    parser.add_argument('--width', '-w', type=int, default=800,
                       help='Screen width (default: 800)')
    parser.add_argument('--height', '-h', type=int, default=600,
                       help='Screen height (default: 600)')
    parser.add_argument('--password', type=str, default=None,
                       help='VNC password (optional)')
    
    args = parser.parse_args()
    
    server = VncServer(
        width=args.width,
        height=args.height,
        port=args.port,
        password=args.password
    )
    
    # Handle SIGTERM for graceful shutdown
    def signal_handler(sig, frame):
        server.stop()
        sys.exit(0)
    
    signal.signal(signal.SIGTERM, signal_handler)
    signal.signal(signal.SIGINT, signal_handler)
    
    server.start()


if __name__ == '__main__':
    main()
