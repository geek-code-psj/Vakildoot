import pypdf
import re

with open('BNS Book_After Correction.pdf', 'rb') as f:
    reader = pypdf.PdfReader(f)

    print(f"Total pages: {len(reader.pages)}")

    # Sample pages from different locations to find actual content
    for page_idx in [20, 50, 100, 150]:
        if page_idx < len(reader.pages):
            text = reader.pages[page_idx].extract_text()
            print(f"\n--- PAGE {page_idx + 1} ---")
            print(text[:500])

            # Find section patterns
            sections = re.findall(r'(?:^|\n)\s*(?:Section\s+)?(\d+)\.\s*([A-Z][^\n]*)', text)
            if sections:
                print(f"Found {len(sections)} sections:")
                for sec_num, sec_title in sections[:3]:
                    print(f"  Section {sec_num}: {sec_title[:60]}")

