import json
import os

output_file = "bns_manifest.jsonl"

with open(output_file, 'r') as f:
    lines = f.readlines()

file_size = os.path.getsize(output_file)

print(f'Total records: {len(lines)}')
print(f'File size: {file_size:,} bytes')

# Count by severity tier
tier_counts = {0: 0, 1: 0, 2: 0, 3: 0}
for line in lines:
    rec = json.loads(line)
    tier = rec['severity_tier']
    tier_counts[tier] += 1

print(f'\nSeverity Distribution:')
print(f'  Tier 0 (Non-penal): {tier_counts[0]} sections')
print(f'  Tier 1 (Minor): {tier_counts[1]} sections')
print(f'  Tier 2 (Moderate): {tier_counts[2]} sections')
print(f'  Tier 3 (Severe/Heinous): {tier_counts[3]} sections')

print(f'\nFirst 3 records:')
for i in range(3):
    rec = json.loads(lines[i])
    print(f'  SEC {rec["section_number"]:>3} | {rec["title"][:45]:<45} | Tier {rec["severity_tier"]}')

print(f'\nSample from middle (Section 150-155):')
for i in range(149, 155):
    rec = json.loads(lines[i])
    print(f'  SEC {rec["section_number"]:>3} | {rec["title"][:45]:<45} | Tier {rec["severity_tier"]}')

print(f'\nLast 3 records:')
for i in range(len(lines)-3, len(lines)):
    rec = json.loads(lines[i])
    print(f'  SEC {rec["section_number"]:>3} | {rec["title"][:45]:<45} | Tier {rec["severity_tier"]}')

# Show punishments for Tier 3 offenses
print(f'\nTier 3 (Severe) Offenses:')
tier3_count = 0
for i, line in enumerate(lines):
    rec = json.loads(line)
    if rec['severity_tier'] == 3:
        tier3_count += 1
        print(f'  [{rec["section_number"]}] {rec["title"][:50]}')
        if rec['punishment_summary'] and rec['punishment_summary'] != 'Refer to official source for penalties.':
            print(f'      → {rec["punishment_summary"][:80]}')
        if tier3_count >= 10:
            print(f'  ... and {len([1 for l in lines if json.loads(l)["severity_tier"] == 3]) - 10} more')
            break

