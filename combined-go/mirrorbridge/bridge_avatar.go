package mirrorbridge

import (
	"context"
	"time"
)

// ---- Contact photos (step 90) ----------------------------------------------
// The phone shows a chat's photo in the watch's chat list and as the picture of
// its notification. A 1:1 chat's photo is the other person's, which Google keeps
// for saved contacts (GetParticipantThumbnail, as mautrix-gmessages does for its
// ghosts); a group's is its icon (Conversation.groupAvatarURL), fetched with
// DownloadAvatar. Both give the image's bytes (JPEG or PNG), or nothing.

// ParticipantThumbnail returns a participant's photo, or nothing when there's none.
func (b *Bridge) ParticipantThumbnail(participantID string) ([]byte, error) {
	resp, err := b.client.GetParticipantThumbnail(b.rc(), participantID)
	if err != nil {
		return nil, err
	}
	for _, t := range resp.GetThumbnail() {
		if d := t.GetData().GetImageBuffer(); len(d) > 0 {
			return d, nil
		}
	}
	return nil, nil
}

// GroupAvatar fetches a group chat's icon from its address (groupAvatarURL).
func (b *Bridge) GroupAvatar(url string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(b.ctx, 20*time.Second)
	defer cancel()
	return b.client.DownloadAvatar(ctx, url)
}
