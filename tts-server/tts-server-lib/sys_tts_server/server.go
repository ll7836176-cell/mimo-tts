package conv_server

import (
	"context"
	"crypto/subtle"
	"embed"
	"encoding/json"
	"errors"
	log "github.com/sirupsen/logrus"
	"io"
	"io/fs"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed public/*
var webFiles embed.FS

type ConvServer struct {
	OnCancelAudio func(engine string)
	OnGetWavAudio func(engine string, voice string, text string, rate int32, pitch int32) ([]byte, error)
	OnGetEngines  func() (string, error)
	OnGetVoices   func(engine string) (string, error)

	serveMux *http.ServeMux
	server   *http.Server

	Log *log.Logger

	// ListenHost defaults to loopback. Set it explicitly when a deployment
	// needs LAN access, and set AccessToken for that mode.
	ListenHost    string
	AccessToken   string
	AllowedOrigin string
	MaxTextLength int
	MaxTTSQueue   int

	ttsGateOnce sync.Once
	ttsSlots    chan struct{}
	ttsQueue    chan struct{}
}

type ttsRequest struct {
	engine string
	voice  string
	text   string
	rate   int32
	pitch  int32
}

type ttsResult struct {
	audio []byte
	err   error
}

const (
	metadataTimeout = 15 * time.Second
	ttsTimeout      = 120 * time.Second
	defaultTextMax  = 16 * 1024
	defaultTTSQueue = 8
)

func (c *ConvServer) handleFunc() {
	if c.serveMux == nil {
		c.serveMux = http.NewServeMux()
	}

	webFilesFs, _ := fs.Sub(webFiles, "public")

	c.serveMux.Handle("/", http.FileServer(http.FS(webFilesFs)))

	c.serveMux.Handle("/api/tts", http.TimeoutHandler(http.HandlerFunc(c.ttsAPIHandler), ttsTimeout, "timeout"))
	c.serveMux.Handle("/api/engines", http.TimeoutHandler(http.HandlerFunc(c.enginesApiHandler), metadataTimeout, "timeout"))
	c.serveMux.Handle("/api/voices", http.TimeoutHandler(http.HandlerFunc(c.voicesApiHandler), metadataTimeout, "timeout"))

	c.serveMux.Handle("/api/legado", http.TimeoutHandler(http.HandlerFunc(c.legadoUrlApiHandler), metadataTimeout, "timeout"))
}

func (c *ConvServer) Start(port int64) error {
	if c.Log == nil {
		c.Log = log.New()
	}

	if c.serveMux == nil {
		c.handleFunc()
	}
	host := strings.TrimSpace(c.ListenHost)
	if host == "" {
		host = "127.0.0.1"
	}
	c.server = &http.Server{
		Addr:           net.JoinHostPort(host, strconv.FormatInt(port, 10)),
		ReadTimeout:    10 * time.Second,
		WriteTimeout:   ttsTimeout + 10*time.Second,
		MaxHeaderBytes: 1 << 20,
		Handler:        c.serveMux,
	}

	c.Log.Infoln("\nstarting server...")
	err := c.server.ListenAndServe()
	if err == http.ErrServerClosed { // 说明调用Shutdown关闭
		err = nil
	} else if err != nil {
		return err
	}
	c.Log.Infoln("server closed")
	return nil
}

func (c *ConvServer) Close(timeout time.Duration) error {
	if c.server == nil {
		return nil
	}
	c.log().Infoln("shutting down server...")
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	return c.server.Shutdown(ctx)
}

func (c *ConvServer) ttsAPIHandler(w http.ResponseWriter, r *http.Request) {
	if !c.authorized(r) {
		c.writeErrorStatus(w, http.StatusUnauthorized, "control.invalid_token")
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		w.Header().Set("Allow", "GET, POST")
		c.writeErrorStatus(w, http.StatusMethodNotAllowed, "request.unsupported_protocol")
		return
	}
	request, err := c.parseTTSRequest(w, r)
	if err != nil {
		c.writeErrorStatus(w, http.StatusBadRequest, "request.invalid")
		return
	}
	if c.OnGetWavAudio == nil {
		c.writeErrorStatus(w, http.StatusServiceUnavailable, "provider.unavailable")
		return
	}
	release, ok := c.acquireTTS(r.Context())
	if !ok {
		c.writeErrorStatus(w, http.StatusRequestTimeout, "cancelled")
		return
	}
	defer release()

	c.log().Infof("api/tts engine=%q voice=%q textLength=%d rate=%d pitch=%d",
		request.engine, request.voice, len([]rune(request.text)), request.rate, request.pitch)
	result := make(chan ttsResult, 1)
	go func() {
		defer func() {
			if recovered := recover(); recovered != nil {
				result <- ttsResult{err: errors.New("tts callback panic")}
			}
		}()
		audio, callbackErr := c.OnGetWavAudio(
			request.engine,
			request.voice,
			request.text,
			request.rate,
			request.pitch,
		)
		result <- ttsResult{audio: audio, err: callbackErr}
	}()

	select {
	case response := <-result:
		if response.err != nil {
			c.writeErrorStatus(w, http.StatusBadGateway, "provider.unavailable")
			return
		}
		if err := writeAudioData(w, response.audio); err != nil {
			c.handleError(err)
		}
	case <-r.Context().Done():
		if c.OnCancelAudio != nil {
			c.OnCancelAudio(request.engine)
		}
	}
}

func (c *ConvServer) enginesApiHandler(w http.ResponseWriter, r *http.Request) {
	if !c.authorized(r) {
		c.writeErrorStatus(w, http.StatusUnauthorized, "control.invalid_token")
		return
	}
	c.setCors(w)

	if c.OnGetEngines == nil {
		c.writeErrorMsg(w, errors.New("OnGetEngines() == nil"))
		return
	}

	s, err := c.OnGetEngines()
	if err != nil {
		c.writeErrorMsg(w, err)
		return
	}

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, err = w.Write([]byte(s))
	if err != nil {
		c.Log.Errorln(err)
	}
}

func (c *ConvServer) voicesApiHandler(w http.ResponseWriter, r *http.Request) {
	if !c.authorized(r) {
		c.writeErrorStatus(w, http.StatusUnauthorized, "control.invalid_token")
		return
	}
	c.setCors(w)

	if c.OnGetVoices == nil {
		c.writeErrorMsg(w, errors.New("OnGetVoices() == nil"))
		return
	}

	engine := r.URL.Query().Get("engine")
	c.log().Infof("api/voices engine=%q", engine)

	json, err := c.OnGetVoices(engine)
	if err != nil {
		c.writeErrorMsg(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_, err = w.Write([]byte(json))
	if err != nil {
		c.handleError(err)
		return
	}
}

func (c *ConvServer) legadoUrlApiHandler(w http.ResponseWriter, r *http.Request) {
	if !c.authorized(r) {
		c.writeErrorStatus(w, http.StatusUnauthorized, "control.invalid_token")
		return
	}
	c.setCors(w)

	params := r.URL.Query()
	api := params.Get("api")
	name := params.Get("name")
	engine := params.Get("engine")
	voice := params.Get("voice")
	pitch := params.Get("pitch")

	json, err := getLegadoJson(api, name, engine, voice, pitch)
	if err != nil {
		c.writeErrorMsg(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_, err = w.Write([]byte(json))
	if err != nil {
		c.handleError(err)
		return
	}
}

func writeAudioData(w http.ResponseWriter, data []byte) error {
	w.Header().Set("Content-Type", "audio/x-wav")
	w.Header().Set("Content-Length", strconv.FormatInt(int64(len(data)), 10))
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("Keep-Alive", "timeout=5")
	_, err := w.Write(data)
	return err
}

func (c *ConvServer) writeErrorMsg(w http.ResponseWriter, e error) {
	c.log().Errorf("request failed type=%T", e)
	c.writeErrorStatus(w, http.StatusInternalServerError, "internal_error")
	return
}

func (c *ConvServer) writeErrorStatus(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(status)
	_, err := io.WriteString(w, code)
	if err != nil {
		c.log().Warnf("write error to client failed type=%T", err)
		return
	}
}

func (c *ConvServer) handleError(err error) {
	c.log().Warnf("response write failed type=%T", err)
}

func (c *ConvServer) log() *log.Logger {
	if c.Log == nil {
		c.Log = log.New()
	}
	return c.Log
}

func (c *ConvServer) setCors(w http.ResponseWriter) {
	if c.AllowedOrigin == "" {
		return
	}
	w.Header().Set("Access-Control-Allow-Origin", c.AllowedOrigin)
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-TTS-Token")
	w.Header().Set("Vary", "Origin")
}

func (c *ConvServer) authorized(r *http.Request) bool {
	if c.AccessToken == "" {
		return true
	}
	token := r.Header.Get("X-TTS-Token")
	if token == "" {
		token = strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	}
	return subtle.ConstantTimeCompare([]byte(token), []byte(c.AccessToken)) == 1
}

func (c *ConvServer) parseTTSRequest(_ http.ResponseWriter, r *http.Request) (ttsRequest, error) {
	var request ttsRequest
	var rateText string
	var pitchText string
	if r.Method == http.MethodPost {
		contentType := strings.ToLower(strings.TrimSpace(strings.Split(r.Header.Get("Content-Type"), ";")[0]))
		if contentType == "application/x-www-form-urlencoded" || contentType == "multipart/form-data" {
			if err := r.ParseForm(); err != nil {
				return request, err
			}
			request.engine = r.FormValue("engine")
			request.voice = r.FormValue("voice")
			request.text = r.FormValue("text")
			rateText = r.FormValue("rate")
			pitchText = r.FormValue("pitch")
		} else {
			var payload struct {
				Engine string `json:"engine"`
				Voice  string `json:"voice"`
				Text   string `json:"text"`
				Rate   *int32 `json:"rate"`
				Pitch  *int32 `json:"pitch"`
			}
			decoder := json.NewDecoder(io.LimitReader(r.Body, 64*1024))
			if err := decoder.Decode(&payload); err != nil {
				return request, err
			}
			request.engine, request.voice, request.text = payload.Engine, payload.Voice, payload.Text
			if payload.Rate != nil {
				request.rate = *payload.Rate
			}
			if payload.Pitch != nil {
				request.pitch = *payload.Pitch
			}
		}
	} else {
		params := r.URL.Query()
		request.engine, request.voice, request.text = params.Get("engine"), params.Get("voice"), params.Get("text")
		rateText, pitchText = params.Get("rate"), params.Get("pitch")
	}
	if rateText != "" {
		value, err := strconv.ParseInt(rateText, 10, 32)
		if err != nil {
			return request, err
		}
		request.rate = int32(value)
	}
	if pitchText != "" {
		value, err := strconv.ParseInt(pitchText, 10, 32)
		if err != nil {
			return request, err
		}
		request.pitch = int32(value)
	}
	maxTextLength := c.MaxTextLength
	if maxTextLength <= 0 {
		maxTextLength = defaultTextMax
	}
	if strings.TrimSpace(request.engine) == "" || strings.TrimSpace(request.voice) == "" ||
		strings.TrimSpace(request.text) == "" || len([]rune(request.text)) > maxTextLength ||
		request.rate < 0 || request.rate > 200 || request.pitch < -100 || request.pitch > 100 {
		return request, errors.New("invalid tts request")
	}
	return request, nil
}

func (c *ConvServer) acquireTTS(ctx context.Context) (func(), bool) {
	c.ttsGateOnce.Do(func() {
		queueSize := c.MaxTTSQueue
		if queueSize <= 0 {
			queueSize = defaultTTSQueue
		}
		c.ttsSlots = make(chan struct{}, 1)
		c.ttsQueue = make(chan struct{}, queueSize)
	})
	select {
	case c.ttsQueue <- struct{}{}:
	case <-ctx.Done():
		return func() {}, false
	}
	select {
	case c.ttsSlots <- struct{}{}:
		return func() {
			<-c.ttsSlots
			<-c.ttsQueue
		}, true
	case <-ctx.Done():
		<-c.ttsQueue
		return func() {}, false
	}
}

type EngineInfo struct {
	Name  string `json:"name"`
	Label string `json:"label"`
}

type ErrorResult struct {
}
