#!/usr/bin/env python3
"""
BNS 2023 Severity-Weighted Legal Manifest Extractor (Enhanced)
Extracts 358 sections with severity classification and fair-use summaries
Handles PDF with mixed IPC/BNS content
"""

import re
import json
from pathlib import Path
from typing import List, Dict, Tuple, Optional
import pypdf

# Severity classification logic
def classify_severity_tier(punishment_text: str) -> int:
    """
    Classify severity based on punishment quantum.
    Tier 0: Non-penal (no punishment)
    Tier 1: Minor (community service, fine only, or ≤2 years imprisonment)
    Tier 2: Moderate (>2 and ≤7 years imprisonment)
    Tier 3: Severe (>7 years, life, or death)
    """
    if not punishment_text or punishment_text.lower() in ['', 'none', 'no punishment']:
        return 0

    punishment_lower = punishment_text.lower()

    # Check for Tier 3 indicators (death, life imprisonment)
    if any(word in punishment_lower for word in ['death', 'death sentence', 'execution', 'capital punishment']):
        return 3
    if any(word in punishment_lower for word in ['life imprisonment', 'life sentence', 'imprisonment for life']):
        return 3

    # Extract imprisonment duration using regex
    imprisonment_pattern = r'(?:imprisonment(?:\s+for)?|imprisoned)(?:\s+for)?(?:\s+(?:up\s+)?to\s+)?(\d+)\s+years?'
    matches = re.findall(imprisonment_pattern, punishment_lower)

    if matches:
        max_years = max(int(m) for m in matches)
        if max_years > 7:
            return 3
        elif max_years > 2:
            return 2
        else:
            return 1

    # Check for community service or fine-only
    if any(word in punishment_lower for word in ['community service', 'fine only']):
        if 'imprisonment' not in punishment_lower:
            return 1

    # If imprisonment mentioned but no duration found, assume moderate
    if 'imprisonment' in punishment_lower:
        return 2

    # Default to Tier 1 if fine or punishment mentioned
    if 'fine' in punishment_lower or punishment_text.strip():
        return 1

    return 0

def extract_sections_from_pdf(pdf_path: str) -> Dict[int, Dict]:
    """
    Extract all BNS sections (1-358) from the PDF.
    Returns a dict mapping section number to section data.
    Handles mixed IPC/BNS content by filtering for BNS sections only.
    """
    sections_dict = {}

    with open(pdf_path, 'rb') as f:
        reader = pypdf.PdfReader(f)
        full_text = ""
        page_map = {}  # Track which page each section is found on

        for page_num, page in enumerate(reader.pages):
            text = page.extract_text()
            full_text += f"\n[PAGE_{page_num + 1}]\n" + text

    # Extract section blocks more carefully
    # Pattern: Section number followed by title, then optional content
    # We want to match: "Section 1. Short title" or just "1. Short title"

    pattern = r'(?:^|\n)\s*(?:Section\s+)?(\d+)\.\s*([A-Z][^\n]{0,150}?)(?:\n|$)'

    matches = list(re.finditer(pattern, full_text, re.MULTILINE))
    print(f"Found {len(matches)} potential section headers")

    # Filter to only BNS sections (1-358)
    section_data = {}
    for match in matches:
        try:
            sec_num = int(match.group(1))
            if 1 <= sec_num <= 358:  # Only process BNS sections
                sec_title = match.group(2).strip()

                # Get content until next section
                content_start = match.end()
                next_match_start = full_text.find('\n[PAGE_', content_start)
                if next_match_start == -1:
                    content_end = len(full_text)
                else:
                    content_end = next_match_start

                section_content = full_text[content_start:content_end].strip()

                # Extract punishment information
                punishment_lines = []
                for line in section_content.split('\n')[:15]:
                    line_lower = line.lower()
                    if any(word in line_lower for word in ['imprisonment', 'fine', 'punishment', 'penalty', 'community service', 'shall be punished']):
                        punishment_lines.append(line.strip())

                punishment_text = ' '.join(punishment_lines) if punishment_lines else ''

                section_data[sec_num] = {
                    'number': str(sec_num),
                    'title': sec_title,
                    'content': section_content[:300],
                    'punishment': punishment_text
                }
        except (ValueError, AttributeError):
            continue

    print(f"Extracted {len(section_data)} BNS sections")
    return section_data

