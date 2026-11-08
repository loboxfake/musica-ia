# Third-party notices

## LAME MP3 encoder

MusicalIA uses LAME 3.100 to encode local PCM WAV output into MP3. LAME is a separate native shared library (`libmp3lame.so`) and is built from source at the immutable upstream Android-port revision:

- Source: https://github.com/xieyangxuejun/libmp3lame-android
- Revision: `bab342ae0a1e2caa545a48e5e950a0b1e36b9004`
- Upstream LAME project: https://lame.sourceforge.io/
- License: GNU Library General Public License, version 2 (LGPL-2.0). The license text is included in `LAME-COPYING.txt`.

The LAME source files retain their original LGPL notices. The Android build adaptations are covered by the MIT notice in `libmp3lame-android-MIT.txt`. The Android build links to LAME as a separate shared library rather than statically incorporating it into the application library.


## Bonsai Image 4B / LiteRT

BARDO includes an optional on-device text-to-image pipeline based on Bonsai Image 4B LiteRT, distributed under Apache-2.0:

- Model: https://huggingface.co/litert-community/Bonsai-Image-ternary-4B
- Pinned model revision: ca6456fc392624e1690c9e8ec053ad5917500d0a
- Runtime/pipeline reference: https://github.com/john-rocky/hf-to-litertlm/tree/main/bonsai_image_work/device/BonsaiAppAndroid
- Android host code: Copyright 2026 Daisuke Majima, Apache-2.0. Original license header retained in the adapted Kotlin files.
- Runtime: LiteRT 2.1.3 with CPU/XNNPACK; no remote inference.
- The user explicitly authorizes the optional ~4.27 GB model download inside BARDO. Model graph and tokenizer files are size/SHA-256 checked; the small pipeline metadata is pinned to the immutable model revision and schema-validated.
