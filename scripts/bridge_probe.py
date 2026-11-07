"""Exercise NEO's local ADB bridge (which delegates capture/input to MaaFramework)."""
import socket
import struct
import sys
from pathlib import Path

def exact(sock, count):
    data = bytearray()
    while len(data) < count:
        part = sock.recv(count - len(data))
        if not part: raise EOFError('bridge disconnected')
        data.extend(part)
    return bytes(data)

def send(sock, command, a=0, b=0, data=b''):
    sock.sendall(struct.pack('<6I', command, a, b, len(data), sum(data), command ^ 0xffffffff) + data)

def receive(sock):
    header = struct.unpack('<6I', exact(sock, 24))
    return header[:3], exact(sock, header[3])

def execute(command):
    with socket.create_connection(('127.0.0.1', 23320), 30) as sock:
        sock.settimeout(60)
        send(sock, 0x4e584e43, 0x01000000, 65536, b'host::neo-test\0')
        receive(sock)
        send(sock, 0x4e45504f, 1, 0, ('shell:' + command + '\0').encode())
        output = bytearray()
        while True:
            (code, local, remote), data = receive(sock)
            if code == 0x45545257:
                output.extend(data); send(sock, 0x59414b4f, remote, local)
            if code == 0x45534c43: break
        if output.startswith(b'BAAH bridge error:'): raise RuntimeError(output.decode())
        return bytes(output)

if __name__ == '__main__':
    result = execute(sys.argv[1])
    if len(sys.argv) > 2:
        Path(sys.argv[2]).write_bytes(result); print('Saved', len(result), 'bytes')
    else: print(result.decode('utf8', 'replace'))
