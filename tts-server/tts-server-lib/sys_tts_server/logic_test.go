package conv_server

import (
	"encoding/json"
	"net/url"
	"strings"
	"testing"
)

func TestLegado(t *testing.T) {
	json, err := getLegadoJson("http://127.0.0.1/api/tts", "ni好啊", "com.gggoel.gtts", "voiceqwq", "50")
	if err != nil {
		t.Fatal(err)
	}
	t.Log(json)
}

func TestLegadoConfigCarriesTheConfiguredAccessTokenAsHeader(t *testing.T) {
	jsonText, err := getLegadoJson(
		"http://127.0.0.1/api/tts",
		"reader",
		"engine",
		"voice",
		"0",
		"secret",
	)
	if err != nil {
		t.Fatal(err)
	}
	var config LegadoJson
	if err := json.Unmarshal([]byte(jsonText), &config); err != nil {
		t.Fatal(err)
	}
	var headers map[string]string
	if err := json.Unmarshal([]byte(config.Header), &headers); err != nil {
		t.Fatal(err)
	}
	if headers["X-TTS-Token"] != "secret" {
		t.Fatalf("expected token header in Legado config, got %q", config.Header)
	}
}

func TestLegadoURLPreservesSpecialQueryValues(t *testing.T) {
	engine := "engine&name+one"
	voice := "voice #2"
	jsonText, err := getLegadoJson("http://127.0.0.1/api/tts", "reader & voice", engine, voice, "-12")
	if err != nil {
		t.Fatal(err)
	}
	var config LegadoJson
	if err := json.Unmarshal([]byte(jsonText), &config); err != nil {
		t.Fatal(err)
	}
	parsed, err := url.Parse(config.URL)
	if err != nil {
		t.Fatal(err)
	}
	params := parsed.Query()
	if params.Get("engine") != engine || params.Get("voice") != voice || params.Get("pitch") != "-12" {
		t.Fatalf("query values were not preserved: %#v", params)
	}
	if params.Get("text") != "{{java.encodeURIComponent(speakText)}}" {
		t.Fatalf("unexpected Legado text template %q", params.Get("text"))
	}
	if !strings.Contains(config.URL, "text={{java.encodeURIComponent(speakText)}}") {
		t.Fatalf("Legado template must remain visible to AnalyzeUrl: %q", config.URL)
	}
}