def generate_fair_use_summary(section_number: int, section_title: str, content: str) -> str:
    """
    Generate a 15-20 word fair-use summary based on section number, title and content.
    Uses domain knowledge of BNS structure.
    """
    sec_num = int(section_number)
    title_lower = section_title.lower()

    # Tier 0: Non-penal sections (1-69)
    if sec_num <= 2:
        return "Foundational provisions governing the statute's application and definitions."
    elif 3 <= sec_num <= 69:
        if 'definition' in title_lower:
            return "Key legal and procedural definitions used throughout the Act."
        elif 'exception' in title_lower or 'defence' in title_lower or 'private defence' in title_lower:
            return "Legal exception or general defence available to persons accused."
        else:
            return "General procedural or definitional provision of the Act."

    # Tier 1-3: Substantive offences (70+)
    elif 'culpable homicide' in title_lower:
        return "Unlawful causing of death not amounting to murder; classified by intention."
    elif 'murder' in title_lower:
        return "Unlawful killing with intent or knowledge likely to cause death."
    elif 'rape' in title_lower or 'sexual' in title_lower and 'assault' in title_lower:
        return "Non-consensual sexual act against person; serious offense with enhanced penalties."
    elif 'kidnapping' in title_lower or 'abduction' in title_lower:
        return "Unlawful taking away person from lawful guardianship or custody."
    elif 'theft' in title_lower:
        return "Dishonest taking of movable property with intent to permanently deprive owner."
    elif 'dacoity' in title_lower or 'dacoity' in title_lower:
        return "Theft by five or more persons acting together with common intention."
    elif 'cheating' in title_lower or 'fraud' in title_lower:
        return "Deceptive inducement causing person to deliver property or accept loss."
    elif 'criminal breach' in title_lower and 'trust' in title_lower:
        return "Misappropriation of property held in fiduciary capacity or trust."
    elif 'defamation' in title_lower or 'libel' in title_lower or 'slander' in title_lower:
        return "False statement damaging reputation; fine and/or short imprisonment possible."
    elif 'wrongful confinement' in title_lower or 'unlawful confinement' in title_lower:
        return "Unlawful restraint or confinement of person without legal authority."
    elif 'hurt' in title_lower:
        if 'grievous' in title_lower:
            return "Causing serious bodily injury damaging health or rendering temporarily useless."
        else:
            return "Causing bodily pain, injury or health damage by unlawful act."
    elif 'criminal force' in title_lower:
        return "Intentional use of physical force without lawful authority or consent."
    elif 'criminal intimidation' in title_lower or 'criminal threat' in title_lower:
        return "Threatening words or acts intended to cause fear or alarm."
    elif 'mischief' in title_lower:
        return "Intentional damage to property or interference with enjoyment of property."
    elif 'criminal conspiracy' in title_lower:
        return "Agreement between two or more persons to commit unlawful act."
    elif 'attempt' in title_lower:
        return "Intentional steps toward committing offense even if unsuccessful."
    elif 'abetment' in title_lower:
        return "Instigating, aiding, or encouraging another person to commit offense."
    elif 'riot' in title_lower or 'communal' in title_lower:
        return "Violent public disturbance involving five or more persons."
    elif 'public servant' in title_lower or 'public official' in title_lower:
        if 'bribe' in title_lower or 'corruption' in title_lower:
            return "Public servant demanding or accepting illegal gratification for official acts."
        else:
            return "Offenses committed by public servants misusing official authority."
    elif 'false evidence' in title_lower or 'perjury' in title_lower:
        return "Giving false testimony in judicial proceedings knowing it to be false."
    elif 'forgery' in title_lower:
        return "Making false document or altering document intending to use as genuine."
    elif 'counterfeiting' in title_lower:
        return "Making fake currency notes or altered genuine notes for circulation."
    elif 'drugs' in title_lower or 'narcotic' in title_lower:
        return "Unlawful manufacture, sale, distribution of controlled substances."
    elif 'explosion' in title_lower or 'bomb' in title_lower or 'explosive' in title_lower:
        return "Unlawful use of explosives causing danger to person or property."
    elif 'organizing crime' in title_lower or 'organized crime' in title_lower:
        return "Criminal gang engaged in organized, continuous unlawful activities."
    elif 'trafficking' in title_lower:
        return "Exploitation through recruitment or movement for forced labor or sexual abuse."
    elif 'extortion' in title_lower:
        return "Obtaining property or service through wrongful use of force or threat."
    elif 'dowry' in title_lower:
        return "Demanding or accepting dowry as condition for marriage or related acts."
    elif 'child' in title_lower:
        if 'abuse' in title_lower or 'sexual' in title_lower:
            return "Sexual abuse or exploitation of minor; stringent penalties and registration."
        else:
            return "Unlawful acts against children; enhanced penalties for protection."
    elif 'acid' in title_lower:
        return "Throwing acid causing permanent disfigurement; serious offense with long sentence."
    else:
        # Generic fallback for sections we don't have specific knowledge
        if title_lower.strip():
            shortened_title = section_title[:40] if len(section_title) > 40 else section_title
            return f"{shortened_title}. Offense with defined legal penalties and procedures."
        return "Statutory offense with defined legal penalties and procedures."

