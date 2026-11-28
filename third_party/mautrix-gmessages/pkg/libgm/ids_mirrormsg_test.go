package libgm

// MirrorMsg's test of its v0.2611.0 backport (fillMissingIDs, see
// MIRRORMSG-PATCH.md). Ids are made up.

import (
	"strings"
	"testing"

	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"

	"go.mau.fi/mautrix-gmessages/pkg/libgm/gmproto"
)

// A conversation in the new format: its ids only in the nested fields.
func newFormatConversation(id, latest string) *gmproto.Conversation {
	return &gmproto.Conversation{
		Name:                  "Placeholder",
		SomeKindOfGroupID:     &gmproto.SomeKindOfGroupID{ConversationID: id},
		LatestMessageAnoherID: &gmproto.AnotherMessageID{MessageID: latest},
	}
}

// A message in the new format.
func newFormatMessage(id, conv string) *gmproto.Message {
	return &gmproto.Message{
		SomeKindOfGroupID: &gmproto.SomeKindOfGroupID{ConversationID: conv},
		AnotherMessageID:  &gmproto.AnotherMessageID{MessageID: id},
	}
}

// Through the wire bytes, as decryptInternalMessage gets them.
func roundTrip[T proto.Message](t *testing.T, in T, out T) T {
	t.Helper()
	b, err := proto.Marshal(in)
	if err != nil {
		t.Fatal(err)
	}
	if err = proto.Unmarshal(b, out); err != nil {
		t.Fatal(err)
	}
	fillMissingIDs(out)
	return out
}

func TestFillMissingIDsListConversations(t *testing.T) {
	resp := roundTrip(t, &gmproto.ListConversationsResponse{Conversations: []*gmproto.Conversation{
		newFormatConversation("101", "m1"),
		{ConversationID: "102", LatestMessageID: "m2",
			SomeKindOfGroupID: &gmproto.SomeKindOfGroupID{ConversationID: "other"}},
		{ConversationID: "103"},
	}}, &gmproto.ListConversationsResponse{})
	c := resp.GetConversations()
	if c[0].GetConversationID() != "101" || c[0].GetLatestMessageID() != "m1" {
		t.Errorf("new format not filled: %q %q", c[0].GetConversationID(), c[0].GetLatestMessageID())
	}
	if c[1].GetConversationID() != "102" || c[1].GetLatestMessageID() != "m2" {
		t.Errorf("old format changed: %q %q", c[1].GetConversationID(), c[1].GetLatestMessageID())
	}
	if c[2].GetConversationID() != "103" || c[2].GetLatestMessageID() != "" {
		t.Errorf("no nested ids: %q %q", c[2].GetConversationID(), c[2].GetLatestMessageID())
	}
	// What the app reads: the list's JSON names each chat.
	js, err := protojson.Marshal(resp)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(js), `"conversationID":"101"`) {
		t.Errorf("JSON has no conversationID for the new-format chat: %s", js)
	}
}

func TestFillMissingIDsListMessages(t *testing.T) {
	resp := roundTrip(t, &gmproto.ListMessagesResponse{Messages: []*gmproto.Message{
		newFormatMessage("m1", "101"),
		{MessageID: "m2", ConversationID: "101"},
	}}, &gmproto.ListMessagesResponse{})
	m := resp.GetMessages()
	if m[0].GetMessageID() != "m1" || m[0].GetConversationID() != "101" {
		t.Errorf("new format not filled: %q %q", m[0].GetMessageID(), m[0].GetConversationID())
	}
	if m[1].GetMessageID() != "m2" || m[1].GetConversationID() != "101" {
		t.Errorf("old format changed: %q %q", m[1].GetMessageID(), m[1].GetConversationID())
	}
}

func TestFillMissingIDsUpdateEvents(t *testing.T) {
	msgs := roundTrip(t, &gmproto.UpdateEvents{Event: &gmproto.UpdateEvents_MessageEvent{
		MessageEvent: &gmproto.MessageEvent{Data: []*gmproto.Message{newFormatMessage("m3", "104")}},
	}}, &gmproto.UpdateEvents{})
	if m := msgs.GetMessageEvent().GetData()[0]; m.GetMessageID() != "m3" || m.GetConversationID() != "104" {
		t.Errorf("message event not filled: %q %q", m.GetMessageID(), m.GetConversationID())
	}
	convs := roundTrip(t, &gmproto.UpdateEvents{Event: &gmproto.UpdateEvents_ConversationEvent{
		ConversationEvent: &gmproto.ConversationEvent{Data: []*gmproto.Conversation{newFormatConversation("104", "m3")}},
	}}, &gmproto.UpdateEvents{})
	if c := convs.GetConversationEvent().GetData()[0]; c.GetConversationID() != "104" || c.GetLatestMessageID() != "m3" {
		t.Errorf("conversation event not filled: %q %q", c.GetConversationID(), c.GetLatestMessageID())
	}
}

func TestFillMissingIDsSingleConversation(t *testing.T) {
	got := roundTrip(t, &gmproto.GetOrCreateConversationResponse{Conversation: newFormatConversation("105", "")},
		&gmproto.GetOrCreateConversationResponse{})
	if got.GetConversation().GetConversationID() != "105" {
		t.Errorf("GetOrCreateConversation not filled: %q", got.GetConversation().GetConversationID())
	}
	one := roundTrip(t, &gmproto.GetConversationResponse{Conversation: newFormatConversation("106", "")},
		&gmproto.GetConversationResponse{})
	if one.GetConversation().GetConversationID() != "106" {
		t.Errorf("GetConversation not filled: %q", one.GetConversation().GetConversationID())
	}
	// Nothing to fill, and nil, are left alone.
	fillMissingIDs(&gmproto.GetConversationResponse{})
	fillMissingIDs(nil)
}

// The hook itself: a chat list answer in the new format, encrypted as the phone
// sends it, comes out of decryptInternalMessage with its ids filled.
func TestDecryptFillsMissingIDs(t *testing.T) {
	c := &Client{AuthData: NewAuthData()}
	plain, err := proto.Marshal(&gmproto.ListConversationsResponse{
		Conversations: []*gmproto.Conversation{newFormatConversation("107", "m7")},
	})
	if err != nil {
		t.Fatal(err)
	}
	enc, err := c.AuthData.RequestCrypto.Encrypt(plain)
	if err != nil {
		t.Fatal(err)
	}
	data, err := proto.Marshal(&gmproto.RPCMessageData{
		Action:        gmproto.ActionType_LIST_CONVERSATIONS,
		EncryptedData: enc,
	})
	if err != nil {
		t.Fatal(err)
	}
	msg, err := c.decryptInternalMessage(&gmproto.IncomingRPCMessage{
		BugleRoute:  gmproto.BugleRoute_DataEvent,
		MessageData: data,
	})
	if err != nil {
		t.Fatal(err)
	}
	resp, ok := msg.DecryptedMessage.(*gmproto.ListConversationsResponse)
	if !ok {
		t.Fatalf("decrypted %T", msg.DecryptedMessage)
	}
	if got := resp.GetConversations()[0].GetConversationID(); got != "107" {
		t.Errorf("conversationID %q, want 107", got)
	}
}
