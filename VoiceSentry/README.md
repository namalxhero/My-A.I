# Voice Sentry

Hands-free phone control on a rooted Android phone. Runs with the screen off.
Only the enrolled owner's voice is obeyed; other voices and noise are ignored.

Pipeline (battery friendly, everything offline):
1. Energy gate: nothing heavy runs while the room is quiet.
2. Silero VAD: is this human speech?
3. Speaker verification (WeSpeaker): is it the owner? Others are dropped here.
4. Whisper tiny.en: what was said? (only runs for the owner)
5. Wake word + command -> executed with `su`.

Build: push this folder to GitHub, open Actions > Build APK, download `VoiceSentry-apk`.
First launch: allow mic, Magisk "Grant", record your voice, enter PIN, tap the orb.
