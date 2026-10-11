package server

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestGenLegadoJsonEscapesAttributesAndHeaders(t *testing.T) {
	token := `secret "quoted" & value`
	voiceName := `voice"<&'`
	styleName := `style&"`
	roleName := `role<'`
	secondaryLocale := `zh&"`
	voiceFormat := `audio-format"&`

	data, err := genLegadoJson("http://127.0.0.1:1233/api/azure", "测试 & 配置", voiceName,
		secondaryLocale, styleName, "1.0", roleName, voiceFormat, token, "5000")
	if err != nil {
		t.Fatalf("genLegadoJson() error = %v", err)
	}

	var config LegadoJson
	if err := json.Unmarshal(data, &config); err != nil {
		t.Fatalf("decode config: %v", err)
	}
	if !strings.Contains(config.URL, `voice name=\"voice&quot;&lt;&amp;&apos;\"`) {
		t.Fatalf("voice name was not XML-escaped: %s", config.URL)
	}
	if !strings.Contains(config.URL, `xml:lang=\"zh&amp;&quot;\"`) {
		t.Fatalf("secondary locale was not XML-escaped: %s", config.URL)
	}
	if !strings.Contains(config.URL, `style=\"style&amp;&quot;\"`) {
		t.Fatalf("style was not XML-escaped: %s", config.URL)
	}
	if !strings.Contains(config.URL, `role=\"role&lt;&apos;\"`) {
		t.Fatalf("role was not XML-escaped: %s", config.URL)
	}
	var headers map[string]string
	if err := json.Unmarshal([]byte(config.Header), &headers); err != nil {
		t.Fatalf("decode generated headers: %v", err)
	}
	if headers["Token"] != token || headers["Format"] != voiceFormat {
		t.Fatalf("generated headers = %#v", headers)
	}
}
