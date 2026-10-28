package bridge

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

type ftpProfile struct {
	Host     string `json:"host"`
	Port     int    `json:"port"`
	User     string `json:"user"`
	Password string `json:"password"`
	TLS      bool   `json:"tls"`
}

// FTPClient supplies native iOS FTP/explicit-FTPS without loading original files into Swift memory.
type FTPClient struct {
	op        sync.Mutex
	mu        sync.Mutex
	conn      net.Conn
	data      net.Conn
	reader    *bufio.Reader
	profile   ftpProfile
	tlsConfig *tls.Config
}

func NewFTPClient() *FTPClient { return &FTPClient{} }

func safeFTP(value string) error {
	if strings.ContainsAny(value, "\r\n\x00") {
		return errors.New("名称包含无效控制字符")
	}
	return nil
}

func ftpReadLine(reader *bufio.Reader, maximum int) (string, error) {
	var result []byte
	for {
		part, more, err := reader.ReadLine()
		if len(result)+len(part) > maximum {
			return "", errors.New("FTP回复或命令过长")
		}
		result = append(result, part...)
		if err != nil {
			return "", err
		}
		if !more {
			return string(result), nil
		}
	}
}

func (c *FTPClient) Connect(raw string) error {
	c.op.Lock()
	defer c.op.Unlock()
	c.Close()
	var config ftpProfile
	if err := json.Unmarshal([]byte(raw), &config); err != nil {
		return err
	}
	if config.Port == 0 {
		config.Port = 21
	}
	if config.Host == "" || strings.ContainsAny(config.Host, "/\\ \r\n\x00") || config.Port < 1 || config.Port > 65535 {
		return errors.New("FTP服务器地址或端口无效")
	}
	if err := safeFTP(config.User); err != nil {
		return err
	}
	if err := safeFTP(config.Password); err != nil {
		return err
	}
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(config.Host, strconv.Itoa(config.Port)), 10*time.Second)
	if err != nil {
		return err
	}
	c.mu.Lock()
	c.conn, c.reader, c.profile = conn, bufio.NewReaderSize(conn, 64<<10), config
	c.mu.Unlock()
	fail := func(err error) error { c.Close(); return err }
	if _, _, err = c.reply(2); err != nil {
		return fail(err)
	}
	if config.TLS {
		if _, err = c.command("AUTH TLS", 2); err != nil {
			return fail(err)
		}
		tlsConfig := &tls.Config{ServerName: config.Host, MinVersion: tls.VersionTLS12, ClientSessionCache: tls.NewLRUClientSessionCache(4)}
		secure := tls.Client(conn, tlsConfig)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		err = secure.HandshakeContext(ctx)
		cancel()
		if err != nil {
			return fail(err)
		}
		c.mu.Lock()
		c.conn, c.reader, c.tlsConfig = secure, bufio.NewReaderSize(secure, 64<<10), tlsConfig
		c.mu.Unlock()
	}
	code, _, err := c.send("USER " + config.User)
	if err != nil {
		return fail(err)
	}
	if code == 331 {
		if _, err = c.command("PASS "+config.Password, 2); err != nil {
			return fail(errors.New("FTP登录失败，请检查账号和密码"))
		}
	} else if code/100 != 2 {
		return fail(errors.New("FTP服务器未接受此账号"))
	}
	if config.TLS {
		if _, err = c.command("PBSZ 0", 2); err != nil {
			return fail(err)
		}
		if _, err = c.command("PROT P", 2); err != nil {
			return fail(err)
		}
	}
	if _, err = c.command("TYPE I", 2); err != nil {
		return fail(err)
	}
	_, _, _ = c.send("OPTS UTF8 ON")
	return nil
}

func (c *FTPClient) send(command string) (int, string, error) {
	if err := safeFTP(command); err != nil {
		return 0, "", err
	}
	c.mu.Lock()
	conn := c.conn
	c.mu.Unlock()
	if conn == nil {
		return 0, "", errors.New("FTP连接已关闭")
	}
	conn.SetDeadline(time.Now().Add(30 * time.Second))
	if _, err := io.WriteString(conn, command+"\r\n"); err != nil {
		return 0, "", err
	}
	return c.reply(0)
}