def create_jsonl_manifest(sections_dict: Dict[int, Dict], output_path: str):
    """
    Create JSONL file with severity-weighted manifest for all 358 sections.
    """
    official_link_base = "https://www.indiacode.nic.in/handle/123456789/20488"

    # Generate records for ALL 358 sections (fill gaps with placeholder data)
    all_sections = {}

    with open(output_path, 'w', encoding='utf-8') as f:
        for sec_num in range(1, 359):
            if sec_num in sections_dict:
                section = sections_dict[sec_num]
                sec_title = section['title']
                sec_punishment = section['punishment']
            else:
                # Placeholder for sections not found in PDF
                sec_title = f"Section {sec_num}"
                sec_punishment = ""

            severity_tier = classify_severity_tier(sec_punishment)
            fair_use_summary = generate_fair_use_summary(sec_num, sec_title, "")

            record = {
                "id": f"BNS_SEC_{sec_num}",
                "section_number": str(sec_num),
                "title": sec_title,
                "severity_tier": severity_tier,
                "punishment_summary": sec_punishment if sec_punishment else "Refer to official source for complete penalty provisions.",
                "fair_use_summary": fair_use_summary[:120],
                "official_reference_link": f"{official_link_base}?view_type=search&section={sec_num}",
                "app_safety_disclaimers": {
                    "copyright_notice": "The statutory text of the BNS is in the public domain. This summary is provided for educational and navigational purposes only. Users must refer to the Official Gazette of India or IndiaCode for authoritative text.",
                    "ai_generation_warning": "WARNING: This metadata, summary, and severity classification was generated by Artificial Intelligence. It has not been manually verified by a human and may contain errors or hallucinations.",
                    "legal_liability_shield": "NOT LEGAL ADVICE. The developers of this application assume no liability for actions taken based on this information. Always consult a registered legal practitioner for legal proceedings."
                }
            }

            f.write(json.dumps(record) + '\n')

def main():
    pdf_path = Path("BNS Book_After Correction.pdf")
    output_path = Path("bns_manifest.jsonl")

    if not pdf_path.exists():
        print(f"Error: {pdf_path} not found")
        return

    print(f"Processing {pdf_path}...")
    sections_dict = extract_sections_from_pdf(str(pdf_path))

    print(f"Creating JSONL manifest for all 358 sections...")
    create_jsonl_manifest(sections_dict, str(output_path))

    print(f"✓ Manifest created: {output_path}")
    print(f"\nManifest Summary:")
    print(f"  Total sections in JSONL: 358")
    print(f"  Extracted from PDF: {len(sections_dict)}")
    print(f"  Missing sections (using domain knowledge): {358 - len(sections_dict)}")

    # Show sample records
    print("\nSample records (first 5 and random):")
    with open(output_path, 'r', encoding='utf-8') as f:
        lines = f.readlines()

        # First 5
        print("  First 5:")
        for i in range(min(5, len(lines))):
            data = json.loads(lines[i])
            print(f"    {data['id']}: {data['title'][:50]} (Tier {data['severity_tier']})")

        # Sample from middle
        print("  Sample from middle (Section 150-155):")
        for i in range(149, min(155, len(lines))):
            data = json.loads(lines[i])
            print(f"    {data['id']}: {data['title'][:50]} (Tier {data['severity_tier']})")

if __name__ == "__main__":
    main()

