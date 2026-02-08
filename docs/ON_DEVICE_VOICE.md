# On-Device Voice Processing Setup

This document explains how to set up on-device speech recognition using **Whisper** and **DeepFilterNet** for the CNC Pendant app.

## Overview

The app supports two speech recognition modes:

1. **Android Speech Recognition** (default) - Uses Google's cloud-based speech recognition
2. **On-Device Whisper + DeepFilterNet** - Fully offline, privacy-respecting speech recognition with optional noise reduction

## Benefits of On-Device Processing

- **Privacy**: Audio never leaves your device
- **Offline**: Works without internet connection
- **Noise Reduction**: DeepFilterNet removes machine shop background noise
- **Improved Accuracy**: Especially in noisy CNC environments

## Setup Instructions

### Step 1: Download Model Files

#### Whisper Model
Download the Whisper model from Hugging Face:
- **Recommended**: `ggml-tiny.en.bin` (~75MB) - Fast, English only
- **Alternative**: `ggml-base.en.bin` (~142MB) - Better accuracy, slower

Download from: https://huggingface.co/ggerganov/whisper.cpp/tree/main

#### DeepFilterNet Model
Download the DeepFilterNet3 ONNX model:
- `deepfilter3.onnx` (~15MB)

Download from: https://github.com/Rikorose/DeepFilterNet/releases

### Step 2: Place Model Files

Place the downloaded models in the appropriate assets directories:

```
app/src/main/assets/
├── models/
│   ├── whisper/
│   │   └── ggml-tiny.en.bin
│   └── deepfilter/
│       └── deepfilter3.onnx
```

### Step 3: Build Native Libraries (Whisper)

Whisper requires native C++ libraries (whisper.cpp). You need to:

1. Clone whisper.cpp: `git clone https://github.com/ggerganov/whisper.cpp`
2. Build for Android using NDK
3. Place the resulting `.so` files in `app/src/main/jniLibs/`

```
app/src/main/jniLibs/
├── arm64-v8a/
│   └── libwhisper.so
├── armeabi-v7a/
│   └── libwhisper.so
└── x86_64/
    └── libwhisper.so
```

See: https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.android

### Step 4: Enable in App

1. Open the Voice Control screen
2. Tap the Settings (gear) icon
3. Enable "Use Whisper (On-Device)"
4. Optionally enable "DeepFilterNet Noise Reduction"

## Architecture

```
┌─────────────────┐
│  AudioRecorder  │ ← Captures 16kHz PCM audio
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  DeepFilterNet  │ ← [Optional] Removes background noise
│    (ONNX)       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│    Whisper      │ ← Transcribes speech to text
│  (whisper.cpp)  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ VoiceProcessor  │ ← Orchestrates the pipeline
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Command Parser │ ← Parses CNC commands from text
└─────────────────┘
```

## Files Added

- `voice/AudioRecorder.kt` - Captures raw audio using AudioRecord
- `voice/SpeechTranscriber.kt` - Interface for speech-to-text
- `voice/WhisperTranscriber.kt` - Whisper implementation via JNI
- `voice/DeepFilterNetProcessor.kt` - ONNX-based noise reduction
- `voice/VoiceProcessor.kt` - Orchestrates the full pipeline

## Performance Notes

| Model | Size | RTF* | Memory |
|-------|------|------|--------|
| Whisper Tiny | 75MB | ~0.5x | ~200MB |
| Whisper Base | 142MB | ~1.0x | ~400MB |
| DeepFilterNet | 15MB | ~0.1x | ~50MB |

*RTF = Real-Time Factor (1.0 = real-time, <1.0 = faster than real-time)

## Troubleshooting

### "Whisper native library not loaded"
- Ensure `libwhisper.so` is in the correct jniLibs directory for your device's architecture

### "Whisper model not found"
- Check that `ggml-tiny.en.bin` is in `assets/models/whisper/`

### "DeepFilterNet initialization failed"
- Verify ONNX model is in `assets/models/deepfilter/`
- Check that ONNX Runtime is included in dependencies

### Poor recognition accuracy
- Ensure you're using the English model for English commands
- Enable DeepFilterNet for noisy environments
- Speak clearly and at a moderate pace

## Future Improvements

- [ ] Automatic model download on first use
- [ ] Model selection in settings (tiny/base/small)
- [ ] Fine-tuning for CNC-specific vocabulary
- [ ] Voice activity detection optimization
- [ ] Streaming transcription for real-time feedback
