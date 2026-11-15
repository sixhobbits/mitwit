#!/usr/bin/env python3
"""Decode Withings WPP traffic from an Android btsnoop_hci.log.

Usage: withings_secret.py btsnoop_hci.log [--all]

Prints the association secret (klSecret) sent in CMD_ASSOCIATION_KEYS_SET /
UserSecret, and with --all every WPP frame with its objects.

Layers: btsnoop (H4) -> HCI ACL (reassembled) -> L2CAP CID 4 (ATT)
-> ATT write/notify values (reassembled into WPP frames per direction)
-> WPP frame: u8 0x01 | u16 cmd | u16 len | [u16 type | u16 len | data]*
"""
import struct
import sys

ATT_CID = 0x0004
ATT_WRITE_OPS = {0x12: "write_req", 0x52: "write_cmd"}
ATT_NOTIFY_OPS = {0x1B: "notify", 0x1D: "indicate"}

CMD_NAMES = {275: "SETUP_OK", 308: "ASSOCIATION_KEYS_SET", 1282: "TRACKER_USER_SET",
             1281: "SET_TIME", 1283: "USER_GET", 1284: "BATTERY", 277: "SYNC_OK"}
OBJ_NAMES = {257: "ProbeReply", 290: "ProbeChallenge", 291: "ProbeChallengeResponse",
             298: "AppProbe", 309: "AccountKey", 310: "AdvKey", 1299: "UserSecret",
             2459: "Hmac", 284: "AssocToken", 2468: "CertDer"}


def btsnoop_records(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"btsnoop\0":
        sys.exit("not a btsnoop file")
    _version, datalink = struct.unpack(">II", data[8:16])
    if datalink != 1002:
        sys.exit(f"unsupported datalink {datalink} (expected 1002, H4)")
    off = 16
    while off + 24 <= len(data):
        _orig, incl, flags, _drops, _ts = struct.unpack(">IIIIq", data[off:off + 24])
        off += 24
        pkt = data[off:off + incl]
        off += incl
        received = bool(flags & 1)  # bit0: 0 = sent by phone, 1 = received
        yield received, pkt


def att_pdus(path):
    """Yield (received, att_pdu) with ACL/L2CAP reassembly."""
    pending = {}  # (handle, direction) -> [expected_len, cid, bytearray]
    for received, pkt in btsnoop_records(path):
        if not pkt or pkt[0] != 0x02:  # H4 ACL
            continue
        hdr, acl_len = struct.unpack("<HH", pkt[1:5])
        handle, pb = hdr & 0x0FFF, (hdr >> 12) & 0x3
        body = pkt[5:5 + acl_len]
        key = (handle, received)
        if pb in (0x0, 0x2):  # first fragment
            l2_len, cid = struct.unpack("<HH", body[:4])
            pending[key] = [l2_len, cid, bytearray(body[4:])]
        elif key in pending:
            pending[key][2] += body
        else:
            continue
        l2_len, cid, buf = pending[key]
        if len(buf) >= l2_len:
            del pending[key]
            if cid == ATT_CID:
                yield received, bytes(buf[:l2_len])


def wpp_frames(path):
    """Yield (direction, att_handle, frame_bytes)."""
    bufs = {}
    for received, pdu in att_pdus(path):
        op = pdu[0]
        if received and op in ATT_NOTIFY_OPS:
            direction = "watch->phone"
        elif not received and op in ATT_WRITE_OPS:
            direction = "phone->watch"
        else:
            continue
        att_handle = struct.unpack("<H", pdu[1:3])[0]
        value = pdu[3:]
        key = (direction, att_handle)
        buf = bufs.get(key, bytearray())
        if not buf and (not value or value[0] != 0x01):
            continue  # not a WPP frame start
        buf += value
        while len(buf) >= 5:
            need = 5 + struct.unpack(">H", buf[3:5])[0]
            if len(buf) < need:
                break
            yield direction, att_handle, bytes(buf[:need])
            buf = buf[need:]
            if buf and buf[0] != 0x01:
                buf = bytearray()
        bufs[key] = buf


def parse_objects(payload):
    objs, off = [], 0
    while off + 4 <= len(payload):
        typ, ln = struct.unpack(">HH", payload[off:off + 4])
        objs.append((typ, payload[off + 4:off + 4 + ln]))
        off += 4 + ln
    return objs


def read_str(b, off):
    n = b[off]
    return b[off + 1:off + 1 + n].decode("utf-8", "replace"), off + 1 + n


def main():
    path = sys.argv[1]
    show_all = "--all" in sys.argv
    found = False
    for direction, att_handle, frame in wpp_frames(path):
        raw_cmd = struct.unpack(">H", frame[1:3])[0]
        channel, cmd = raw_cmd >> 14, raw_cmd & 0x3FFF
        objs = parse_objects(frame[5:])
        if "--requests" in sys.argv:
            if direction == "phone->watch":
                print(f"cmd={cmd} ch={channel} {frame.hex()}")
            continue
        if show_all:
            print(f"{direction} h=0x{att_handle:04x} ch={channel} cmd={cmd} "
                  f"{CMD_NAMES.get(cmd, '')}")
            for typ, data in objs:
                print(f"    {typ} {OBJ_NAMES.get(typ, '')} {data.hex()}")
        for typ, data in objs:
            if typ == 309:  # AccountKey {u32 id, str secret}
                acc_id = struct.unpack(">I", data[:4])[0]
                secret, _ = read_str(data, 4)
                print(f"AccountKey: account_id={acc_id} klSecret={secret!r}")
                found = True
            elif typ == 1299:  # UserSecret {str}
                secret, _ = read_str(data, 0)
                print(f"UserSecret: klSecret={secret!r}")
                found = True
            elif typ == 310:  # AdvKey {str}
                print(f"AdvKey: {read_str(data, 0)[0]!r}")
            elif typ == 2468:
                print("CertDer seen: TLS-over-BLE is in use")
    if not found:
        print("No secret found (setup not captured, or traffic is inside TLS).")


if __name__ == "__main__":
    main()
