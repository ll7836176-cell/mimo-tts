package tts_server_lib

import (
	log "github.com/sirupsen/logrus"
	"io"
	"os"
	"time"
	"tts-server-lib/sys_tts_server"
	"tts-server-lib/wrapper"
)

type SysTtsForwarderCallback interface {
	Log(level int32, msg string)

	CancelAudio(engine string)
	GetAudio(engine string, voice string, text string, rate int32, pitch int32) (file string, err error)
	GetEngines() (json string, err error)
	GetVoices(engine string) (json string, err error)
}

type SysTtsForwarder struct {
	server   conv_server.ConvServer
	callback SysTtsForwarderCallback
}

func (s *SysTtsForwarder) InitCallback(cb SysTtsForwarderCallback) {
	s.callback = cb
	s.server.Log = log.New()
	s.server.Log.Out = io.Discard
	s.server.Log.Formatter = &wrapper.MyFormatter{
		OnLog: func(level int32, msg string) {
			s.callback.Log(level, msg)
		}}

	s.server.OnGetEngines = func() (string, error) {
		return s.callback.GetEngines()
	}
	s.server.OnGetVoices = func(engine string) (string, error) {
		return s.callback.GetVoices(engine)
	}
	s.server.OnCancelAudio = func(engine string) {
		s.callback.CancelAudio(engine)
	}
	s.server.OnGetWavAudio = func(engine string, voice string, text string, rate int32, pitch int32) ([]byte, error) {
		filePath, err := s.callback.GetAudio(engine, voice, text, rate, pitch)
		if err != nil {
			return nil, err
		}
		audio, err := os.ReadFile(filePath)
		if err != nil {
			return nil, err
		}
		return audio, nil
	}
}

// SetListenHost configures the interface used by the embedded HTTP server.
// The default is loopback; callers should opt in explicitly for LAN access.
func (s *SysTtsForwarder) SetListenHost(host string) {
	s.server.ListenHost = host
}

// SetAccessToken enables bearer/X-TTS-Token authentication for HTTP requests.
func (s *SysTtsForwarder) SetAccessToken(token string) {
	s.server.AccessToken = token
}

// SetAllowedOrigin enables an explicit CORS origin for browser clients.
func (s *SysTtsForwarder) SetAllowedOrigin(origin string) {
	s.server.AllowedOrigin = origin
}

func (s *SysTtsForwarder) Start(port int64) error {
	err := s.server.Start(port)
	if err != nil {
		return err
	}
	return nil
}

func (s *SysTtsForwarder) Close() error {
	return s.server.Close(time.Second * 5)
}
