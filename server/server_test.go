package main

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

func setup(t *testing.T) (*handler, string, string) {
	t.Helper()
	dir := t.TempDir()
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	privB64 := base64.RawURLEncoding.EncodeToString(priv.Bytes())
	pubB64 := base64.RawURLEncoding.EncodeToString(priv.PublicKey().Bytes())
	xray := `{"inbounds":[
	  {"tag":"socks","port":1080,"protocol":"socks"},
	  {"tag":"vless-reality-8443","port":8443,"protocol":"vless",
	   "settings":{"clients":[{"id":"11111111-2222-3333-4444-555555555555","flow":"xtls-rprx-vision"}]},
	   "streamSettings":{"security":"reality","realitySettings":{"privateKey":"` + privB64 + `","serverNames":["www.cloudflare.com"],"shortIds":["abcd"]}}}]}`
	xrayPath := filepath.Join(dir, "xray.json")
	if err := os.WriteFile(xrayPath, []byte(xray), 0o600); err != nil {
		t.Fatal(err)
	}
	cfgPath := filepath.Join(dir, "server.json")
	if err := cmdInit(cfgPath, []string{"-host", "203.0.113.7", "-xray", xrayPath}); err != nil {
		t.Fatal(err)
	}
	c, _ := loadConfig(cfgPath)
	hash, _ := hashPassword("correct horse")
	c.Users = []User{{Name: "family", PasswordHash: hash}}
	if err := saveConfig(cfgPath, c); err != nil {
		t.Fatal(err)
	}
	return &handler{configPath: cfgPath, limiter: newLimiter()}, cfgPath, pubB64
}

func doLogin(h *handler, user, pass string) *httptest.ResponseRecorder {
	body, _ := json.Marshal(map[string]string{"username": user, "password": pass})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/login", strings.NewReader(string(body)))
	req.RemoteAddr = "198.51.100.1:5555"
	rec := httptest.NewRecorder()
	h.login(rec, req)
	return rec
}

func doProfile(h *handler, token string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, "/api/v1/profile", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()
	h.profile(rec, req)
	return rec
}

func TestLoginAndProfile(t *testing.T) {
	h, cfgPath, pub := setup(t)

	rec := doLogin(h, "family", "correct horse")
	if rec.Code != 200 {
		t.Fatalf("login: %d %s", rec.Code, rec.Body)
	}
	var resp struct {
		Token   string  `json:"token"`
		Profile profile `json:"profile"`
	}
	json.Unmarshal(rec.Body.Bytes(), &resp)
	s := resp.Profile.Servers[0]
	if s.Host != "203.0.113.7" || s.Port != 8443 || s.PublicKey != pub || s.ShortID != "abcd" ||
		s.ServerName != "www.cloudflare.com" || s.Flow != "xtls-rprx-vision" || s.Fingerprint != "chrome" {
		t.Fatalf("unexpected server params: %+v", s)
	}

	if rec := doProfile(h, resp.Token); rec.Code != 200 {
		t.Fatalf("profile with token: %d", rec.Code)
	}
	if rec := doProfile(h, resp.Token+"x"); rec.Code != 401 {
		t.Fatalf("tampered token accepted: %d", rec.Code)
	}

	// Changing the password must invalidate existing tokens.
	c, _ := loadConfig(cfgPath)
	c.Users[0].PasswordHash, _ = hashPassword("new password!")
	saveConfig(cfgPath, c)
	if rec := doProfile(h, resp.Token); rec.Code != 401 {
		t.Fatalf("token survived password change: %d", rec.Code)
	}
}

func TestLockout(t *testing.T) {
	h, _, _ := setup(t)
	for i := 0; i < maxFails; i++ {
		if rec := doLogin(h, "family", "wrong"); rec.Code != 401 {
			t.Fatalf("attempt %d: %d", i, rec.Code)
		}
	}
	if rec := doLogin(h, "family", "correct horse"); rec.Code != 429 {
		t.Fatalf("expected lockout, got %d", rec.Code)
	}
}

func TestLegacyTokenMaxAge(t *testing.T) {
	h, cfgPath, _ := setup(t)
	c, _ := loadConfig(cfgPath)
	old := issueToken(c.TokenSecret, &c.Users[0], time.Now().Add(-3*24*time.Hour))
	fresh := issueToken(c.TokenSecret, &c.Users[0], time.Now())
	if doProfile(h, old).Code != 200 {
		t.Fatal("tokens must not expire by default")
	}
	c.LegacyTokenMaxAgeDays = 2
	saveConfig(cfgPath, c)
	if doProfile(h, old).Code != 401 {
		t.Fatal("expired token accepted")
	}
	if doProfile(h, fresh).Code != 200 {
		t.Fatal("fresh token rejected")
	}
}

// jsonShape reduces a decoded JSON value to its keys and value types.
func jsonShape(v any) any {
	switch x := v.(type) {
	case map[string]any:
		m := map[string]any{}
		for k, e := range x {
			m[k] = jsonShape(e)
		}
		return m
	case []any:
		if len(x) == 0 {
			return []any{}
		}
		return []any{jsonShape(x[0])}
	case float64:
		return "number"
	case string:
		return "string"
	case bool:
		return "bool"
	}
	return "null"
}

// The Android app's tests parse the same file (ProfileContractTest), so a field
// renamed on one side only fails one of the two test suites.
func TestProfileMatchesContract(t *testing.T) {
	data, err := os.ReadFile("testdata/profile.json")
	if err != nil {
		t.Fatal(err)
	}
	var want any
	if err := json.Unmarshal(data, &want); err != nil {
		t.Fatal(err)
	}
	h, _, _ := setup(t)
	rec := doLogin(h, "family", "correct horse")
	var resp struct {
		Profile any `json:"profile"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if got, want := jsonShape(resp.Profile), jsonShape(want); !reflect.DeepEqual(got, want) {
		t.Fatalf("profile shape changed:\n got  %v\n want %v\nupdate server/testdata/profile.json and the app's Profile.kt together", got, want)
	}
}
