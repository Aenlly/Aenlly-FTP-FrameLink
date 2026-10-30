package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func ccapiTestClient(t *testing.T, server *httptest.Server, user, password string) *ccapiHTTP {
	t.Helper()
	host, portText, err := net.SplitHostPort(strings.TrimPrefix(server.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	port, _ := strconv.Atoi(portText)
	client, err := newCCAPIHTTP(host, port, user, password, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(client.close)
	return client
}

func TestCCAPIDigestRFC2617Vector(t *testing.T) {
	fields := map[string]string{"realm": "testrealm@host.com", "nonce": "dcd98b7102dd2f0e8b11d0f600bfb0c093", "qop": "auth", "algorithm": "MD5"}
	got, err := ccapiDigest("Mufasa", "Circle Of Life", "GET", "/dir/index.html", "0a4f113b", "00000001", fields)
	if err != nil || got != "6629fae49393a05397450978507c4ef1" {
		t.Fatalf("RFC digest %q: %v", got, err)
	}
	for _, algorithm := range []string{"SHA-512", "MD5\r\nX-Test: yes"} {
		if _, err = digestHash(algorithm); err == nil {
			t.Fatal("accepted unsupported digest algorithm")
		}
	}
}

func TestCCAPIChallengeHandlesQuotedCommasAndRejectsMalformedFields(t *testing.T) {
	fields, err := digestFields("realm=\"Camera, local\", nonce=\"ab\\\"cd\", qop=\"auth,auth-int\", algorithm=SHA-256")
	if err != nil || fields["realm"] != "Camera, local" || fields["nonce"] != "ab\"cd" || !containsAuth(fields["qop"]) {
		t.Fatalf("challenge: %v %v", fields, err)
	}
	for _, input := range []string{"nonce=\"unfinished", "nonce=one,nonce=two", "nonce=\"ok\"extra", "invalid"} {
		if _, err := digestFields(input); err == nil {
			t.Fatalf("accepted malformed challenge %q", input)
		}
	}
}

func TestCCAPIAuthenticationAndOriginalResponseBytes(t *testing.T) {
	original := []byte{0, 1, 255, 128, 0, 13, 10, 42}
	var requests atomic.Int32
	var authorised atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		header := r.Header.Get("Authorization")
		if header == "" {
			w.Header().Set("WWW-Authenticate", "Digest realm=\"Camera\", nonce=\"test-nonce\", qop=\"auth,auth-int\", algorithm=SHA-256")
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		fields, err := digestFields(strings.TrimPrefix(header, "Digest "))
		if err != nil {
			t.Error(err)
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		want, _ := ccapiDigest("photographer", "local-secret", r.Method, r.URL.RequestURI(), fields["cnonce"], fields["nc"], fields)
		if fields["username"] != "photographer" || fields["response"] != want || fields["qop"] != "auth" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		authorised.Add(1)
		w.Write(original)
	}))
	defer server.Close()
	client := ccapiTestClient(t, server, "photographer", "local-secret")
	for range 2 {
		response, err := client.request(context.Background(), http.MethodGet, "/ccapi/ver100/contents/sd/100CANON/IMG_0001.CR3", nil)
		if err != nil {
			t.Fatal(err)
		}
		body, err := io.ReadAll(response.Body)
		response.Body.Close()
		if err != nil || string(body) != string(original) {
			t.Fatal("original response bytes changed", err)
		}
	}
	if requests.Load() != 3 || authorised.Load() != 2 {
		t.Fatalf("requests=%d authorised=%d", requests.Load(), authorised.Load())
	}
}

func TestCCAPIUsesNetworkDialerAndRejectsOffCameraResources(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		w.Write([]byte("{}"))
	}))
	defer server.Close()
	client := ccapiTestClient(t, server, "", "")
	var dials atomic.Int32
	dialer := &net.Dialer{}
	client.client.Transport.(*http.Transport).DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
		dials.Add(1)
		return dialer.DialContext(ctx, network, address)
	}
	var output map[string]any
	if err := client.json(context.Background(), http.MethodGet, "/ccapi", nil, &output); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"http://192.0.2.10:8080/ccapi/ver100/contents", "/private", "/ccapi/%2e%2e/private", "/ccapi/ver100/deviceinformation#fragment", "http://user:password@" + client.base.Host + "/ccapi"} {
		if _, err := client.request(context.Background(), http.MethodGet, path, nil); err == nil {
			t.Fatalf("accepted foreign resource %q", path)
		}
	}
	if requests.Load() != 1 || dials.Load() != 1 {
		t.Fatalf("network binding was bypassed: requests=%d dials=%d", requests.Load(), dials.Load())
	}
}

