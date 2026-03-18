# VakilDoot — File Organization Issues & Solutions

## 🔴 CRITICAL ISSUES FOUND

Three files are currently in the wrong location and need to be reorganized.

---

## Issue #1: `LlmInferenceEngine_BNS_updated.kt`

### ❌ Current Location
```
android/
└── LlmInferenceEngine_BNS_updated.kt   ← WRONG (at project root)
```

### ✅ Correct Location
```
android/app/src/main/java/com/vakildoot/ml/inference/
└── LlmInferenceEngine_BNS_updated.kt   ← CORRECT
```

### Problem
- **Severity:** 🔴 HIGH
- File is mixed with gradle scripts and configuration files
- Duplicates functionality with `app/src/main/java/com/vakildoot/ml/inference/LlmInferenceEngine.kt`
- The BNS-updated version is **superior** (better legal prompt)
- Won't be packaged into APK in current location

### Solution
1. **Delete** the old `LlmInferenceEngine.kt` (the one in app/src/main)
2. **Move** `LlmInferenceEngine_BNS_updated.kt` to `android/app/src/main/java/com/vakildoot/ml/inference/`
3. **Rename** to `LlmInferenceEngine.kt` (remove "_BNS_updated" suffix)
4. **Update** all imports in files that reference it:
   - `LlmInferenceEngine.kt` → No changes needed (same package)
   - `VakilDootViewModel.kt` → Already imports correct package ✅
   - `AppModule.kt` → Check if present ✅
   - `UseCases.kt` → Already imports correct package ✅

### Why This Version is Better
The BNS-updated version has:
- ✅ Complete legal statute reference (322 lines of legal context)
- ✅ BNS/BNSS/BSA 2023 transition rules
- ✅ Section number references for all Indian laws
- ✅ Enforcement guidance for Indian courts
- ✅ Transitional rules for pre/post July 1, 2024

---

## Issue #2: `train_lora.py`

### ❌ Current Location
```
android/
└── train_lora.py   ← WRONG (at project root, mixed with build files)
```

### ✅ Correct Location
```
ml-training/
├── train_lora.py   ← CORRECT (Python script)
├── requirements.txt
├── data/
│   └── bns_indian_law_qa.json
└── models/
    ├── phi4_mini_4bit.pte (generated)
    └── gemma_embedding.task (generated)
```

### Problem
- **Severity:** 🟡 MEDIUM
- ML training code shouldn't live in Android project
- Python code mixed with Kotlin/Gradle build system
- Makes repository structure confusing
- Obscures Android app's dependencies

### Solution
1. **Create** new `ml-training/` directory at project root
2. **Move** `train_lora.py` to `ml-training/`
3. **Move** `bns_indian_law_qa.json` to `ml-training/data/`
4. **Create** `ml-training/requirements.txt`:
   ```
   torch>=2.0.0
   transformers>=4.30.0
   peft>=0.4.0  # LoRA library
   bitsandbytes>=0.39.0  # 4-bit quantization
   ```
5. **Create** `ml-training/README.md` with LoRA training instructions
6. **Update** main `README.md` with reference to LoRA training

### Recommended ml-training Structure
```
ml-training/
├── README.md
├── train_lora.py           ← Main training script
├── export_model.py         ← ExecuTorch export utility
├── quantize_model.py       ← Quantization script
├── requirements.txt        ← Python dependencies
├── data/
│   ├── bns_indian_law_qa.json
│   └── instructions_template.json
└── models/
    ├── checkpoints/        ← During training
    ├── output/             ← Final LoRA adapter
    └── quantized/          ← Quantized output
```

---

## Issue #3: `bns_indian_law_qa.json`

### ❌ Current Location
```
android/
└── bns_indian_law_qa.json   ← WRONG (at project root)
```

### ✅ Correct Location
```
ml-training/data/
└── bns_indian_law_qa.json   ← CORRECT (training data, not app data)
```

### Problem
- **Severity:** 🟡 MEDIUM
- Training data mixed with Android configuration files
- Not used by the Android app at runtime
- Confusing whether it's a runtime dependency

### Solution
1. **Create** `ml-training/data/` directory
2. **Move** `bns_indian_law_qa.json` to `ml-training/data/`
3. **Reference** in ML training scripts:
   ```python
   # train_lora.py
   import json
   with open("data/bns_indian_law_qa.json") as f:
       dataset = json.load(f)
   ```

---

## 📋 Reorganization Checklist

### Step 1: Create New Directory Structure
```powershell
# In vakildoot_phase1_android/ (parent directory)
mkdir ml-training
mkdir ml-training\data
mkdir ml-training\models
mkdir ml-training\models\checkpoints
mkdir ml-training\models\output
mkdir ml-training\models\quantized
```