func (c *FTPClient) reply(expected int) (int, string, error) {
	c.mu.Lock()
	reader, conn := c.reader, c.conn
	c.mu.Unlock()
	if reader == nil || conn == nil {
		return 0, "", errors.New("FTP连接已关闭")
	}
	conn.SetReadDeadline(time.Now().Add(30 * time.Second))
	line, err := ftpReadLine(reader, 65536)
	if err != nil {
		return 0, "", err
	}
	if len(line) < 3 || len(line) > 65536 {
		return 0, "", errors.New("FTP回复格式无效")
	}
	code, err := strconv.Atoi(line[:3])
	if err != nil {
		return 0, "", err
	}
	if len(line) > 3 && line[3] == '-' {
		for count := 0; count < 128; count++ {
			part, readErr := ftpReadLine(reader, 65536)
			if readErr != nil {
				return 0, "", readErr
			}
			if len(part) > 65536 {
				return 0, "", errors.New("FTP回复过长")
			}
			if strings.HasPrefix(part, line[:3]+" ") {
				break
			}
			if count == 127 {
				return 0, "", errors.New("FTP多行回复没有结束")
			}
		}
	}
	if expected != 0 && code/100 != expected {
		return code, line, fmt.Errorf("FTP操作未完成（%d）", code)
	}
	return code, strings.TrimSpace(line), nil
}

func (c *FTPClient) command(command string, expected int) (string, error) {
	code, text, err := c.send(command)
	if err == nil && code/100 != expected {
		err = fmt.Errorf("FTP操作未完成（%d）", code)
	}
	return text, err
}

func (c *FTPClient) passive(command string) (net.Conn, error) {
	text, err := c.command("EPSV", 2)
	port := 0
	if err == nil {
		inner := strings.Split(strings.TrimSuffix(strings.SplitN(text, "(", 2)[len(strings.SplitN(text, "(", 2))-1], ")"), "|")
		if len(inner) >= 4 {
			port, _ = strconv.Atoi(inner[len(inner)-2])
		}
	}
	if port == 0 {
		text, err = c.command("PASV", 2)
		if err != nil {
			return nil, err
		}
		start, end := strings.Index(text, "("), strings.Index(text, ")")
		if start < 0 || end <= start {
			return nil, errors.New("FTP被动端口无效")
		}
		parts := strings.Split(text[start+1:end], ",")
		if len(parts) != 6 {
			return nil, errors.New("FTP被动端口无效")
		}
		high, e1 := strconv.Atoi(strings.TrimSpace(parts[4]))
		low, e2 := strconv.Atoi(strings.TrimSpace(parts[5]))
		if e1 != nil || e2 != nil || high < 0 || high > 255 || low < 0 || low > 255 {
			return nil, errors.New("FTP被动端口无效")
		}
		port = high*256 + low
	}
	if port < 1 || port > 65535 {
		return nil, errors.New("FTP被动端口无效")
	}
	data, err := net.DialTimeout("tcp", net.JoinHostPort(c.profile.Host, strconv.Itoa(port)), 10*time.Second)
	if err != nil {
		return nil, err
	}
	c.mu.Lock()
	c.data = data
	c.mu.Unlock()
	if _, err = c.command(command, 1); err != nil {
		data.Close()
		return nil, err
	}
	c.mu.Lock()
	tlsConfig := c.tlsConfig
	c.mu.Unlock()
	if tlsConfig != nil {
		secure := tls.Client(data, tlsConfig)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		err = secure.HandshakeContext(ctx)
		cancel()
		if err != nil {
			data.Close()
			return nil, err
		}
		data = secure
		c.mu.Lock()
		c.data = secure
		c.mu.Unlock()
	}
	return data, nil
}

type ftpEntry struct {
	Name      string `json:"name"`
	Path      string `json:"path"`
	Directory bool   `json:"directory"`
	Size      int64  `json:"size"`
}

