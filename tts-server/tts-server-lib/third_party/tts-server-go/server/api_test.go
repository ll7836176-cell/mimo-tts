package server

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"
)

func TestShutdown(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}

	s := &GracefulServer{
		Server:       &http.Server{Handler: http.NewServeMux()},
		shutdownLoad: make(chan struct{}),
	}
	serveDone := make(chan error, 1)
	go func() {
		serveDone <- s.Server.Serve(listener)
	}()

	if err := s.Shutdown(time.Second); err != nil {
		t.Fatalf("Shutdown() error = %v", err)
	}
	if s.shutdownLoad != nil {
		t.Fatal("shutdownLoad should be cleared after shutdown")
	}
	if err := <-serveDone; err != http.ErrServerClosed {
		t.Fatalf("Serve() error = %v, want %v", err, http.ErrServerClosed)
	}
}

func TestLegadoAPIHandlerUsesHeaderTokenAndEncodesJSON(t *testing.T) {
	query := url.Values{}
	query.Set("api", "http://127.0.0.1:1233/api/azure?existing=a&other=b")
	query.Set("name", "中文 & TTS")
	query.Set("voiceName", "zh-CN-XiaoxiaoNeural")
	query.Set("voiceFormat", "audio-24khz-48kbitrate-mono-mp3")
	query.Set("concurrentRate", "5000")
	req, err := http.NewRequest(http.MethodGet, "/api/legado", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.URL.RawQuery = query.Encode()
	req.Header.Set("Token", `secret "quoted" & value`)
	response := httptest.NewRecorder()

	(&GracefulServer{}).legadoAPIHandler(response, req)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	if got := response.Header().Get("Content-Type"); got != "application/json; charset=utf-8" {
		t.Fatalf("Content-Type = %q", got)
	}

	var config LegadoJson
	if err := json.Unmarshal(response.Body.Bytes(), &config); err != nil {
		t.Fatalf("decode config: %v", err)
	}
	var headers map[string]string
	if err := json.Unmarshal([]byte(config.Header), &headers); err != nil {
		t.Fatalf("decode generated headers: %v", err)
	}
	if got := headers["Token"]; got != `secret "quoted" & value` {
		t.Fatalf("generated Token header = %q", got)
	}
	if got := headers["Content-Type"]; got != "text/plain" {
		t.Fatalf("generated Content-Type header = %q", got)
	}
	if got := headers["Format"]; got != "audio-24khz-48kbitrate-mono-mp3" {
		t.Fatalf("generated Format header = %q", got)
	}
	if got := config.Name; got != "中文 & TTS" {
		t.Fatalf("name = %q", got)
	}
	if got := config.URL[:len(query.Get("api"))]; got != query.Get("api") {
		t.Fatalf("generated URL prefix = %q", got)
	}
}
