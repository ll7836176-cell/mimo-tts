package tts_server_lib

import (
	"testing"

	"github.com/jing332/tts-server-go/tts"
)

func TestVoicePropertyProtoMapsProsodyAndExpression(t *testing.T) {
	property := &VoiceProperty{VoiceName: "zh-CN-XiaoxiaoNeural"}
	prosody := &VoiceProsody{Rate: 10, Volume: 50, Pitch: 0}
	expression := &VoiceExpressAs{Style: "general", StyleDegree: 1.5, Role: "default"}

	result := property.Proto(prosody, expression)
	if result.VoiceName != property.VoiceName {
		t.Fatalf("voice name changed during conversion: %q", result.VoiceName)
	}
	if result.Prosody != (*tts.Prosody)(prosody) {
		t.Fatal("prosody was not mapped to the library type")
	}
	if result.ExpressAs != (*tts.ExpressAs)(expression) {
		t.Fatal("expression was not mapped to the library type")
	}
}
