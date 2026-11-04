# Third-party notices

## LAME MP3 encoder

MusicalIA uses LAME 3.100 to encode local PCM WAV output into MP3. LAME is a separate native shared library (`libmp3lame.so`) and is built from source at the immutable upstream Android-port revision:

- Source: https://github.com/xieyangxuejun/libmp3lame-android
- Revision: `bab342ae0a1e2caa545a48e5e950a0b1e36b9004`
- Upstream LAME project: https://lame.sourceforge.io/
- License: GNU Library General Public License, version 2 (LGPL-2.0). The license text is included in `LAME-COPYING.txt`.

The LAME source files retain their original copyright and license notices. The Android build links to LAME as a separate shared library rather than statically incorporating it into the application library.
