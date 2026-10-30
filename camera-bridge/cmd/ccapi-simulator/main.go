// Command ccapi-simulator serves an isolated synthetic CCAPI camera for mobile
// integration tests. It is not a dump of a Canon camera or its firmware.
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"image"
	"image/jpeg"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

const root = "/ccapi/ver100/"
const contents = root + "contents"
const card = contents + "/sd"
const folder = card + "/100CANON"

type media struct {
	name, stamp       string
	original, preview []byte
}

type camera struct {
	mu            sync.Mutex
	files         map[string]media
	values        map[string]string
	choices       map[string][]string
	shots         int
	manual        []string
	focus         map[string]int
	live          bool
	frame         []byte
	width, height int
	generation    string
}

func main() {
	dir := flag.String("dir", "", "licensed JPEG fixture directory")
	address := flag.String("addr", "127.0.0.1:18080", "loopback test address")
	flag.Parse()
	host, _, err := net.SplitHostPort(*address)
	if err != nil || net.ParseIP(host) == nil || !net.ParseIP(host).IsLoopback() {
		log.Fatal("synthetic camera must bind a loopback address")
	}
	c := &camera{
		files:      make(map[string]media),
		generation: "initial-synthetic",
		values:     map[string]string{"av": "2.8", "tv": "1/125", "iso": "400", "exposure": "0", "wb": "auto", "afoperation": "one_shot", "drive": "continuous_high"},
		choices: map[string][]string{"av": {"2.8", "4.0", "5.6", "8.0"}, "tv": {"1/60", "1/125", "1/250"}, "iso": {"auto", "100", "400", "800"},
			"exposure": {"-1/3", "0", "+1/3"}, "wb": {"auto", "daylight"}, "afoperation": {"one_shot", "servo"}, "drive": {"single", "continuous_high"}},
	}
	paths, err := filepath.Glob(filepath.Join(*dir, "*.jpg"))
	if err != nil {
		log.Fatal(err)
	}
	for i, filename := range paths {
		original, err := os.ReadFile(filename)
		if err != nil {
			log.Fatal(err)
		}
		picture, err := jpeg.Decode(bytes.NewReader(original))
		if err != nil {
			log.Fatal(err)
		}
		bounds := picture.Bounds()
		width := min(480, bounds.Dx())
		height := max(1, bounds.Dy()*width/bounds.Dx())
		thumb := image.NewRGBA(image.Rect(0, 0, width, height))
		for y := 0; y < height; y++ {
			for x := 0; x < width; x++ {
				thumb.Set(x, y, picture.At(bounds.Min.X+x*bounds.Dx()/width, bounds.Min.Y+y*bounds.Dy()/height))
			}
		}
		var encoded bytes.Buffer
		if err := jpeg.Encode(&encoded, thumb, &jpeg.Options{Quality: 80}); err != nil {
			log.Fatal(err)
		}
		name := fmt.Sprintf("CCAPI_FIXTURE_%04d.JPG", i+1)
		stamp := fmt.Sprintf("2026-10-%02dT12:00:00+08:00", 23+i%3)
		c.files[folder+"/"+name] = media{name, stamp, original, encoded.Bytes()}
		raw := append([]byte{0, 0, 0, 24, 'f', 't', 'y', 'p', 'c', 'r', 'x', ' '}, bytes.Repeat([]byte{byte(i), 0, 255, 128}, 256<<10)...)
		rawName := strings.TrimSuffix(name, ".JPG") + ".CR3"
		c.files[folder+"/"+rawName] = media{rawName, stamp, raw, encoded.Bytes()}
		if len(c.frame) == 0 {
			c.frame, c.width, c.height = encoded.Bytes(), width, height
		}
	}
	if len(c.files) == 0 {
		log.Fatal("no licensed JPEG fixtures")
	}
	server := &http.Server{Addr: *address, Handler: c, ReadHeaderTimeout: 5 * time.Second}
	log.Printf("synthetic CCAPI listening at %s; files=%d; physicalCamera=false", *address, len(c.files))
	log.Fatal(server.ListenAndServe())
}

