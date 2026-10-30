package bridge

import (
	"bytes"
	"context"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"hash"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

const ccapiJSONLimit = 4 << 20

type ccapiHTTPError struct {
	status  int
	message string
}

func (e *ccapiHTTPError) Error() string { return e.message }

// ccapiHTTP keeps camera requests on the supplied local-network dialer.
// Redirects and off-camera resource URLs are rejected before credentials are attached.
type ccapiHTTP struct {
	base       *url.URL
	client     *http.Client
	user       string
	password   string
	authMu     sync.Mutex
	challenge  map[string]string
	authType   string
	nonceCount uint32
}

func newCCAPIHTTP(host string, port int, user, password string, dial func(context.Context, string, string) (net.Conn, error)) (*ccapiHTTP, error) {
	if net.ParseIP(host) == nil || port < 1 || port > 65535 {
		return nil, errors.New("佳能相机地址或端口无效")
	}
	if dial == nil {
		dial = (&net.Dialer{Timeout: 10 * time.Second}).DialContext
	}
	transport := &http.Transport{
		DialContext: dial, MaxConnsPerHost: 2, MaxIdleConnsPerHost: 2,
		ResponseHeaderTimeout: 15 * time.Second, IdleConnTimeout: 30 * time.Second,
		DisableCompression: true,
	}
	return &ccapiHTTP{
		base: &url.URL{Scheme: "http", Host: net.JoinHostPort(host, strconv.Itoa(port))},
		user: user, password: password,
		client: &http.Client{Transport: transport, CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return errors.New("相机资源发生重定向，请核对机身地址")
		}},
	}, nil
}

func (c *ccapiHTTP) resource(path string) (*url.URL, error) {
	u, err := url.Parse(path)
	if err != nil || u.User != nil || u.Fragment != "" {
		return nil, errors.New("相机返回了无效资源地址")
	}
	u = c.base.ResolveReference(u)
	if u.Scheme != c.base.Scheme || u.Host != c.base.Host || (u.Path != "/ccapi" && !strings.HasPrefix(u.Path, "/ccapi/")) {
		return nil, errors.New("相机资源不属于当前连接")
	}
	// Percent-encoded traversal must not be reinterpreted by the HTTP server.
	for _, part := range strings.Split(u.Path, "/") {
		if part == "." || part == ".." {
			return nil, errors.New("相机资源路径无效")
		}
	}
	return u, nil
}

func (c *ccapiHTTP) request(ctx context.Context, method, path string, payload []byte) (*http.Response, error) {
	u, err := c.resource(path)
	if err != nil {
		return nil, err
	}
	// The second attempt is only an explicit 401 challenge. Network failures and
	// ambiguous shutter responses are never retried here.
	for attempt := 0; attempt < 2; attempt++ {
		req, err := http.NewRequestWithContext(ctx, method, u.String(), bytes.NewReader(payload))
		if err != nil {
			return nil, err
		}
		if len(payload) > 0 {
			req.Header.Set("Content-Type", "application/json")
		}
		if err = c.authorize(req); err != nil {
			return nil, err
		}
		response, err := c.client.Do(req)
		if err != nil {
			return nil, err
		}
		if response.StatusCode == http.StatusUnauthorized && attempt == 0 && c.user != "" {
			challenges := response.Header.Values("WWW-Authenticate")
			response.Body.Close()
			if err = c.acceptChallenge(challenges); err != nil {
				return nil, err
			}
			continue
		}
		if response.StatusCode < 200 || response.StatusCode >= 300 {
			response.Body.Close()
			switch response.StatusCode {
			case http.StatusUnauthorized, http.StatusForbidden:
				return nil, &ccapiHTTPError{response.StatusCode, "相机CCAPI认证失败，请核对机身账号和密码"}
			case http.StatusNotFound, http.StatusMethodNotAllowed:
				return nil, &ccapiHTTPError{response.StatusCode, "当前机身未提供此CCAPI操作"}
			case http.StatusServiceUnavailable, http.StatusConflict:
				return nil, &ccapiHTTPError{response.StatusCode, "相机正在处理，请稍后再操作"}
			default:
				return nil, &ccapiHTTPError{response.StatusCode, fmt.Sprintf("相机CCAPI返回HTTP %d", response.StatusCode)}
			}
		}
		return response, nil
	}
	return nil, errors.New("相机CCAPI认证未完成")
}

func (c *ccapiHTTP) json(ctx context.Context, method, path string, input, output any) error {
	var payload []byte
	var err error
	if input != nil {
		payload, err = json.Marshal(input)
		if err != nil {
			return err
		}
	}
	response, err := c.request(ctx, method, path, payload)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, ccapiJSONLimit+1))
	if err != nil {
		return err
	}
	if len(body) > ccapiJSONLimit {
		return errors.New("相机元数据超过单次读取范围")
	}
	if output == nil {
		return nil
	}
	if err = json.Unmarshal(body, output); err != nil {
		return errors.New("相机返回的CCAPI元数据无效")
	}
	return nil
}

func (c *ccapiHTTP) close() { c.client.CloseIdleConnections() }

