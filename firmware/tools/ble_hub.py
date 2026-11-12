# /// script
# requires-python = ">=3.12"
# dependencies = ["bleak>=3.0", "dbus-fast>=2.0"]
# ///
import argparse
import asyncio
import json
import os
import struct
import sys
import time
from pathlib import Path

from bleak import BleakClient, BleakScanner
from dbus_fast import BusType, Message
from dbus_fast.aio import MessageBus
from dbus_fast.service import ServiceInterface, method

SERVICE = "5a391e60-e572-4f44-920b-4a82eb105232"
RX = "5a391e61-e572-4f44-920b-4a82eb105232"
TX = "5a391e62-e572-4f44-920b-4a82eb105232"
TYPE_CHUNK, TYPE_ACK, TYPE_CHECKIN = 1, 2, 3
CHUNK_HEADER = struct.Struct("<6sIIBqIIIq")
AGENT_PATH = "/resticky/agent"


class Agent(ServiceInterface):
    def __init__(self):
        super().__init__("org.bluez.Agent1")

    @method()
    async def RequestPasskey(self, device: "o") -> "u":
        loop = asyncio.get_running_loop()
        print("passkey shown on the Sticky: ", end="", flush=True)
        line = await loop.run_in_executor(None, sys.stdin.readline)
        return int(line.strip().replace(" ", ""))

    @method()
    def DisplayPasskey(self, device: "o", passkey: "u", entered: "q"):
        print(f"passkey {passkey:06d}")

    @method()
    def RequestConfirmation(self, device: "o", passkey: "u"):
        print(f"confirm passkey {passkey:06d}")

    @method()
    def RequestAuthorization(self, device: "o"):
        pass

    @method()
    def AuthorizeService(self, device: "o", uuid: "s"):
        pass

    @method()
    def Cancel(self):
        print("pairing cancelled")

    @method()
    def Release(self):
        pass


async def register_agent():
    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    bus.export(AGENT_PATH, Agent())
    for member, sig, body in (("RegisterAgent", "os", [AGENT_PATH, "KeyboardOnly"]), ("RequestDefaultAgent", "o", [AGENT_PATH])):
        await bus.call(Message(destination="org.bluez", path="/org/bluez", interface="org.bluez.AgentManager1",
                               member=member, signature=sig, body=body))
    return bus


def frame(kind, payload):
    return struct.pack("<IB", len(payload) + 1, kind) + payload


class Hub:
    def __init__(self, out):
        self.out = out
        self.buf = bytearray()
        self.started = None
        self.frames = asyncio.Queue()
        self.total_bytes = 0
        self.total_s = 0.0

    def feed(self, _, data):
        if not self.buf:
            self.started = time.monotonic()
        self.buf += data
        while len(self.buf) >= 4:
            n = struct.unpack_from("<I", self.buf)[0]
            if len(self.buf) < 4 + n:
                return
            body = bytes(self.buf[4:4 + n])
            del self.buf[:4 + n]
            self.frames.put_nowait((body[0], body[1:], self.started, time.monotonic()))
            self.started = time.monotonic() if self.buf else None

    def store(self, device, session, seq, audio):
        folder = self.out / device / session
        folder.mkdir(parents=True, exist_ok=True)
        tmp = folder / f"{seq}.opus.tmp"
        with open(tmp, "wb") as f:
            f.write(audio)
            f.flush()
            os.fsync(f.fileno())
        tmp.rename(folder / f"{seq}.opus")

    async def serve(self, client):
        while client.is_connected:
            try:
                kind, body, t0, t1 = await asyncio.wait_for(self.frames.get(), 1)
            except TimeoutError:
                continue
            if kind == TYPE_CHECKIN:
                print("checkin", json.loads(body))
                continue
            if kind != TYPE_CHUNK:
                print(f"frame type {kind} ignored ({len(body)} bytes)")
                continue
            mac, session, seq, final, start_ms, duration_ms, length, boot_id, boot_unix_ms = CHUNK_HEADER.unpack_from(body)
            audio = body[CHUNK_HEADER.size:]
            if len(audio) != length:
                print(f"chunk {session:08x}/{seq}: {len(audio)} bytes, header says {length}; no ack")
                continue
            self.store(mac.hex(), f"{session:08x}", seq, audio)
            await client.write_gatt_char(RX, frame(TYPE_ACK, struct.pack("<II", session, seq)), response=False)
            took = t1 - t0
            self.total_bytes += len(body) + 5
            self.total_s += took
            print(f"chunk {session:08x}/{seq} final={final} start_ms={start_ms} boot={boot_id:08x} boot_unix_ms={boot_unix_ms} {duration_ms} ms audio, {len(audio)} bytes in {took * 1000:.0f} ms "
                  f"({len(body) / 1024 / took:.1f} KB/s), acked {(time.monotonic() - t1) * 1000:.0f} ms after the last fragment; "
                  f"average {self.total_bytes / 1024 / self.total_s:.1f} KB/s")


async def main():
    p = argparse.ArgumentParser(description="Minimal BLE hub for the Sticky: receives chunks, stores them, sends acks.")
    p.add_argument("--out", type=Path, default=Path("ble-chunks"), help="folder for <device>/<session>/<seq>.opus")
    args = p.parse_args()
    await register_agent()
    hub = Hub(args.out)
    while True:
        print("scanning for the Sticky")
        dev = await BleakScanner.find_device_by_filter(lambda d, ad: SERVICE in ad.service_uuids, timeout=30)
        if not dev:
            continue
        print(f"found {dev.address} ({dev.name}), connecting")
        try:
            async with BleakClient(dev, pair=True, timeout=90) as client:
                print(f"connected, mtu {client.mtu_size}")
                hub.buf.clear()
                await client.start_notify(TX, hub.feed)
                await hub.serve(client)
        except Exception as e:
            print(f"link error: {e!r}")
        print("disconnected")
        await asyncio.sleep(2)


if __name__ == "__main__":
    asyncio.run(main())
