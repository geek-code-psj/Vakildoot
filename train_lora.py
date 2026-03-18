"""
VakilDoot — LoRA Fine-Tuning Script
Indian Legal Specialist Training for Phi-4-mini-instruct

What this does:
  Takes the base Phi-4 Mini model and trains a small "adapter" (legal_lora.bin)
  that teaches it Indian law — BNS 2023, Contract Act, RERA, DPDP Act etc.
  The adapter is ~80 MB. The base model (1.9 GB) stays unchanged.
  At runtime, both are loaded together on the phone.

How to run:
  1. Get free GPU access at aikosh.indiaai.gov.in (click "Notebook")
     OR use Google Colab (colab.research.google.com) with T4 GPU (free tier)
  2. Upload this script and bns_indian_law_qa.json
  3. Run: python train_lora.py
  4. Download the output: legal_lora/ folder
  5. Convert to phone format and push to device (instructions at bottom)

Requirements:
  pip install transformers peft datasets accelerate bitsandbytes torch
"""

import json
import torch
from datasets import Dataset
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    TrainingArguments,
    Trainer,
    DataCollatorForSeq2Seq,
)
from peft import (
    LoraConfig,
    get_peft_model,
    TaskType,
    prepare_model_for_kbit_training,
)

# ── Configuration ──────────────────────────────────────────────────────────────

MODEL_NAME      = "microsoft/Phi-4-mini-instruct"
DATASET_FILE    = "bns_indian_law_qa.json"
OUTPUT_DIR      = "./legal_lora"
MAX_LENGTH      = 1024       # Max tokens per training example
EPOCHS          = 3          # 3 passes over the dataset
BATCH_SIZE      = 2          # Keep low for free GPU tier (16GB VRAM)
LEARNING_RATE   = 2e-4       # Standard LoRA learning rate
LORA_RANK       = 16         # Higher = more capacity, more memory
LORA_ALPHA      = 32         # Scaling factor (usually 2x rank)
LORA_DROPOUT    = 0.05

# ── System prompt (same as in LlmInferenceEngine.kt) ──────────────────────────

SYSTEM_PROMPT = """You are VakilDoot, an expert AI legal assistant specialising in Indian law.
You analyse contracts and legal documents with precise clause-level analysis.
Always cite exact section numbers. BNS 2023 replaces IPC, BNSS 2023 replaces CrPC,
BSA 2023 replaces IEA — effective 1 July 2024. Flag HIGH/MEDIUM/LOW RISK clearly."""


def format_training_example(example: dict) -> str:
    """
    Format a Q&A pair into Phi-4's chat template format.
    This is the format the model was originally trained on,
    so fine-tuning in the same format gives the best results.
    """
    return (
        f"<|system|>\n{SYSTEM_PROMPT}\n<|end|>\n"
        f"<|user|>\n{example['instruction']}\n<|end|>\n"
        f"<|assistant|>\n{example['response']}\n<|end|>"
    )


def load_dataset(filepath: str) -> Dataset:
    """Load and format the Indian law Q&A dataset."""
    with open(filepath, "r", encoding="utf-8") as f:
        raw_data = json.load(f)

    formatted = [{"text": format_training_example(ex)} for ex in raw_data]
    print(f"Loaded {len(formatted)} training examples")
    return Dataset.from_list(formatted)


def tokenize(examples, tokenizer):
    """Tokenize training examples."""
    result = tokenizer(
        examples["text"],
        truncation=True,
        max_length=MAX_LENGTH,
        padding=False,
    )
    result["labels"] = result["input_ids"].copy()
    return result


