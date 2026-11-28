package mirrorbridge

import (
	"encoding/base64"
	"fmt"

	"fi.mirrormsg/mediaroute"
)

// ---- unified media entry point ---------------------------------------------
//
// These download once and hand the bytes to mediaroute, which encodes them and
// wraps the reply. Only the fetch is Google-specific and lives here; every other
// bridge has the same pair of methods over its own client.

// DownloadMediaForWatch downloads and decrypts once, then encodes for the watch.
func (b *Bridge) DownloadMediaForWatch(mediaID, decryptionKeyB64 string,
	maxW, maxH, maxBytes, quality, fpsFloor, maxFrames, gifBytes, startRung int) (string, error) {
	key, err := base64.StdEncoding.DecodeString(decryptionKeyB64)
	if err != nil {
		key, err = base64.RawStdEncoding.DecodeString(decryptionKeyB64)
		if err != nil {
			return "", fmt.Errorf("bad decryptionKey base64: %w", err)
		}
	}
	raw, err := b.downloadMedia(mediaID, key)
	if err != nil {
		return "", err
	}
	return mediaroute.Encode(raw, maxW, maxH, maxBytes, quality, fpsFloor, maxFrames, gifBytes, startRung)
}

// ProcessInlineMediaForWatch is the inline twin (MMS / RCS INCOMING_COMPLETE).
func (b *Bridge) ProcessInlineMediaForWatch(mediaDataB64 string,
	maxW, maxH, maxBytes, quality, fpsFloor, maxFrames, gifBytes, startRung int) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(mediaDataB64)
	if err != nil {
		raw, err = base64.RawStdEncoding.DecodeString(mediaDataB64)
		if err != nil {
			return "", fmt.Errorf("bad mediaData base64: %w", err)
		}
	}
	return mediaroute.Encode(raw, maxW, maxH, maxBytes, quality, fpsFloor, maxFrames, gifBytes, startRung)
}
