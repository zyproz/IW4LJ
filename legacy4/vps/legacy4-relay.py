#!/usr/bin/env python3
"""Legacy 4 online: server list + relay for matches hosted on players' phones (cod4-decomp listen servers).

The host's phone runs the match. Its app keeps a link to this relay (UDP, control port 4435) while it hosts; the
relay gives that match a public UDP port (4440-4499) and passes every packet between the players and the host's
phone, so nobody needs Tailscale, a router setting or a public address. The Internet list of the game asks this
same port for the matches (the cod4e master protocol: getchallenge / getservers), and gets this VPS's address with
each match's port; the game then talks to that port as to any server.

Nothing is written to disk: matches and players live in memory and go when the link stops.
Usage: legacy4-relay.py <public IP of this VPS> [control port] [first game port] [last game port]
"""
import asyncio
import os
import struct
import sys
import time

PUBLIC_IP = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
CONTROL = int(sys.argv[2]) if len(sys.argv) > 2 else 4435
FIRST = int(sys.argv[3]) if len(sys.argv) > 3 else 4440
LAST = int(sys.argv[4]) if len(sys.argv) > 4 else 4499

MAGIC = b"L4R1"
OOB = b"\xff\xff\xff\xff"
HOST_TTL = 10.0         # a host that stops sending its link packet (every 2 s) is gone after this
CLIENT_TTL = 120.0      # a player silent this long is forgotten
MAX_CLIENTS = 64        # players (and server-list queries) one match may have at once
MAX_HOSTS_PER_IP = 3
SERVERS_PER_PACKET = 40


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


class Host:
    def __init__(self, agent, port):
        self.agent = agent
        self.port = port
        self.session = int.from_bytes(os.urandom(4), "big")
        self.name = ""
        self.last = time.monotonic()
        self.transport = None
        self.by_addr = {}   # player address -> id
        self.by_id = {}     # id -> [address, last packet time]
        self.next_id = 1

    def client_id(self, addr, now):
        cid = self.by_addr.get(addr)
        if cid is None:
            if len(self.by_addr) >= MAX_CLIENTS:
                return None
            cid = self.next_id
            self.next_id = self.next_id % 65535 + 1
            old = self.by_id.pop(cid, None)
            if old:
                self.by_addr.pop(old[0], None)
            self.by_addr[addr] = cid
            self.by_id[cid] = [addr, now]
        else:
            self.by_id[cid][1] = now
        return cid


hosts = {}          # agent address -> Host
pending = set()     # agent addresses whose game port is being opened
control = None


class Game(asyncio.DatagramProtocol):
    """The public port of one match: what players send goes to the host's phone."""

    def __init__(self, host):
        self.host = host

    def datagram_received(self, data, addr):
        h = self.host
        cid = h.client_id(addr, time.monotonic())
        if cid is not None and control is not None:
            control.sendto(MAGIC + b"D" + struct.pack(">H", cid) + data, h.agent)


class Control(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        global control
        control = transport

    def datagram_received(self, data, addr):
        if data.startswith(OOB):
            self.master(data, addr)
        elif data.startswith(MAGIC) and len(data) >= 5:
            kind = data[4:5]
            if kind == b"D" and len(data) >= 7:
                h = hosts.get(addr)
                if h and h.transport:
                    entry = h.by_id.get(struct.unpack(">H", data[5:7])[0])
                    if entry:
                        h.transport.sendto(data[7:], entry[0])
            elif kind == b"R":
                self.register(addr, data[5:69].decode("utf-8", "replace"))
            elif kind == b"X":
                h = hosts.get(addr)
                if h:
                    close(h, "host left")

    def master(self, data, addr):
        text = data[4:].decode("latin-1").strip("\0\n ")
        cmd = text.split(" ", 1)[0]
        if cmd == "getchallenge":
            control.sendto(OOB + b"challengeResponse 1", addr)
        elif cmd == "getservers":
            live = [f"{PUBLIC_IP}:{h.port}" for h in hosts.values() if h.transport]
            for i in range(0, max(len(live), 1), SERVERS_PER_PACKET):
                words = " ".join(live[i:i + SERVERS_PER_PACKET])
                control.sendto(OOB + ("getserversResponse " + words).strip().encode(), addr)

    def register(self, addr, name):
        h = hosts.get(addr)
        if h:
            h.last = time.monotonic()
            h.name = name
            if h.transport:
                control.sendto(MAGIC + b"P" + struct.pack(">HI", h.port, h.session), addr)
            return
        if addr in pending:
            return
        if sum(1 for a in hosts if a[0] == addr[0]) >= MAX_HOSTS_PER_IP:
            return
        used = {h.port for h in hosts.values()}
        port = next((p for p in range(FIRST, LAST + 1) if p not in used), None)
        if port is None:
            log("no free game port")
            return
        pending.add(addr)
        asyncio.get_running_loop().create_task(self.open(addr, port, name))

    async def open(self, addr, port, name):
        h = Host(addr, port)
        h.name = name
        try:
            transport, _ = await asyncio.get_running_loop().create_datagram_endpoint(
                lambda: Game(h), local_addr=("0.0.0.0", port))
        except OSError as e:
            log("cannot open port", port, e)
            pending.discard(addr)
            return
        h.transport = transport
        hosts[addr] = h
        pending.discard(addr)
        control.sendto(MAGIC + b"P" + struct.pack(">HI", h.port, h.session), addr)
        log(f"match open on port {port} ({len(hosts)} live): {name!r}")


def close(h, why):
    hosts.pop(h.agent, None)
    if h.transport:
        h.transport.close()
    log(f"match on port {h.port} closed ({why}), {len(hosts)} live")


async def sweep():
    while True:
        await asyncio.sleep(2)
        now = time.monotonic()
        for h in list(hosts.values()):
            if now - h.last > HOST_TTL:
                close(h, "no link")
                continue
            for cid, (addr, last) in list(h.by_id.items()):
                if now - last > CLIENT_TTL:
                    del h.by_id[cid]
                    h.by_addr.pop(addr, None)


async def main():
    loop = asyncio.get_running_loop()
    await loop.create_datagram_endpoint(Control, local_addr=("0.0.0.0", CONTROL))
    log(f"legacy4-relay: list and links on udp {CONTROL}, matches on udp {FIRST}-{LAST}, public address {PUBLIC_IP}")
    await sweep()


if __name__ == "__main__":
    asyncio.run(main())