func (c *camera) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	c.mu.Lock()
	defer c.mu.Unlock()
	reply := func(value any) { w.Header().Set("Content-Type", "application/json"); json.NewEncoder(w).Encode(value) }
	if r.URL.Path == "/__test/reset" && r.Method == http.MethodPost {
		var body struct{ Generation string }
		if json.NewDecoder(r.Body).Decode(&body) != nil || len(body.Generation) < 8 || len(body.Generation) > 64 {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		c.generation = body.Generation
		c.shots, c.manual, c.focus, c.live = 0, nil, nil, false
		c.values["av"], c.values["drive"] = "2.8", "continuous_high"
		for resource, file := range c.files {
			if strings.HasPrefix(file.name, "CCAPI_SHOT_") {
				delete(c.files, resource)
			}
		}
		reply(map[string]any{"synthetic": true})
		return
	}
	if r.URL.Path == "/__stats" {
		checks := make(map[string]string)
		for _, file := range c.files {
			sum := sha256.Sum256(file.original)
			checks[file.name] = hex.EncodeToString(sum[:])
		}
		reply(map[string]any{"synthetic": true, "shots": c.shots, "manual": c.manual, "focus": c.focus, "drive": c.values["drive"], "files": checks})
		return
	}
	switch r.URL.Path {
	case "/ccapi":
		paths := []map[string]any{{"path": root + "deviceinformation", "get": true}, {"path": contents, "get": true},
			{"path": root + "shooting/liveview", "post": true}, {"path": root + "shooting/liveview/flip", "get": true},
			{"path": root + "shooting/liveview/flipdetail", "get": true}, {"path": root + "shooting/liveview/afframeposition", "put": true},
			{"path": root + "shooting/control/shutterbutton", "post": true}, {"path": root + "shooting/control/shutterbutton/manual", "post": true}}
		for name := range c.values {
			paths = append(paths, map[string]any{"path": root + "shooting/settings/" + name, "get": true, "put": true})
		}
		reply(map[string]any{"ver100": paths})
	case root + "deviceinformation":
		reply(map[string]any{"manufacturer": "Canon", "productname": "Canon EOS 850D", "serialnumber": "synthetic-framelink-850d-" + c.generation, "firmwareversion": "SIMULATION-NOT-FIRMWARE"})
	case contents:
		reply(map[string]any{"path": []string{card}})
	case card:
		reply(map[string]any{"path": []string{folder}})
	case folder:
		if r.URL.Query().Get("kind") == "number" {
			reply(map[string]any{"pagenumber": (len(c.files) + 99) / 100})
			return
		}
		page, _ := strconv.Atoi(r.URL.Query().Get("page"))
		if page < 1 {
			page = 1
		}
		paths := make([]string, 0, len(c.files))
		for path := range c.files {
			paths = append(paths, path)
		}
		slices.Sort(paths)
		start := min((page-1)*100, len(paths))
		until := min(start+100, len(paths))
		reply(map[string]any{"path": paths[start:until]})
	case root + "shooting/liveview":
		var body struct{ Liveviewsize string }
		json.NewDecoder(r.Body).Decode(&body)
		c.live = body.Liveviewsize != "off"
		reply(map[string]any{})
	case root + "shooting/liveview/flip":
		if !c.live {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "image/jpeg")
		w.Write(c.frame)
	case root + "shooting/liveview/flipdetail":
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Write([]byte{0, 0, 0, 8, 0, 0, 0, 0})
		json.NewEncoder(w).Encode(map[string]any{"liveviewdata": map[string]any{"image": map[string]int{"positionx": 0, "positiony": 0, "positionwidth": c.width * 10, "positionheight": c.height * 10}}})
	case root + "shooting/liveview/afframeposition":
		if r.Method != http.MethodPut {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		json.NewDecoder(r.Body).Decode(&c.focus)
		reply(map[string]any{})
	case root + "shooting/control/shutterbutton/manual":
		var body struct{ Action string }
		json.NewDecoder(r.Body).Decode(&body)
		if body.Action != "half_press" && body.Action != "release" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		c.manual = append(c.manual, body.Action)
		reply(map[string]any{})
	case root + "shooting/control/shutterbutton":
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		c.shots++
		name := fmt.Sprintf("CCAPI_SHOT_%04d.JPG", c.shots)
		c.files[folder+"/"+name] = media{name, "2026-10-30T12:00:00+08:00", c.frame, c.frame}
		reply(map[string]any{})
	default:
		if strings.HasPrefix(r.URL.Path, root+"shooting/settings/") {
			name := strings.TrimPrefix(r.URL.Path, root+"shooting/settings/")
			if _, exists := c.values[name]; !exists {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			if r.Method == http.MethodPut {
				var body struct{ Value string }
				json.NewDecoder(r.Body).Decode(&body)
				if !slices.Contains(c.choices[name], body.Value) {
					w.WriteHeader(http.StatusBadRequest)
					return
				}
				c.values[name] = body.Value
			}
			reply(map[string]any{"value": c.values[name], "ability": c.choices[name]})
			return
		}
		file, exists := c.files[r.URL.Path]
		if !exists {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		switch r.URL.Query().Get("kind") {
		case "info":
			reply(map[string]any{"filesize": len(file.original), "lastmodifieddate": file.stamp})
		case "thumbnail", "display":
			w.Header().Set("Content-Type", "image/jpeg")
			w.Write(file.preview)
		default:
			w.Header().Set("Content-Type", "application/octet-stream")
			w.Write(file.original)
		}
	}
}