func (c *ccapiHTTP) acceptChallenge(headers []string) error {
	var basic bool
	for _, header := range headers {
		scheme, data, _ := strings.Cut(strings.TrimSpace(header), " ")
		if strings.EqualFold(scheme, "Basic") {
			basic = true
			continue
		}
		if !strings.EqualFold(scheme, "Digest") {
			continue
		}
		fields, err := digestFields(data)
		if err != nil {
			return err
		}
		if _, err = digestHash(fields["algorithm"]); err != nil {
			return err
		}
		if fields["nonce"] == "" || fields["realm"] == "" {
			return errors.New("相机认证挑战缺少身份信息")
		}
		if fields["qop"] != "" && !containsAuth(fields["qop"]) {
			return errors.New("相机认证方式暂不支持")
		}
		c.authMu.Lock()
		c.authType, c.challenge, c.nonceCount = "Digest", fields, 0
		c.authMu.Unlock()
		return nil
	}
	if basic {
		c.authMu.Lock()
		c.authType = "Basic"
		c.authMu.Unlock()
		return nil
	}
	return errors.New("相机未提供受支持的CCAPI认证方式")
}

func (c *ccapiHTTP) authorize(req *http.Request) error {
	c.authMu.Lock()
	defer c.authMu.Unlock()
	if c.authType == "" {
		return nil
	}
	if c.authType == "Basic" {
		req.SetBasicAuth(c.user, c.password)
		return nil
	}
	var random [16]byte
	if _, err := rand.Read(random[:]); err != nil {
		return err
	}
	cnonce := hex.EncodeToString(random[:])
	c.nonceCount++
	nc := fmt.Sprintf("%08x", c.nonceCount)
	digest, err := ccapiDigest(c.user, c.password, req.Method, req.URL.RequestURI(), cnonce, nc, c.challenge)
	if err != nil {
		return err
	}
	fields := []string{
		"username=" + strconv.Quote(c.user), "realm=" + strconv.Quote(c.challenge["realm"]),
		"nonce=" + strconv.Quote(c.challenge["nonce"]), "uri=" + strconv.Quote(req.URL.RequestURI()),
		"response=" + strconv.Quote(digest),
	}
	if algorithm := c.challenge["algorithm"]; algorithm != "" {
		fields = append(fields, "algorithm="+algorithm)
	}
	if opaque := c.challenge["opaque"]; opaque != "" {
		fields = append(fields, "opaque="+strconv.Quote(opaque))
	}
	if c.challenge["qop"] != "" {
		fields = append(fields, "qop=auth", "nc="+nc, "cnonce="+strconv.Quote(cnonce))
	} else if strings.HasSuffix(strings.ToUpper(c.challenge["algorithm"]), "-SESS") {
		fields = append(fields, "cnonce="+strconv.Quote(cnonce))
	}
	req.Header.Set("Authorization", "Digest "+strings.Join(fields, ", "))
	return nil
}

func containsAuth(qop string) bool {
	for _, value := range strings.Split(qop, ",") {
		if strings.EqualFold(strings.TrimSpace(value), "auth") {
			return true
		}
	}
	return false
}

func digestHash(algorithm string) (func() hash.Hash, error) {
	switch strings.ToUpper(algorithm) {
	case "", "MD5", "MD5-SESS":
		return md5.New, nil
	case "SHA-256", "SHA-256-SESS":
		return sha256.New, nil
	default:
		return nil, errors.New("相机摘要认证算法暂不支持")
	}
}

func ccapiDigest(user, password, method, uri, cnonce, nc string, fields map[string]string) (string, error) {
	newHash, err := digestHash(fields["algorithm"])
	if err != nil {
		return "", err
	}
	sum := func(value string) string {
		h := newHash()
		h.Write([]byte(value))
		return hex.EncodeToString(h.Sum(nil))
	}
	a1 := sum(user + ":" + fields["realm"] + ":" + password)
	if strings.HasSuffix(strings.ToUpper(fields["algorithm"]), "-SESS") {
		a1 = sum(a1 + ":" + fields["nonce"] + ":" + cnonce)
	}
	a2 := sum(method + ":" + uri)
	if fields["qop"] != "" {
		return sum(a1 + ":" + fields["nonce"] + ":" + nc + ":" + cnonce + ":auth:" + a2), nil
	}
	return sum(a1 + ":" + fields["nonce"] + ":" + a2), nil
}

// digestFields parses quoted commas and escapes without logging challenge contents.
func digestFields(value string) (map[string]string, error) {
	if len(value) > 8192 {
		return nil, errors.New("相机认证挑战长度无效")
	}
	fields := make(map[string]string)
	for value != "" {
		value = strings.TrimLeft(value, " \t,")
		if value == "" {
			break
		}
		key, rest, ok := strings.Cut(value, "=")
		if !ok || strings.TrimSpace(key) == "" {
			return nil, errors.New("相机认证挑战格式无效")
		}
		value = strings.TrimSpace(rest)
		var item string
		if strings.HasPrefix(value, "\"") {
			var text strings.Builder
			closed := false
			for i := 1; i < len(value); i++ {
				switch value[i] {
				case '\\':
					i++
					if i >= len(value) {
						return nil, errors.New("相机认证挑战转义无效")
					}
					text.WriteByte(value[i])
				case '"':
					item, value, closed = text.String(), value[i+1:], true
				default:
					text.WriteByte(value[i])
				}
				if closed {
					break
				}
			}
			if !closed {
				return nil, errors.New("相机认证挑战缺少结束引号")
			}
			if strings.TrimSpace(value) != "" && !strings.HasPrefix(strings.TrimSpace(value), ",") {
				return nil, errors.New("相机认证挑战分隔无效")
			}
		} else {
			item, value, _ = strings.Cut(value, ",")
			item = strings.TrimSpace(item)
		}
		key = strings.ToLower(strings.TrimSpace(key))
		if _, exists := fields[key]; exists {
			return nil, errors.New("相机认证挑战存在重复字段")
		}
		fields[key] = item
	}
	return fields, nil
}