### Step 2: Move ML Files
```powershell
# Move Python training script
Move-Item .\android\train_lora.py .\ml-training\

# Move training data
Move-Item .\android\bns_indian_law_qa.json .\ml-training\data\

# Move LLM inference engine
Remove-Item .\android\app\src\main\java\com\vakildoot\ml\inference\LlmInferenceEngine.kt
Move-Item .\android\LlmInferenceEngine_BNS_updated.kt .\android\app\src\main\java\com\vakildoot\ml\inference\LlmInferenceEngine.kt
```

### Step 3: Create Supporting Files
```powershell
# Create Python requirements
New-Item .\ml-training\requirements.txt -Type File -Value @"
torch>=2.0.0
transformers>=4.30.0
peft>=0.4.0
bitsandbytes>=0.39.0
executorch>=0.3.0
numpy>=1.24.0
"@

# Create ML training README
New-Item .\ml-training\README.md -Type File
```

### Step 4: Update .gitignore
```
# Add to android/.gitignore
# ML models (too large for git)
/models/*.pte
/models/*.task
```

### Step 5: Verify Project Structure
```
vakildoot_phase1_android/
├── android/
│   ├── app/
│   │   ├── src/
│   │   │   ├── main/
│   │   │   │   ├── java/com/vakildoot/
│   │   │   │   │   ├── ml/inference/
│   │   │   │   │   │   ├── LlmInferenceEngine.kt        ✅ MOVED HERE
│   │   │   │   │   │   └── ThermalManager.kt
│   │   │   │   │   └── ...other files...
│   │   │   │   └── AndroidManifest.xml
│   │   │   └── test/
│   │   │       └── java/com/vakildoot/RagPipelineTest.kt
│   │   └── build.gradle.kts
│   ├── gradle/
│   │   └── libs.versions.toml
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── ml-training/                           ✅ NEW
│   ├── README.md                          ✅ NEW
│   ├── train_lora.py                      ✅ MOVED HERE
│   ├── export_model.py                    ✅ NEW (optional)
│   ├── requirements.txt                   ✅ NEW
│   ├── data/
│   │   └── bns_indian_law_qa.json        ✅ MOVED HERE
│   └── models/
│       ├── checkpoints/
│       ├── output/
│       └── quantized/
│
└── README.md (updated with ml-training reference)
```

---

## 🎯 After Reorganization

### Benefits
- ✅ Android app structure is clean
- ✅ ML training is self-contained
- ✅ Easy to build APK (no stray Python files)
- ✅ Clear separation of concerns
- ✅ Easier onboarding for new developers

### No Breaking Changes
- ✅ No code changes needed (same package structure in app)
- ✅ All imports remain valid
- ✅ Gradle build unaffected
- ✅ Tests unaffected

---

## 📝 Updated File References

### In `android/README.md`
Add section:
```markdown
## Training a Custom Legal Model

To train your own LoRA adapter for specialized legal domains:

```bash
cd ml-training
pip install -r requirements.txt
python train_lora.py \
    --model phi-4-mini-instruct \
    --data data/bns_indian_law_qa.json \
    --output models/output/legal_lora.bin
```

Then export to ExecuTorch format and copy to `/data/data/com.vakildoot/files/models/`
```

---

## ❌ BEFORE vs ✅ AFTER

### Before (Current)
```
android/
├── .gradle/
├── .idea/
├── app/
├── gradle/
├── bns_indian_law_qa.json          ❌ Wrong place
├── build.gradle.kts
├── local.properties
├── LlmInferenceEngine_BNS_updated.kt ❌ Wrong place
├── README.md
├── settings.gradle.kts
└── train_lora.py                   ❌ Wrong place
```

### After (Correct)
```
vakildoot_phase1_android/
├── android/                        ← Android app only
│   ├── .gradle/
│   ├── .idea/
│   ├── app/
│   │   ├── src/
│   │   │   ├── main/
│   │   │   │   └── java/.../ml/inference/
│   │   │   │       └── LlmInferenceEngine.kt  ✅ HERE
│   │   │   └── test/
│   │   └── build.gradle.kts
│   ├── gradle/
│   ├── build.gradle.kts
│   ├── local.properties
│   ├── README.md
│   └── settings.gradle.kts
│
├── ml-training/                    ← ML training code
│   ├── train_lora.py              ✅ HERE
│   ├── requirements.txt           ✅ HERE
│   ├── data/
│   │   └── bns_indian_law_qa.json ✅ HERE
│   └── models/
│
└── README.md (project overview)
```

---

## ⏱️ Implementation Time

| Task | Time | Notes |
|------|------|-------|
| Create directories | 2 min | PowerShell command |
| Move files | 3 min | 3 file moves |
| Rename file | 1 min | `_BNS_updated` → drop suffix |
| Create documentation | 10 min | ML training README |
| Verify no breakage | 5 min | Run `./gradlew build` |
| **Total** | **~20 min** | No code changes required |

---

**This reorganization is recommended before production deployment.**