var ftpUnixListing = regexp.MustCompile(`^([d-][^ ]*)\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\S+\s+\d+\s+\S+\s+(.*)$`)
var ftpDOSListing = regexp.MustCompile(`^\d{2}-\d{2}-\d{2,4}\s+\d{2}:\d{2}[AP]M\s+(<DIR>|\d+)\s+(.*)$`)

func ftpLegacyEntry(line string) (ftpEntry, bool) {
	if fields := ftpUnixListing.FindStringSubmatch(line); fields != nil {
		size, err := strconv.ParseInt(fields[2], 10, 64)
		return ftpEntry{Name: fields[3], Directory: fields[1][0] == 'd', Size: size}, err == nil
	}
	if fields := ftpDOSListing.FindStringSubmatch(line); fields != nil {
		directory := fields[1] == "<DIR>"
		size, err := strconv.ParseInt(fields[1], 10, 64)
		return ftpEntry{Name: fields[2], Directory: directory, Size: size}, directory || err == nil
	}
	return ftpEntry{}, false
}

func (c *FTPClient) list(directory string) ([]ftpEntry, error) {
	if err := safeFTP(directory); err != nil {
		return nil, err
	}
	data, err := c.passive("MLSD " + directory)
	legacy := false
	if err != nil {
		data, err = c.passive("LIST " + directory)
		legacy = true
	}
	if err != nil {
		return nil, err
	}
	defer data.Close()
	data.SetDeadline(time.Now().Add(30 * time.Second))
	scanner := bufio.NewScanner(data)
	scanner.Buffer(make([]byte, 4096), 1<<20)
	entries := []ftpEntry{}
	for scanner.Scan() {
		line := scanner.Text()
		entry := ftpEntry{}
		if !legacy {
			parts := strings.SplitN(line, " ", 2)
			if len(parts) != 2 {
				continue
			}
			entry.Name = parts[1]
			for _, fact := range strings.Split(parts[0], ";") {
				kv := strings.SplitN(fact, "=", 2)
				if len(kv) != 2 {
					continue
				}
				switch strings.ToLower(kv[0]) {
				case "type":
					entry.Directory = kv[1] == "dir"
					if kv[1] == "cdir" || kv[1] == "pdir" {
						entry.Name = ""
					}
				case "size":
					entry.Size, _ = strconv.ParseInt(kv[1], 10, 64)
				}
			}
		} else {
			var parsed bool
			entry, parsed = ftpLegacyEntry(line)
			if !parsed {
				if strings.TrimSpace(line) != "" && !strings.HasPrefix(line, "total ") {
					c.Close()
					return nil, errors.New("服务器目录格式无法识别，请使用支持MLSD的FTP服务器")
				}
				continue
			}
		}
		if entry.Name != "" && entry.Name != "." && entry.Name != ".." && !strings.ContainsAny(entry.Name, "/\\") && safeFTP(entry.Name) == nil {
			entry.Path = path.Join(directory, entry.Name)
			entries = append(entries, entry)
			if len(entries) > 10000 {
				c.Close()
				return nil, errors.New("目录超过一万项，请进入子目录后浏览")
			}
		}
	}
	if err = scanner.Err(); err != nil {
		return nil, err
	}
	data.Close()
	if _, _, err = c.reply(2); err != nil {
		return nil, err
	}
	return entries, nil
}

func (c *FTPClient) List(directory string) (string, error) {
	c.op.Lock()
	defer c.op.Unlock()
	entries, err := c.list(directory)
	if err != nil {
		return "", err
	}
	return marshal(entries)
}
func (c *FTPClient) MakeDirectory(directory string) error {
	c.op.Lock()
	defer c.op.Unlock()
	_, err := c.command("MKD "+directory, 2)
	return err
}

