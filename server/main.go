// vpndeck-server hands VLESS+Reality connection parameters to the VpnDeck
// Android app after a username/password login.
//
//	vpndeck-server [-config /etc/vpndeck/server.json] init -host <public ip> -xray <xray config>
//	vpndeck-server [-config ...] passwd <user>        (password read from stdin)
//	vpndeck-server [-config ...] fingerprint
//	vpndeck-server [-config ...] serve
package main

import (
	"bufio"
	"crypto/tls"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const profileRefreshSeconds = 6 * 60 * 60

func main() {
	configPath := flag.String("config", "/etc/vpndeck/server.json", "path to server config")
	flag.Parse()
	args := flag.Args()
	if len(args) == 0 {
		args = []string{"serve"}
	}
	var err error
	switch args[0] {
	case "init":
		err = cmdInit(*configPath, args[1:])
	case "passwd":
		err = cmdPasswd(*configPath, args[1:])
	case "fingerprint":
		err = cmdFingerprint(*configPath)
	case "check":
		err = cmdCheck(*configPath)
	case "serve":
		err = cmdServe(*configPath)
	default:
		err = fmt.Errorf("unknown command %q", args[0])
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func cmdInit(path string, args []string) error {
	fs := flag.NewFlagSet("init", flag.ExitOnError)
	host := fs.String("host", "", "public IP or hostname of this server")
	xray := fs.String("xray", "", "path to the Xray config with the VLESS+Reality inbound")
	tag := fs.String("tag", "", "inbound tag (default: first VLESS+Reality inbound)")
	listen := fs.String("listen", ":8448", "listen address for the API")
	fs.Parse(args)
	if *host == "" || *xray == "" {
		return errors.New("init needs -host and -xray")
	}
	if _, err := os.Stat(path); err == nil {
		return fmt.Errorf("%s already exists; edit it instead", path)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	c := &Config{
		Listen:      *listen,
		PublicHost:  *host,
		XrayConfig:  *xray,
		InboundTag:  *tag,
		Fingerprint: "chrome",
		TokenSecret: randomHex(32),
	}
	if err := saveConfig(path, c); err != nil {
		return err
	}
	c, err := loadConfig(path)
	if err != nil {
		return err
	}
	if _, err := readVlessServer(c, ""); err != nil {
		return fmt.Errorf("config written, but xray config is not usable: %w", err)
	}
	if err := ensureCert(c); err != nil {
		return err
	}
	fmt.Println("wrote", path)
	return nil
}

func cmdPasswd(path string, args []string) error {
	if len(args) != 1 || args[0] == "" {
		return errors.New("usage: passwd <user>  (password on stdin)")
	}
	c, err := loadConfig(path)
	if err != nil {
		return err
	}
	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil && line == "" {
		return errors.New("no password on stdin")
	}
	password := strings.TrimRight(line, "\r\n")
	if len(password) < 8 {
		return errors.New("password must be at least 8 characters")
	}
	hash, err := hashPassword(password)
	if err != nil {
		return err
	}
	if u := c.user(args[0]); u != nil {
		u.PasswordHash = hash
		u.Disabled = false
	} else {
		c.Users = append(c.Users, User{Name: args[0], PasswordHash: hash})
	}
	if err := saveConfig(path, c); err != nil {
		return err
	}
	fmt.Printf("password set for %q; existing sessions of this user are now logged out\n", args[0])
	return nil
}

func cmdFingerprint(path string) error {
	c, err := loadConfig(path)
	if err != nil {
		return err
	}
	fp, err := spkiFingerprint(c.TLSCert)
	if err != nil {
		return err
	}
	fmt.Println(fp)
	return nil
}

func cmdCheck(path string) error {
	c, err := loadConfig(path)
	if err != nil {
		return err
	}
	s, err := readVlessServer(c, "")
	if err != nil {
		return err
	}
	fmt.Printf("ok: inbound %q -> %s:%d sni=%s flow=%s users=%d\n", s.Tag, s.Host, s.Port, s.ServerName, s.Flow, len(c.Users))
	return nil
}

func cmdServe(path string) error {
	c, err := loadConfig(path)
	if err != nil {
		return err
	}
	if err := ensureCert(c); err != nil {
		return err
	}
	cert, err := tls.LoadX509KeyPair(c.TLSCert, c.TLSKey)
	if err != nil {
		return err
	}
	fp, _ := spkiFingerprint(c.TLSCert)
	h := &handler{configPath: path, limiter: newLimiter()}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/v1/login", h.login)
	mux.HandleFunc("GET /api/v1/profile", h.profile)
	srv := &http.Server{
		Addr:              c.Listen,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       20 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 << 10,
		TLSConfig: &tls.Config{
			MinVersion:   tls.VersionTLS13,
			Certificates: []tls.Certificate{cert},
		},
		ErrorLog: log.New(os.Stderr, "http: ", 0),
	}
	log.Printf("listening on %s, certificate %s", c.Listen, fp)
	return srv.ListenAndServeTLS("", "")
}

type handler struct {
	configPath string
	limiter    *limiter
}

type profile struct {
	Version         int           `json:"version"`
	Account         string        `json:"account"`
	Servers         []VlessServer `json:"servers"`
	RefreshInterval int           `json:"refresh_interval"`
}

func (h *handler) login(w http.ResponseWriter, r *http.Request) {
	ip := clientIP(r)
	now := time.Now()
	if h.limiter.locked(ip, now) {
		writeError(w, http.StatusTooManyRequests, "too_many_attempts")
		return
	}
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request")
		return
	}
	c, err := loadConfig(h.configPath)
	if err != nil {
		log.Printf("load config: %v", err)
		writeError(w, http.StatusInternalServerError, "server_error")
		return
	}
	u := c.user(strings.TrimSpace(req.Username))
	ok := u != nil && !u.Disabled && checkPassword(u.PasswordHash, req.Password)
	if !ok {
		if u == nil {
			// Burn the same time as a real check so usernames cannot be probed.
			checkPassword(dummyHash(), req.Password)
		}
		h.limiter.fail(ip, now)
		log.Printf("login failed: user=%q ip=%s", req.Username, ip)
		writeError(w, http.StatusUnauthorized, "invalid_credentials")
		return
	}
	h.limiter.success(ip)
	p, err := buildProfile(c, u)
	if err != nil {
		log.Printf("build profile: %v", err)
		writeError(w, http.StatusInternalServerError, "server_error")
		return
	}
	log.Printf("login ok: user=%q ip=%s", u.Name, ip)
	writeJSON(w, http.StatusOK, map[string]any{
		"token":   issueToken(c.TokenSecret, u, now),
		"profile": p,
	})
}

func (h *handler) profile(w http.ResponseWriter, r *http.Request) {
	token, found := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !found {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	c, err := loadConfig(h.configPath)
	if err != nil {
		log.Printf("load config: %v", err)
		writeError(w, http.StatusInternalServerError, "server_error")
		return
	}
	u, err := verifyToken(c, token)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	p, err := buildProfile(c, u)
	if err != nil {
		log.Printf("build profile: %v", err)
		writeError(w, http.StatusInternalServerError, "server_error")
		return
	}
	writeJSON(w, http.StatusOK, p)
}

func buildProfile(c *Config, u *User) (*profile, error) {
	s, err := readVlessServer(c, u.Name)
	if err != nil {
		return nil, err
	}
	return &profile{Version: 1, Account: u.Name, Servers: []VlessServer{*s}, RefreshInterval: profileRefreshSeconds}, nil
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}

// dummyHash is a real hash of a random password, used to make failed logins
// for unknown users cost as much as failed logins for known ones.
var dummyHash = sync.OnceValue(func() string {
	h, err := hashPassword(randomHex(16))
	if err != nil {
		panic(err)
	}
	return h
})
