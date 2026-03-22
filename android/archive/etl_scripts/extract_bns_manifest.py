#!/usr/bin/env python3
"""
BNS 2023 Severity-Weighted Legal Manifest Extractor
Extracts 358 sections with severity classification and fair-use summaries
"""

import re
import json
from pathlib import Path
from typing import List, Dict, Tuple
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
    if any(word in punishment_lower for word in ['community service', 'fine only', 'fine or']):
        if 'imprisonment' not in punishment_lower:
            return 1

    # If imprisonment mentioned but no duration found, assume moderate
    if 'imprisonment' in punishment_lower:
        return 2

    # Default to Tier 1 if punishment exists but doesn't fit other categories
    if punishment_text.strip():
        return 1

    return 0

def extract_sections_from_pdf(pdf_path: str) -> List[Dict]:
    """
    Extract all sections from the BNS PDF.
    Returns a list of section dictionaries with: number, title, content, punishment
    """
    sections = []

    with open(pdf_path, 'rb') as f:
        reader = pypdf.PdfReader(f)
        print(f"Total pages: {len(reader.pages)}")

        full_text = ""
        for page_num, page in enumerate(reader.pages):
            text = page.extract_text()
            full_text += f"\n--- PAGE {page_num + 1} ---\n" + text

    # Split by section pattern: "Section [Number]." or "[Number]."
    # Pattern: Section followed by optional numbers and period, or just number.
    section_pattern = r'(?:^|\n)\s*(?:Section\s+)?(\d+)\.\s*([A-Z][^\n]*?)(?:\n|$)'

    # Find all matches
    matches = list(re.finditer(section_pattern, full_text, re.MULTILINE))
    print(f"Found {len(matches)} section headers")

    for idx, match in enumerate(matches):
        section_num = match.group(1)
        section_title = match.group(2).strip()

        # Get content until next section
        content_start = match.end()
        if idx + 1 < len(matches):
            content_end = matches[idx + 1].start()
        else:
            content_end = len(full_text)

        section_content = full_text[content_start:content_end].strip()

        # Extract punishment information (look for lines with imprisonment/fine/punishment)
        punishment_lines = []
        for line in section_content.split('\n')[:20]:  # Check first 20 lines for punishment
            if any(word in line.lower() for word in ['imprisonment', 'fine', 'punishment', 'penalty', 'community service']):
                punishment_lines.append(line.strip())

        punishment_text = ' '.join(punishment_lines) if punishment_lines else ''

        sections.append({
            'number': section_num,
            'title': section_title,
            'content': section_content[:200],  # First 200 chars
            'punishment': punishment_text
        })

    return sections

