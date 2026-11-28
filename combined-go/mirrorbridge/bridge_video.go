package mirrorbridge

// Video streaming: the phone decodes the video itself (Android's hardware
// decoder), so it needs the file, not an encoded envelope. This is the
// download half of DownloadMediaForWatch, writing the decrypted bytes to path.

import (
	"encoding/base64"
	"fmt"
	"os"
)

// DownloadMediaToFile downloads and decrypts a message's media to path and
// returns its size in bytes.
func (b *Bridge) DownloadMediaToFile(mediaID, decryptionKeyB64, path string) (int, error) {
	key, err := base64.StdEncoding.DecodeString(decryptionKeyB64)
	if err != nil {
		key, err = base64.RawStdEncoding.DecodeString(decryptionKeyB64)
		if err != nil {
			return 0, fmt.Errorf("bad decryptionKey base64: %w", err)
		}
	}
	raw, err := b.readMedia(mediaID, key)
	if err != nil {
		return 0, fmt.Errorf("DownloadMedia: %w", err)
	}
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		return 0, fmt.Errorf("write %s: %w", path, err)
	}
	return len(raw), nil
}
