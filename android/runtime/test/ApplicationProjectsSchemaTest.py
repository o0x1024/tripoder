"""Exercise the production schema SQL against SQLite, including a v34 upgrade."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

SOURCE = (Path(__file__).parents[1] / "src/dev/tripoder/runtime/ApplicationProjects.java").read_text()


def statements(method):
    body = SOURCE.split(f"static void {method}(SQLiteDatabase db){{", 1)[1].split("\n    }", 1)[0]
    return [json.loads('"' + raw + '"') for raw in re.findall(r'db\.execSQL\("((?:[^"\\]|\\.)*)"\)', body)]


def create(db):
    for sql in statements("create") + statements("createTargets"):
        db.execute(sql)


class ApplicationProjectsSchemaTest(unittest.TestCase):
    def test_one_owner_multiple_spaces_and_ownership_survives_target_removal(self):
        db = sqlite3.connect(":memory:")
        create(db)
        db.execute("INSERT INTO application_projects VALUES ('owned.app','canonical','a')")
        with self.assertRaises(sqlite3.IntegrityError):
            db.execute("INSERT INTO application_projects VALUES ('owned.app','duplicate','b')")
        for space in ("a", "b"):
            db.execute("INSERT INTO project_refs VALUES (?,?,?,?,?,?,?,?,?,?)", ("canonical", space, "owned.app", "install-"+space, "", "", "COMMITTED", "bind-"+space, "DIRECT", "thin"))
        self.assertEqual(db.execute("SELECT COUNT(DISTINCT id),COUNT(*) FROM project_refs").fetchone(), (1, 2))
        db.execute("DELETE FROM project_refs WHERE space='a'")
        self.assertEqual(db.execute("SELECT id FROM application_projects WHERE package='owned.app'").fetchone(), ("canonical",))
        with self.assertRaises(sqlite3.IntegrityError):
            db.execute("INSERT INTO project_refs VALUES ('other','b','owned.app','g','','','COMMITTED','o','DIRECT','thin')")

    def test_upgrade_preserves_archives_and_collapses_duplicate_projects(self):
        db = sqlite3.connect(":memory:")
        db.execute("CREATE TABLE project_refs(id TEXT PRIMARY KEY,space TEXT,package TEXT,generation TEXT,sourceSha TEXT,snapshot TEXT,state TEXT,operation TEXT,kind TEXT,mode TEXT)")
        rows = [("one", "a"), ("two", "a"), ("three", "b")]
        for project, space in rows:
            db.execute("INSERT INTO project_refs VALUES (?,?,?,?,?,?,?,?,?,?)", (project, space, "owned.app", "g-"+space, "sha-"+project, "snapshot-"+project, "COMMITTED", "op-"+project, "DIRECT", "thin"))
        db.execute("INSERT INTO project_refs VALUES ('legacy','c','legacy.app','g','sha','snapshot','COMMITTED','op','MIGRATED','thick')")
        db.execute("INSERT INTO project_refs VALUES ('legacy-own','c','owned.app','g','sha','snapshot','COMMITTED','op','MIGRATED','thick')")
        # Execute the exact production sequence, including the helper calls.
        for sql in statements("create"):
            db.execute(sql)
        sqls = statements("migrate")
        for sql in sqls[:-1]:
            db.execute(sql)
        for sql in statements("createTargets"):
            db.execute(sql)
        db.execute(sqls[-1])
        canonical = db.execute("SELECT id FROM application_projects WHERE package='owned.app'").fetchone()[0]
        self.assertEqual(db.execute("SELECT COUNT(*) FROM application_projects").fetchone()[0], 1)
        self.assertEqual(db.execute("SELECT COUNT(*) FROM project_refs_archive_v34").fetchone()[0], 5)
        self.assertEqual(db.execute("SELECT id,space,state FROM project_refs WHERE package='owned.app' AND kind='DIRECT' ORDER BY space").fetchall(), [(canonical, "a", "STALE"), (canonical, "b", "STALE")])
        self.assertEqual(db.execute("SELECT COUNT(*) FROM project_aliases WHERE project=?", (canonical,)).fetchone()[0], 2)
        self.assertEqual(db.execute("SELECT snapshot FROM project_refs_archive_v34 WHERE id='two'").fetchone()[0], "snapshot-two")
        self.assertEqual(db.execute("SELECT id FROM project_refs WHERE kind='MIGRATED' AND package='owned.app'").fetchone()[0], "legacy-own")


if __name__ == "__main__":
    unittest.main()
