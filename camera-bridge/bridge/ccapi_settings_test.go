package bridge

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestCCAPIShutterConversionDoesNotRoundShortExposures(t *testing.T) {
	for raw, expected := range map[string]int64{"1/2000": 1<<16 | 2000, "0.0005": 1<<16 | 2000, "1.3": 13<<16 | 10, "bulb": 0} {
		value, valid := ccapiShutter(raw)
		if !valid || value != expected {
			t.Fatalf("%s -> %d, valid=%v", raw, value, valid)
		}
	}
	for _, raw := range []string{"1/80000", "NaN", "0", "-1", "unsupported"} {
		if _, valid := ccapiShutter(raw); valid {
			t.Fatalf("accepted unsupported shutter %q", raw)
		}
	}
}

func TestCCAPIParameterWriteKeepsNativeJSONTypeAndReadsBack(t *testing.T) {
	for _, numeric := range []bool{false, true} {
		t.Run(map[bool]string{false: "string", true: "number"}[numeric], func(t *testing.T) {
			var writes atomic.Int32
			current := json.RawMessage("\"2.8\"")
			ability := json.RawMessage("[\"2.8\",\"4.0\"]")
			expected := "\"4.0\""
			if numeric {
				current, ability, expected = json.RawMessage("2.8"), json.RawMessage("[2.8,4.0]"), "4.0"
			}
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method == http.MethodPut {
					var body map[string]json.RawMessage
					if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
						t.Error(err)
					}
					if string(body["value"]) != expected {
						t.Error("parameter JSON type or original value changed", string(body["value"]))
					}
					current = body["value"]
					writes.Add(1)
					w.Write([]byte("{}"))
					return
				}
				json.NewEncoder(w).Encode(map[string]json.RawMessage{"value": current, "ability": ability})
			}))
			defer server.Close()
			client := ccapiTestClient(t, server, "", "")
			resources := ccapiResources{"GET:shooting/settings/av": "/ccapi/ver100/shooting/settings/av", "PUT:shooting/settings/av": "/ccapi/ver100/shooting/settings/av"}
			if err := setCCAPISetting(context.Background(), client, resources, ccapiSettings[0], 400); err != nil {
				t.Fatal(err)
			}
			if writes.Load() != 1 {
				t.Fatal("parameter was sent more than once")
			}
			if err := setCCAPISetting(context.Background(), client, resources, ccapiSettings[0], 999); err == nil {
				t.Fatal("unsupported parameter value was written")
			}
			if writes.Load() != 1 {
				t.Fatal("invalid choice reached the camera")
			}
		})
	}
}

func TestCCAPIParameterReadbackMismatchDoesNotRetryWrite(t *testing.T) {
	var writes atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPut {
			writes.Add(1)
			w.Write([]byte("{}"))
		} else {
			w.Write([]byte("{\"value\":\"2.8\",\"ability\":[\"2.8\",\"4.0\"]}"))
		}
	}))
	defer server.Close()
	resources := ccapiResources{"GET:shooting/settings/av": "/ccapi/ver100/shooting/settings/av", "PUT:shooting/settings/av": "/ccapi/ver100/shooting/settings/av"}
	err := setCCAPISetting(context.Background(), ccapiTestClient(t, server, "", ""), resources, ccapiSettings[0], 400)
	if err == nil || !strings.Contains(err.Error(), "尚未确认") || writes.Load() != 1 {
		t.Fatalf("readback result %v writes=%d", err, writes.Load())
	}
}