func (c *FTPClient) Download(remote, target string, expectedSize int64, progress Progress) (string, error) {
	c.op.Lock()
	defer c.op.Unlock()
	if err := safeFTP(remote); err != nil {
		return "", err
	}
	if !filepath.IsAbs(target) {
		return "", errors.New("下载路径必须位于App文件目录")
	}
	if _, err := os.Stat(target); err == nil {
		return "", errors.New("本机目标已存在，未覆盖")
	}
	if err := os.MkdirAll(filepath.Dir(target), 0700); err != nil {
		return "", err
	}
	data, err := c.passive("RETR " + remote)
	if err != nil {
		return "", err
	}
	defer data.Close()
	file, err := os.OpenFile(target+".part", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return "", err
	}
	hash := sha256.New()
	buffer := make([]byte, 256<<10)
	var count int64
	var last time.Time
	for {
		data.SetReadDeadline(time.Now().Add(30 * time.Second))
		n, readErr := data.Read(buffer)
		if n > 0 {
			if _, err = file.Write(buffer[:n]); err != nil {
				break
			}
			hash.Write(buffer[:n])
			count += int64(n)
			if progress != nil && time.Since(last) > 300*time.Millisecond {
				progress.OnProgress(count, 0)
				last = time.Now()
			}
		}
		if readErr != nil {
			if readErr != io.EOF {
				err = readErr
			}
			break
		}
	}
	if err == nil {
		err = file.Sync()
	}
	file.Close()
	data.Close()
	if err == nil {
		_, _, err = c.reply(2)
	}
	if err == nil && expectedSize >= 0 && count != expectedSize {
		err = errors.New("下载长度与目录信息不一致，请刷新后重试")
	}
	if err != nil {
		return "", err
	}
	if err = os.Rename(target+".part", target); err != nil {
		return "", err
	}
	if folder, openErr := os.Open(filepath.Dir(target)); openErr == nil {
		folder.Sync()
		folder.Close()
	}
	if progress != nil {
		progress.OnProgress(count, count)
	}
	return marshal(map[string]any{"path": target, "size": count, "sha256": hex.EncodeToString(hash.Sum(nil))})
}

func (c *FTPClient) Upload(remote, source string, progress Progress) error {
	c.op.Lock()
	defer c.op.Unlock()
	if err := safeFTP(remote); err != nil {
		return err
	}
	entries, err := c.list(path.Dir(remote))
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if entry.Name == path.Base(remote) {
			return errors.New("远程文件已存在，未覆盖")
		}
	}
	file, err := os.Open(source)
	if err != nil {
		return err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return err
	}
	temporary := path.Join(path.Dir(remote), ".framelink-"+strconv.FormatInt(time.Now().UnixNano(), 36)+".part")
	data, err := c.passive("STOR " + temporary)
	if err != nil {
		return err
	}
	buffer := make([]byte, 256<<10)
	var count int64
	var last time.Time
	for {
		n, readErr := file.Read(buffer)
		if n > 0 {
			data.SetWriteDeadline(time.Now().Add(30 * time.Second))
			if _, err = data.Write(buffer[:n]); err != nil {
				break
			}
			count += int64(n)
			if progress != nil && time.Since(last) > 300*time.Millisecond {
				progress.OnProgress(count, info.Size())
				last = time.Now()
			}
		}
		if readErr != nil {
			if readErr != io.EOF {
				err = readErr
			}
			break
		}
	}
	data.Close()
	if err != nil {
		return err
	}
	if count != info.Size() {
		return errors.New("上传期间本机文件长度变化，未提交远程文件")
	}
	if _, _, err = c.reply(2); err != nil {
		return err
	}
	entries, err = c.list(path.Dir(remote))
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if entry.Name == path.Base(remote) {
			return errors.New("上传期间出现同名文件，未替换")
		}
	}
	if _, err = c.command("RNFR "+temporary, 3); err != nil {
		return err
	}
	if _, err = c.command("RNTO "+remote, 2); err != nil {
		return err
	}
	if progress != nil {
		progress.OnProgress(count, count)
	}
	return nil
}

func (c *FTPClient) Close() {
	c.mu.Lock()
	conn, data := c.conn, c.data
	c.conn, c.data, c.reader, c.tlsConfig = nil, nil, nil, nil
	c.mu.Unlock()
	if data != nil {
		data.Close()
	}
	if conn != nil {
		conn.Close()
	}
}
