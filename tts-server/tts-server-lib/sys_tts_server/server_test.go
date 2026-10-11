package conv_server

import (
	log "github.com/sirupsen/logrus"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestTTSHandlerRejectsInvalidRequestWithoutSynthesis(t *testing.T) {
	called := false
	server := &ConvServer{
		Log: log.New(),
		OnGetWavAudio: func(engine string, voice string, text string, rate int32, pitch int32) ([]byte, error) {
			called = true
			return []byte("audio"), nil
		},
	}
	req := httptest.NewRequest("GET", "/api/tts?engine=&voice=&text=", nil)
	res := httptest.NewRecorder()

	server.ttsAPIHandler(res, req)

	if res.Code != 400 {
		t.Fatalf("expected 400, got %d", res.Code)
	}
	if called {
		t.Fatal("invalid request invoked Android TTS")
	}
}

func TestTTSHandlerAcceptsJSONPost(t *testing.T) {
	server := &ConvServer{
		Log: log.New(),
		OnGetWavAudio: func(engine string, voice string, text string, rate int32, pitch int32) ([]byte, error) {
			if engine != "mimo" || voice != "Mia" || text != "hello" || rate != 100 || pitch != 0 {
				t.Fatalf("unexpected request: %q %q %q %d %d", engine, voice, text, rate, pitch)
			}
			return []byte("wav"), nil
		},
	}
	req := httptest.NewRequest(http.MethodPost, "/api/tts", strings.NewReader(`{"engine":"mimo","voice":"Mia","text":"hello","rate":100,"pitch":0}`))
	req.Header.Set("Content-Type", "application/json")
	res := httptest.NewRecorder()

	server.ttsAPIHandler(res, req)

	if res.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", res.Code)
	}
	if res.Body.String() != "wav" {
		t.Fatalf("unexpected body %q", res.Body.String())
	}
}

func TestTTSHandlerRequiresConfiguredToken(t *testing.T) {
	called := false
	server := &ConvServer{
		Log:         log.New(),
		AccessToken: "secret",
		OnGetWavAudio: func(engine string, voice string, text string, rate int32, pitch int32) ([]byte, error) {
			called = true
			return []byte("wav"), nil
		},
	}
	req := httptest.NewRequest(http.MethodGet, "/api/tts?engine=mimo&voice=Mia&text=hello", nil)
	res := httptest.NewRecorder()

	server.ttsAPIHandler(res, req)

	if res.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", res.Code)
	}
	if called {
		t.Fatal("unauthorized request invoked Android TTS")
	}
}

func TestServerCloseBeforeStartIsSafe(t *testing.T) {
	if err := (&ConvServer{}).Close(0); err != nil {
		t.Fatal(err)
	}
}

type testCancelCallback struct {
}

func (testCancelCallback) Cancel() {

}
