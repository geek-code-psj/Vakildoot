import pypdf
import re

with open('BNS Book_After Correction.pdf', 'rb') as f:
    reader = pypdf.PdfReader(f)
    full_text = ""
    for page_num in range(min(10, len(reader.pages))):
        text = reader.pages[page_num].extract_text()
        full_text += text + "\n"

    # Show first 2000 characters
    print(full_text[:2000])
    print("\n\n--- SEARCHING FOR SECTION PATTERNS ---")

    # Find section numbers
    section_nums = re.findall(r'Section\s+(\d+)', full_text)
    unique_sections = sorted(set(section_nums), key=int)
    print(f"Found section numbers: {unique_sections[:20]}")