func TestCCAPIRedirectNeverForwardsCredentials(t *testing.T) {
	var foreign atomic.Int32
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { foreign.Add(1) }))
	defer other.Close()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL+"/ccapi", http.StatusFound)
	}))
	defer server.Close()
	client := ccapiTestClient(t, server, "user", "secret")
	client.authType = "Basic"
	if _, err := client.request(context.Background(), http.MethodGet, "/ccapi", nil); err == nil {
		t.Fatal("redirect was followed")
	}
	if foreign.Load() != 0 {
		t.Fatal("credentials or request leaked to another server")
	}
}

func TestCCAPIShutterTimeoutIsNotRetried(t *testing.T) {
	var count atomic.Int32
	received := make(chan struct{}, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		count.Add(1)
		io.Copy(io.Discard, r.Body)
		received <- struct{}{}
		<-r.Context().Done()
	}))
	defer server.Close()
	client := ccapiTestClient(t, server, "", "")
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		_, err := client.request(ctx, http.MethodPost, "/ccapi/ver100/shooting/control/shutterbutton", []byte("{}"))
		done <- err
	}()
	select {
	case <-received:
	case <-time.After(3 * time.Second):
		t.Fatal("shutter request did not arrive")
	}
	if err := <-done; !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("cancelled shutter %v", err)
	}
	if count.Load() != 1 {
		t.Fatal("ambiguous shutter was sent more than once")
	}
}

func TestCCAPIBasicChallengePreservesPostAndStopsAfterInvalidPassword(t *testing.T) {
	for _, correct := range []bool{false, true} {
		t.Run(strconv.FormatBool(correct), func(t *testing.T) {
			var attempts, actions atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				attempts.Add(1)
				body, _ := io.ReadAll(r.Body)
				user, password, ok := r.BasicAuth()
				if !ok || user != "camera-user" || password != "camera-password" {
					w.Header().Set("WWW-Authenticate", "Basic realm=\"Camera\"")
					w.WriteHeader(http.StatusUnauthorized)
					return
				}
				if r.Method != http.MethodPost || string(body) != "{\"af\":true}" {
					t.Error("authentication changed the command")
				}
				actions.Add(1)
				w.Write([]byte("{}"))
			}))
			defer server.Close()
			password := "wrong-password"
			if correct {
				password = "camera-password"
			}
			client := ccapiTestClient(t, server, "camera-user", password)
			err := client.json(context.Background(), http.MethodPost, "/ccapi/ver100/shooting/control/shutterbutton", map[string]bool{"af": true}, nil)
			if (err == nil) != correct || attempts.Load() != 2 {
				t.Fatalf("authentication result err=%v attempts=%d", err, attempts.Load())
			}
			expected := int32(0)
			if correct {
				expected = 1
			}
			if actions.Load() != expected {
				t.Fatal("authenticated action count changed", actions.Load())
			}
		})
	}
}

func TestCCAPIDiscoversResourcesPerMethodAndVersion(t *testing.T) {
	response := map[string]any{
		"ver100": []map[string]any{
			{"path": "/ccapi/ver100/deviceinformation", "get": true},
			{"path": "/ccapi/ver100/contents", "get": true},
			{"path": "/ccapi/ver100/shooting/settings/av", "get": true, "put": true},
		},
		"ver110": []map[string]any{
			{"path": "/ccapi/ver110/contents", "get": true},
			{"path": "/ccapi/ver110/shooting/settings/av", "get": true, "put": false},
		},
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { json.NewEncoder(w).Encode(response) }))
	defer server.Close()
	resources, err := discoverCCAPI(context.Background(), ccapiTestClient(t, server, "", ""))
	if err != nil {
		t.Fatal(err)
	}
	for key, want := range map[string]string{
		"GET:deviceinformation":    "/ccapi/ver100/deviceinformation",
		"GET:contents":             "/ccapi/ver110/contents",
		"GET:shooting/settings/av": "/ccapi/ver110/shooting/settings/av",
		"PUT:shooting/settings/av": "/ccapi/ver100/shooting/settings/av",
	} {
		if resources[key] != want {
			t.Fatalf("%s = %s, want %s", key, resources[key], want)
		}
	}
	if _, err := resources.path(http.MethodPost, "shooting/control/shutterbutton"); err == nil {
		t.Fatal("unadvertised shutter was enabled")
	}
}

func TestCCAPIDiscoveryRejectsForeignAndUnlistedAPIs(t *testing.T) {
	for _, value := range []string{
		"{\"value\":\"No list of APIs\"}",
		"{\"ver100\":[{\"path\":\"http://192.0.2.10:8080/ccapi/ver100/contents\",\"get\":true}]}",
		"{\"ver100\":[{\"path\":\"/ccapi/ver110/contents\",\"get\":true}]}",
	} {
		t.Run(fmt.Sprintf("%x", value[:12]), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(value)) }))
			defer server.Close()
			if _, err := discoverCCAPI(context.Background(), ccapiTestClient(t, server, "", "")); err == nil {
				t.Fatal("accepted invalid discovery")
			}
		})
	}
}
