"""Send one console command to the local Paper test server over RCON.

Usage: python rcon.py [server folder] <command...>     e.g.  python rcon.py stop
The port and password are read from the server's server.properties (never printed).
"""
import os
import socket
import struct
import sys

DEFAULT_SERVER = '.'  # run from the server folder, or pass it first


def props(folder):
    out = {}
    with open(os.path.join(folder, 'server.properties'), encoding='utf-8') as f:
        for line in f:
            if '=' in line and not line.startswith('#'):
                k, v = line.rstrip('\n').split('=', 1)
                out[k] = v
    return out


def packet(req_id, kind, body):
    data = struct.pack('<ii', req_id, kind) + body.encode('utf-8') + b'\x00\x00'
    return struct.pack('<i', len(data)) + data


def read(sock):
    size = struct.unpack('<i', sock.recv(4))[0]
    data = b''
    while len(data) < size:
        data += sock.recv(size - len(data))
    req_id, _ = struct.unpack('<ii', data[:8])
    return req_id, data[8:-2].decode('utf-8', 'replace')


def main():
    args = sys.argv[1:]
    folder = DEFAULT_SERVER
    if args and os.path.isdir(args[0]):
        folder = args.pop(0)
    if not args:
        sys.exit(__doc__)
    p = props(folder)
    if p.get('enable-rcon') != 'true' or not p.get('rcon.password'):
        sys.exit('RCON is off in ' + folder + '/server.properties')
    try:
        sock = socket.create_connection(('127.0.0.1', int(p.get('rcon.port', '25575'))), timeout=5)
    except OSError:
        sys.exit('The server is not running (nothing on the RCON port).')
    with sock:
        sock.sendall(packet(1, 3, p['rcon.password']))
        if read(sock)[0] == -1:
            sys.exit('RCON password rejected.')
        sock.sendall(packet(2, 2, ' '.join(args)))
        try:
            print(read(sock)[1] or '(sent)')
        except (OSError, struct.error):
            print('(sent; the server closed the connection)')


if __name__ == '__main__':
    main()
