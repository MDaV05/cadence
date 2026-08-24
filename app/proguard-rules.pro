# R8 ships consumer keep rules for Media3, Compose, Room, and Coil, so the
# app itself needs almost nothing here. Add rules sparingly and verify with
# assembleRelease plus a playback smoke test — minification breaks reflection.

# Keep line numbers in release stack traces readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
