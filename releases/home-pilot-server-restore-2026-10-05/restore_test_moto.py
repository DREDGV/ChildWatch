#!/usr/bin/env python3
"""Server-owner action for ONE spare Moto; default is read-only inspection."""
import argparse
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

SPARE_DEVICE_ID = "child-6bc359d8"


def inspect(conn):
    rows = conn.execute(
        "SELECT device_id, device_name, is_active FROM devices WHERE device_id = ?",
        (SPARE_DEVICE_ID,),
    ).fetchall()
    if len(rows) != 1:
        raise RuntimeError("Expected exactly one known spare device; nothing changed")
    active_bindings = conn.execute(
        "SELECT COUNT(*) FROM family_devices WHERE device_id = ? AND is_active = 1",
        (SPARE_DEVICE_ID,),
    ).fetchone()[0]
    active_links = conn.execute(
        "SELECT COUNT(*) FROM device_links WHERE is_active = 1 AND "
        "(parent_device_id = ? OR child_device_id = ?)",
        (SPARE_DEVICE_ID, SPARE_DEVICE_ID),
    ).fetchone()[0]
    return {
        "deviceId": rows[0][0], "deviceName": rows[0][1], "isActive": rows[0][2],
        "activeFamilyBindings": active_bindings, "activeLinks": active_links,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path, help="Exact existing production childwatch.db")
    parser.add_argument("--apply", action="store_true", help="Owner explicitly restores this spare's registration access")
    args = parser.parse_args()
    db = args.database.resolve(strict=True)
    mode = "rw" if args.apply else "ro"
    conn = sqlite3.connect(db.as_uri() + "?mode=" + mode, uri=True, timeout=30)
    try:
        before = inspect(conn)
        print(json.dumps(before, ensure_ascii=False))
        if not args.apply:
            print("Inspection only: no database changes. --apply requires the server owner's decision.")
            return
        if before["isActive"] != 0:
            raise RuntimeError("Spare is not revoked; nothing to restore")
        if before["activeFamilyBindings"] or before["activeLinks"]:
            raise RuntimeError("Spare has an active binding/link; inspect ownership first, nothing changed")
        if conn.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            raise RuntimeError("Database check failed; nothing changed")
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%f")
        backup = db.with_name(db.name + ".before-test-moto-restore-" + stamp)
        backup_conn = sqlite3.connect(str(backup))
        try:
            conn.backup(backup_conn)
        finally:
            backup_conn.close()
        conn.execute("BEGIN IMMEDIATE")
        try:
            if inspect(conn) != before:
                raise RuntimeError("Spare state changed after backup; nothing changed")
            counts_before = {
                table: conn.execute("SELECT COUNT(*) FROM " + table).fetchone()[0]
                for table in ("family_members", "family_devices", "device_links", "chat_messages_v2")
            }
            changed = conn.execute(
                "UPDATE devices SET is_active = 1 WHERE device_id = ? AND is_active = 0",
                (SPARE_DEVICE_ID,),
            ).rowcount
            if changed != 1:
                raise RuntimeError("Expected exactly one updated row")
            after = inspect(conn)
            if after != {**before, "isActive": 1}:
                raise RuntimeError("Unexpected spare state after update")
            counts_after = {
                table: conn.execute("SELECT COUNT(*) FROM " + table).fetchone()[0]
                for table in counts_before
            }
            if counts_before != counts_after:
                raise RuntimeError("Family/chat row counts changed")
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        print(json.dumps({"restoredDevice": SPARE_DEVICE_ID, "changedRows": changed,
                          "backup": str(backup), "familyAndChatCounts": counts_after}, ensure_ascii=False))
        print("Registration access restored. No old family binding or links restored; use a fresh guardian invitation.")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
