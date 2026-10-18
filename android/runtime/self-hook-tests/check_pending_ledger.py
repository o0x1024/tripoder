#!/usr/bin/env python3
"""Execute production schema constraints in SQLite; no Android or system token claims."""
import hashlib
import json
from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "android/runtime/src/dev/tripoder/runtime/PendingIntentLedger.java"
STORE = ROOT / "android/runtime/src/dev/tripoder/runtime/RuntimeStore.java"


def main():
    source = SOURCE.read_text()
    create = source.split("static void create(SQLiteDatabase db){", 1)[1].split("    static final class", 1)[0]
    statements = [json.loads('"' + value + '"') for value in re.findall(r'db\.execSQL\("(.*?)"\)', create)]
    assert len(statements) == 5
    db = sqlite3.connect(":memory:")
    for sql in statements:
        db.execute(sql)
    db.execute("CREATE TABLE installs(space TEXT,package TEXT,generation TEXT,dataId TEXT,state TEXT)")
    db.execute("INSERT INTO installs VALUES('space','owned.pkg','install1','data1','COMMITTED')")
    checks = 0

    def check(condition):
        nonlocal checks
        checks += 1
        assert condition, checks

    def rejected(sql, args):
        try:
            db.execute(sql, args)
        except sqlite3.IntegrityError:
            check(True)
        else:
            check(False)

    columns = "version,identityKey,space,package,installGeneration,dataId,processName,processStub,receiver,requestCode,flags,payload,state,fenced,currentVersion,routeAction"
    insert = "INSERT INTO pending_intents(" + columns + ") VALUES(" + ",".join("?" * 16) + ")"
    row = ("v1", "key", "space", "owned.pkg", "install1", "data1", "owned.pkg:receiver", "slot1", "Receiver", 7, 0, b"logical", "ACTIVE", 0, 1, "route.v1")
    db.execute(insert, row)
    rejected(insert, ("v2", *row[1:]))
    db.execute("UPDATE pending_intents SET fenced=1,currentVersion=0 WHERE version='v1'")
    db.execute(insert, ("v2", *row[1:-1], "route.v2"))
    check(db.execute("SELECT COUNT(*) FROM pending_intents").fetchone()[0] == 2)
    check(db.execute("SELECT version FROM pending_intents WHERE identityKey='key' AND currentVersion=1").fetchone() == ("v2",))
    check(db.execute("SELECT state,payload FROM pending_intents WHERE version='v1'").fetchone() == ("ACTIVE", b"logical"))
    mutation = "INSERT INTO pending_mutations(operation,installGeneration,version,kind) VALUES(?,?,?,?)"
    db.execute(mutation, ("op1", "install1", "v2", "GET"))
    rejected(mutation, ("op2", "install1", "v2", "CANCEL"))
    db.execute(mutation, ("other", "install2", "other", "GET"))
    check(db.execute("SELECT COUNT(*) FROM pending_mutations WHERE closed=0").fetchone()[0] == 2)
    # Losing the process does not remove the owner: UNKNOWN work cannot be replayed.
    rejected(mutation, ("recovered", "install1", "v2", "GET"))
    db.execute("UPDATE pending_mutations SET closed=1 WHERE operation='op1'")
    db.execute(mutation, ("op2", "install1", "v2", "CANCEL"))
    check(db.execute("SELECT COUNT(*) FROM pending_mutations WHERE installGeneration='install1'").fetchone()[0] == 2)
    # Exact route metadata migration keeps old rows and unknown outcome states.
    old = sqlite3.connect(":memory:")
    for sql in statements:
        old.execute(sql.replace(",routeAction TEXT NOT NULL", ""))
    old.execute("INSERT INTO pending_intents(" + columns.rsplit(",", 1)[0] + ") VALUES(" + ",".join("?" * 15) + ")", (*row[:12], "UNKNOWN", *row[13:15]))
    before = old.execute("SELECT * FROM pending_intents").fetchone()
    migration = re.search(r'if\(oldVersion==29\)db\.execSQL\("([^"]+)"\)', STORE.read_text()).group(1)
    old.execute(migration)
    after = old.execute("SELECT * FROM pending_intents").fetchone()
    check(after[:-1] == before)
    check(after[-1] == "dev.tripoder.runtime.PENDING_BROADCAST")
    old.execute(mutation, ("unknown-original", "install1", "v1", "GET"))
    gateway = list(row)
    gateway[0], gateway[1], gateway[7], gateway[-1] = "gateway", "new-key", "", "route.gateway"
    old.execute(insert, gateway)
    retirement = re.search(r'if\(oldVersion<31\)db\.execSQL\("([^"]+)"\)', STORE.read_text()).group(1)
    old.execute(retirement)
    check(old.execute("SELECT state,fenced,currentVersion,processStub FROM pending_intents WHERE version='v1'").fetchone() == ("UNKNOWN", 1, 0, "slot1"))
    check(old.execute("SELECT state,fenced,currentVersion,processStub FROM pending_intents WHERE version='gateway'").fetchone() == ("ACTIVE", 0, 1, ""))
    check(old.execute("SELECT closed FROM pending_mutations WHERE operation='unknown-original'").fetchone() == (0,))
    check(old.execute("SELECT COUNT(*) FROM pending_intents").fetchone()[0] == 2)
    print(json.dumps({"passed": True, "checks": checks, "sourceSha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(), "storeSha256": hashlib.sha256(STORE.read_bytes()).hexdigest(), "systemTokensExecuted": False}))


if __name__ == "__main__":
    main()
