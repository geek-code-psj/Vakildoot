import json
from collections import Counter

path = "bns_manifest.jsonl"
records = []
with open(path, "r", encoding="utf-8") as f:
    for i, line in enumerate(f, 1):
        line = line.strip()
        if not line:
            continue
        obj = json.loads(line)
        obj["_line"] = i
        records.append(obj)

by_sec = {}
for r in records:
    by_sec.setdefault(str(r.get("section_number")), []).append(r)

sec1 = by_sec.get("1", [None])[0]
sec66 = by_sec.get("66", [None])[0]
sec70 = by_sec.get("70", [None])[0]
sec358 = by_sec.get("358", [None])[0]

tier3 = [r for r in records if r.get("severity_tier") == 3]

ge = []
for n in range(14, 45):
    item = by_sec.get(str(n), [None])[0]
    ge.append((n, None if item is None else item.get("severity_tier")))

nums = []
invalid = []
for r in records:
    try:
        nums.append(int(str(r.get("section_number"))))
    except Exception:
        invalid.append(r.get("section_number"))

counter = Counter(nums)
dups = sorted([n for n, c in counter.items() if c > 1])
missing = sorted(set(range(1, 359)) - set(nums))
extra = sorted(set(nums) - set(range(1, 359)))

print("RECORD_COUNT", len(records))
print("SECTION_66_TIER", None if sec66 is None else sec66.get("severity_tier"))
print("SECTION_70_TIER", None if sec70 is None else sec70.get("severity_tier"))
print("TIER3_TOTAL", len(tier3))
print("SECTION_1_TIER", None if sec1 is None else sec1.get("severity_tier"))
print("SECTION_358_TIER", None if sec358 is None else sec358.get("severity_tier"))
print("GE_14_44_NONZERO", [(n, t) for (n, t) in ge if t != 0])
print("GE_14_44_MISSING", [n for (n, t) in ge if t is None])
print("DUPLICATES", dups)
print("MISSING", missing)
print("EXTRA", extra)
print("INVALID_SECTION_NUMBERS", invalid)

