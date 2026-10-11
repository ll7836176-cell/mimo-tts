package conv_server

import (
	"encoding/json"
	"net/url"
	"strings"
	"time"
)

type LegadoJson struct {
	ContentType    string `json:"contentType"`
	Header         string `json:"header"`
	ID             int64  `json:"id"`
	LastUpdateTime int64  `json:"lastUpdateTime"`
	Name           string `json:"name"`
	URL            string `json:"url"`
	ConcurrentRate string `json:"concurrentRate"`
	//EnabledCookieJar bool   `json:"enabledCookieJar"`
	//LoginCheckJs   string `json:"loginCheckJs"`
	//LoginUI        string `json:"loginUi"`
	//LoginURL       string `json:"loginUrl"`
}

func getLegadoJson(api string, displayName string, engine string, voice string, pitch string, accessTokens ...string) (string, error) {
	t := time.Now().UnixNano() / 1e6
	endpoint, err := url.Parse(api)
	if err != nil {
		return "", err
	}
	params := endpoint.Query()
	for _, key := range []string{"engine", "text", "rate", "pitch", "voice"} {
		params.Del(key)
	}
	query := params.Encode()
	queryParts := []string{
		"engine=" + url.QueryEscape(engine),
		"text={{java.encodeURIComponent(speakText)}}",
		"rate={{speakSpeed * 2}}",
		"pitch=" + url.QueryEscape(pitch),
	}
	if voice != "" {
		queryParts = append(queryParts, "voice="+url.QueryEscape(voice))
	}
	if query != "" {
		queryParts = append([]string{query}, queryParts...)
	}
	endpoint.RawQuery = strings.Join(queryParts, "&")

	var header string
	if len(accessTokens) > 0 && accessTokens[0] != "" {
		headerBytes, err := json.Marshal(map[string]string{"X-TTS-Token": accessTokens[0]})
		if err != nil {
			return "", err
		}
		header = string(headerBytes)
	}
	data := &LegadoJson{Name: displayName, LastUpdateTime: t, ID: t, URL: endpoint.String(), Header: header, ContentType: "audio/x-wav", ConcurrentRate: "100"}
	jsonStr, err := json.Marshal(data)
	if err != nil {
		return "", err
	}
	return string(jsonStr), nil
}
