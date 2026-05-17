# Animated Stickers Implementation & Verification

This document describes how animated WebP stickers are handled in KBoard and how to verify the implementation.

## Architecture

### 1. Binary Passthrough
Stickers from Klipy are downloaded as raw binary blobs and saved directly to the `cacheDir/klipy/` directory.
- **NEVER** decode or re-encode these files (e.g., via `BitmapFactory`).
- Preserving the `ANIM` and `ALPH` chunks is critical for WhatsApp to recognize them as animated.

### 2. Delivery Flow
- **FileProvider**: Used to share individual stickers from the cache with target apps like WhatsApp.
- **commitContent**: The keyboard uses `InputConnectionCompat.commitContent` with MIME type `image/webp.wasticker` (or `image/webp` fallback) to deliver the sticker URI.
- **StickerContentProvider**: Implements the WhatsApp Sticker Pack API (metadata, stickers, stickers_asset) to support potential pack-based integration.

## Verification Steps

### Automated Verification
1. **Binary Integrity Test**:
   - Save a test `.webp` file.
   - Use `sha256Hex` to verify the hash before and after any internal copy operation.
   - Assert hashes are identical.
2. **URI Generation Test**:
   - Verify `FileProvider.getUriForFile` produces a valid `content://` URI starting with the correct authority.

### Manual Verification Checklist
1. [ ] **Animated Preview**: Open the sticker panel and search for stickers. Verify they are NOT squished (aspect ratio preserved).
2. [ ] **WhatsApp Send**: Tap an animated sticker. Confirm it appears animated in WhatsApp.
3. [ ] **No Crashes**:
   - Drag stickers in the panel (no `ConcurrentModificationException`).
   - Start the IME (no `IInputMethodPrivilegedOperations` error).
   - Start the app (no `ContentProvider` authority error).

## Troubleshooting
- **Flattening**: If stickers are still flat, ensure the file size is under WhatsApp's thresholds (usually 512KB for stickers) and that no hidden decoding occurs in the URI delivery path.
- **MIME Types**: WhatsApp expects `image/webp.wasticker` for animated stickers via `commitContent`.

## Tool: Compute SHA-256
You can use the following command to compute the SHA-256 of a sticker file for comparison:
```bash
certutil -hashfile <path_to_file> SHA256
```
or on Linux/macOS:
```bash
sha256sum <path_to_file>
```