def main():
    print("=" * 60)
    print("VakilDoot LoRA Fine-Tuning — Indian Law Specialist")
    print("=" * 60)

    # ── Step 1: Load tokenizer ─────────────────────────────────────────────
    print("\n[1/5] Loading tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME, trust_remote_code=True)
    tokenizer.pad_token = tokenizer.eos_token
    tokenizer.padding_side = "right"

    # ── Step 2: Load base model in 4-bit (saves GPU memory) ───────────────
    print("[2/5] Loading Phi-4 Mini in 4-bit quantization...")
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_NAME,
        load_in_4bit=True,           # Reduces VRAM from 7.6 GB to ~4 GB
        torch_dtype=torch.float16,
        device_map="auto",
        trust_remote_code=True,
    )
    model = prepare_model_for_kbit_training(model)

    # ── Step 3: Attach LoRA adapter ────────────────────────────────────────
    print("[3/5] Attaching LoRA adapter...")
    lora_config = LoraConfig(
        task_type=TaskType.CAUSAL_LM,
        r=LORA_RANK,
        lora_alpha=LORA_ALPHA,
        lora_dropout=LORA_DROPOUT,
        # These are the attention layers LoRA will modify
        # Phi-4 uses these projection names — adjust if using different model
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                        "gate_proj", "up_proj", "down_proj"],
        bias="none",
    )
    model = get_peft_model(model, lora_config)
    model.print_trainable_parameters()
    # Expected output: trainable params: ~20M out of 3.8B (about 0.5%)
    # This is the beauty of LoRA — we only train a tiny fraction of weights

    # ── Step 4: Load and tokenize dataset ─────────────────────────────────
    print("[4/5] Loading Indian law dataset...")
    dataset = load_dataset(DATASET_FILE)
    tokenized = dataset.map(
        lambda x: tokenize(x, tokenizer),
        batched=True,
        remove_columns=dataset.column_names,
    )
    print(f"Dataset ready: {len(tokenized)} examples")

    # ── Step 5: Train ──────────────────────────────────────────────────────
    print("[5/5] Starting LoRA fine-tuning...")
    print(f"  Epochs:        {EPOCHS}")
    print(f"  Batch size:    {BATCH_SIZE}")
    print(f"  Learning rate: {LEARNING_RATE}")
    print(f"  LoRA rank:     {LORA_RANK}")
    print()

    training_args = TrainingArguments(
        output_dir=OUTPUT_DIR,
        num_train_epochs=EPOCHS,
        per_device_train_batch_size=BATCH_SIZE,
        gradient_accumulation_steps=4,    # Effective batch = 8
        learning_rate=LEARNING_RATE,
        fp16=True,                         # Mixed precision — faster on GPU
        logging_steps=10,
        save_steps=50,
        save_total_limit=2,
        warmup_ratio=0.05,
        lr_scheduler_type="cosine",
        report_to="none",                  # Disable wandb/tensorboard
        dataloader_pin_memory=False,       # For compatibility
    )

    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=tokenized,
        data_collator=DataCollatorForSeq2Seq(
            tokenizer, model=model, padding=True, pad_to_multiple_of=8
        ),
    )

    trainer.train()

    # ── Save ───────────────────────────────────────────────────────────────
    print(f"\nSaving LoRA adapter to {OUTPUT_DIR}/...")
    model.save_pretrained(OUTPUT_DIR)
    tokenizer.save_pretrained(OUTPUT_DIR)

    # Check output size
    import os
    total_size = sum(
        os.path.getsize(os.path.join(OUTPUT_DIR, f))
        for f in os.listdir(OUTPUT_DIR)
    )
    print(f"Adapter size: {total_size / 1024 / 1024:.1f} MB")
    print("\nTraining complete!")
    print(f"Output folder: {OUTPUT_DIR}/")
    print("  adapter_config.json    — LoRA configuration")
    print("  adapter_model.safetensors — The trained weights (~80 MB)")


if __name__ == "__main__":
    main()


# ══════════════════════════════════════════════════════════════════════════════
#  AFTER TRAINING — How to put the adapter on your phone
# ══════════════════════════════════════════════════════════════════════════════
#
#  Step 1: Convert adapter to a format ExecuTorch can load
#
#    python convert_lora_for_phone.py \
#      --lora_path ./legal_lora \
#      --output legal_lora_phone.bin
#
#  (I will write convert_lora_for_phone.py for you — just ask)
#
#  Step 2: Push to your Android phone
#
#    adb push legal_lora_phone.bin \
#      /data/data/com.vakildoot/files/models/legal_lora.bin
#
#  Step 3: Android Studio — add 2 lines to LlmInferenceEngine.kt:
#
#    val loraPath = context.filesDir.absolutePath + "/models/legal_lora.bin"
#    llmModule.loadLoraAdaptor(loraPath)   // call after llmModule.load()
#
#  That's it. VakilDoot now knows Indian law at specialist level.
#
# ══════════════════════════════════════════════════════════════════════════════
#  EXPANDING THE TRAINING DATA
# ══════════════════════════════════════════════════════════════════════════════
#
#  The more Q&A examples you add to bns_indian_law_qa.json, the better.
#  Good sources for new examples:
#
#  1. Indian Kanoon (indiankanoon.org)
#     - Search for any law (e.g. "Section 316 BNS cheating")
#     - Copy the judgment summary
#     - Format as: instruction = "What does the court say about X?"
#                  response = summary with section citations
#
#  2. eCourts (ecourts.gov.in)
#     - Supreme Court and High Court orders
#     - Especially useful for BNS 2023 early judgments
#
#  3. Your own contracts
#     - Take real NDA / lease / service agreement clauses
#     - instruction = "Analyse this clause: [paste clause]"
#     - response = your ideal analysis (I can help write these)
#
#  Minimum for good results: 200 examples
#  Good results: 500 examples
#  Excellent results: 2000+ examples
#
# ══════════════════════════════════════════════════════════════════════════════
