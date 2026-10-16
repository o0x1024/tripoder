"""Read the locked guest's vendor configuration without mounting or modifying it."""
import hashlib
import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True
from qcow_readonly import QcowReader
from vendor_readonly import VendorReader

PROBE = Path(__file__).resolve().parent.parent
image = QcowReader(PROBE / "build/media/vda.qcow2")

path = "/etc/init/hw/init.low_performance.rc"
data = VendorReader(image).find_file(path)
out = PROBE.parents[1] / "docs/evidence/android-source-vm-2026-10-13/software-profile"
(out / "image-init.low_performance.rc").write_bytes(data)
(out / "image-config-audit.json").write_text(json.dumps({
    "image": "locked vda.qcow2 opened read-only",
    "filesystem": "vendor ext4 via checksummed logical-partition metadata",
    "path": path,
    "bytes": len(data),
    "sha256": hashlib.sha256(data).hexdigest(),
}, indent=2) + "\n")
print(data.decode())
image.file.close()