def generate_fair_use_summary(section_title: str, section_number: str, content: str) -> str:
    """
    Generate a 15-20 word fair-use summary based on title and content.
    """
    # Extract key terms from title and content
    title_words = section_title.lower().split()

    # Find first sentence or main concept
    sentences = content.split('.')
    first_info = sentences[0] if sentences else ""

    # Build summary
    if section_number in ['2']:
        return "Definitions of key terms used throughout the Bharatiya Nyaya Sanhita."
    elif any(word in section_title.lower() for word in ['exception', 'defence']):
        return f"Legal exception or general defence provision: {section_title[:50]}"
    elif any(word in section_title.lower() for word in ['murder', 'culpable homicide']):
        return f"Unlawful killing under specific legal circumstances with defined penalties."
    elif any(word in section_title.lower() for word in ['rape', 'sexual', 'assault']):
        return f"Sexual offense involving non-consensual acts against a person."
    elif any(word in section_title.lower() for word in ['theft', 'wrongful gain', 'theft']):
        return f"Unlawful appropriation of property belonging to another person."
    elif any(word in section_title.lower() for word in ['cheating', 'fraud', 'criminal deception']):
        return f"Deceptive acts to induce a person to part with property or advantage."
    elif any(word in section_title.lower() for word in ['defamation', 'libel', 'slander']):
        return f"False statements damaging to reputation; fine and imprisonment possible."
    elif any(word in section_title.lower() for word in ['criminal intimidation', 'threat']):
        return f"Threatening words or acts intended to cause alarm and fear."
    elif any(word in section_title.lower() for word in ['criminal breach', 'trust', 'breach']):
        return f"Misappropriation of property held in trust; betrayal of confidence."
    elif any(word in section_title.lower() for word in ['criminal conspiracy']):
        return f"Agreement between two or more persons to commit an unlawful act."
    elif any(word in section_title.lower() for word in ['attempt']):
        return f"Intentional steps toward committing an offense, even if unsuccessful."
    elif any(word in section_title.lower() for word in ['abetment']):
        return f"Instigating, aiding, or abetting another person to commit offense."
    elif any(word in section_title.lower() for word in ['common intention']):
        return f"Acting with knowledge of common intention leads to shared liability."
    elif any(word in section_title.lower() for word in ['hurt', 'injury', 'grievous']):
        return f"Physical injury or bodily harm caused by unlawful force or act."
    elif any(word in section_title.lower() for word in ['wrongful restraint', 'wrongful confinement', 'unlawful confinement']):
        return f"Restraining or confining a person without legal authority or consent."
    elif any(word in section_title.lower() for word in ['criminal force']):
        return f"Intentional use of physical force without lawful right or authority."
    elif any(word in section_title.lower() for word in ['criminal intimidation']):
        return f"Threatening act or words to cause fear and compel action."
    elif any(word in section_title.lower() for word in ['criminal breach of trust']):
        return f"Misappropriation by person holding property in fiduciary capacity."
    elif any(word in section_title.lower() for word in ['public servant', 'official']):
        return f"Offenses by public servants misusing their official authority."
    elif any(word in section_title.lower() for word in ['bribery', 'corruption']):
        return f"Offering or accepting illegal gratification for official favors."
    elif any(word in section_title.lower() for word in ['false evidence']):
        return f"Giving false or misleading testimony in judicial proceedings."
    elif any(word in section_title.lower() for word in ['communal violence', 'riot']):
        return f"Public disturbance involving multiple persons using violence."
    else:
        # Generic fallback
        title_short = section_title[:50]
        return f"{title_short}. Specific offense with defined penalties and procedures."

def create_jsonl_manifest(sections: List[Dict], output_path: str):
    """
    Create JSONL file with severity-weighted manifest.
    """
    official_link_base = "https://www.indiacode.nic.in/handle/123456789/20488?view_type=search&sam_handle=123456789/1362"

    with open(output_path, 'w', encoding='utf-8') as f:
        for section in sections:
            severity_tier = classify_severity_tier(section['punishment'])
            fair_use_summary = generate_fair_use_summary(
                section['title'],
                section['number'],
                section['content']
            )

            record = {
                "id": f"BNS_SEC_{section['number']}",
                "section_number": section['number'],
                "title": section['title'],
                "severity_tier": severity_tier,
                "punishment_summary": section['punishment'] if section['punishment'] else "No punishment specified",
                "fair_use_summary": fair_use_summary[:120],  # Trim to ~20 words
                "official_reference_link": f"{official_link_base}&section={section['number']}",
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
    sections = extract_sections_from_pdf(str(pdf_path))

    print(f"Extracted {len(sections)} sections")
    print("Creating JSONL manifest...")
    create_jsonl_manifest(sections, str(output_path))

    print(f"✓ Manifest created: {output_path}")

    # Show sample
    print("\nSample records (first 3):")
    with open(output_path, 'r', encoding='utf-8') as f:
        for i, line in enumerate(f):
            if i < 3:
                data = json.loads(line)
                print(f"  {data['id']}: {data['title'][:50]} (Tier {data['severity_tier']})")
            else:
                break

if __name__ == "__main__":
    main()

