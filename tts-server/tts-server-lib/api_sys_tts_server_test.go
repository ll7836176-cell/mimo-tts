package tts_server_lib

import (
	"testing"
)

func TestServerCloseBeforeStartIsSafe(t *testing.T) {
	s := new(SysTtsForwarder)
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
}
