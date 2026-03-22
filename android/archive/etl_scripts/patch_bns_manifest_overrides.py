import json
from pathlib import Path
from datetime import datetime

MANIFEST_PATH = Path("bns_manifest.jsonl")

# Deterministic legal overrides for critical boundaries.
SECTION_TIER_OVERRIDES = {
    1: 0,    # Short title/commencement is non-penal
    66: 3,   # Murder is heinous
    70: 3,   # Gang rape is heinous
    358: 0,  # Repeal and savings is non-penal
}


def main() -> None:
    if not MANIFEST_PATH.exists():
        raise FileNotFoundError(f"Missing {MANIFEST_PATH}")

    backup_path = MANIFEST_PATH.with_name(
        f"{MANIFEST_PATH.stem}.backup.{datetime.now().strftime('%Y%m%d_%H%M%S')}{MANIFEST_PATH.suffix}"
    )
    backup_path.write_bytes(MANIFEST_PATH.read_bytes())

    lines = MANIFEST_PATH.read_text(encoding="utf-8").splitlines()
    patched_lines = []
    changed = []

    for idx, line in enumerate(lines, start=1):
        if not line.strip():
            patched_lines.append(line)
            continue

        rec = json.loads(line)
        section_number_raw = rec.get("section_number")
        try:
            sec_no = int(str(section_number_raw))
        except Exception:
            patched_lines.append(line)
            continue

        if sec_no in SECTION_TIER_OVERRIDES:
            old = rec.get("severity_tier")
            new = SECTION_TIER_OVERRIDES[sec_no]
            if old != new:
                rec["severity_tier"] = new
                changed.append((sec_no, old, new, idx))

        patched_lines.append(json.dumps(rec, ensure_ascii=True))

    MANIFEST_PATH.write_text("\n".join(patched_lines) + "\n", encoding="utf-8")

    print("BACKUP", backup_path.name)
    print("CHANGED_COUNT", len(changed))
    for sec_no, old, new, line_no in sorted(changed, key=lambda x: x[0]):
        print(f"SECTION {sec_no}: {old} -> {new} (line {line_no})")


if __name__ == "__main__":
    main()

