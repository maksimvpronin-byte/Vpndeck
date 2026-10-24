package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

const testDeviceA = "00000000-0000-4000-8000-000000000001"
const testDeviceB = "00000000-0000-4000-8000-000000000002"

func managedSetup(t *testing.T) (*handler, string, *Config) {
	t.Helper()
	h, path, _ := setup(t)
	c, err := loadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	c.ManageDevices = true
	c.XrayRestartCommand = []string{"/usr/bin/true"}
	if err := saveConfig(path, c); err != nil {
		t.Fatal(err)
	}
	return h, path, c
}

func register(h *handler, token, id string) *httptest.ResponseRecorder {
	body, _ := json.Marshal(map[string]string{"device_id": id, "device_name": "Test phone"})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/device", strings.NewReader(string(body)))
	req.Header.Set("Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()
	h.registerDevice(rec, req)
	return rec
}

func responseToken(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	if rec.Code != 200 {
		t.Fatalf("unexpected status: %d", rec.Code)
	}
	var result struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(result.Token, "v2.") {
		t.Fatal("expected device-bound token")
	}
	return result.Token
}

func TestDeviceMigrationAndRevocation(t *testing.T) {
	h, path, c := managedSetup(t)
	legacyA := issueToken(c.TokenSecret, &c.Users[0], time.Now())
	legacyB := issueToken(c.TokenSecret, &c.Users[0], time.Now().Add(-time.Second))
	tokenA := responseToken(t, register(h, legacyA, testDeviceA))
	tokenB := responseToken(t, register(h, legacyB, testDeviceB))
	c, _ = loadConfig(path)
	if len(c.Devices) != 2 || c.Devices[0].UUID == c.Devices[1].UUID {
		t.Fatal("devices must have distinct credentials")
	}
	for _, token := range []string{tokenA, tokenB, legacyA} {
		if doProfile(h, token).Code != 200 {
			t.Fatal("active token rejected")
		}
	}
	if register(h, legacyA, "00000000-0000-4000-8000-000000000003").Code != 401 {
		t.Fatal("consumed token registered another device")
	}
	if err := cmdDevices(path, []string{"revoke", testDeviceA}); err != nil {
		t.Fatal(err)
	}
	if doProfile(h, tokenA).Code != 401 || doProfile(h, legacyA).Code != 401 {
		t.Fatal("revoked device retained API access")
	}
	if doProfile(h, tokenB).Code != 200 {
		t.Fatal("revocation affected another device")
	}
	data, err := os.ReadFile(c.XrayConfig)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), c.Devices[0].UUID) {
		t.Fatal("revoked UUID remains accepted by Xray")
	}
	if !strings.Contains(string(data), c.Devices[1].UUID) {
		t.Fatal("other device's UUID was removed")
	}
	if !strings.Contains(string(data), "11111111-2222-3333-4444-555555555555") {
		t.Fatal("legacy phones must keep working during migration")
	}
	if register(h, legacyA, testDeviceA).Code != 401 {
		t.Fatal("revoked device reactivated")
	}
}

func TestDeviceRegistrationIsIdempotentAndSerialized(t *testing.T) {
	h, path, c := managedSetup(t)
	token := issueToken(c.TokenSecret, &c.Users[0], time.Now())
	var wg sync.WaitGroup
	results := make(chan int, 6)
	for range 6 {
		wg.Add(1)
		go func() { defer wg.Done(); results <- register(h, token, testDeviceA).Code }()
	}
	wg.Wait()
	close(results)
	for code := range results {
		if code != 200 {
			t.Fatalf("concurrent registration status: %d", code)
		}
	}
	c, err := loadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(c.Devices) != 1 {
		t.Fatal("duplicate devices created")
	}
	data, _ := os.ReadFile(c.XrayConfig)
	if strings.Count(string(data), "vpndeck-device-"+testDeviceA) != 1 {
		t.Fatal("duplicate Xray credentials")
	}
}

func TestDeviceChangeRollback(t *testing.T) {
	h, path, c := managedSetup(t)
	before, _ := os.ReadFile(c.XrayConfig)
	c.XrayRestartCommand = []string{"/usr/bin/false"}
	saveConfig(path, c)
	legacy := issueToken(c.TokenSecret, &c.Users[0], time.Now())
	if register(h, legacy, testDeviceA).Code != 503 {
		t.Fatal("failed restart must not issue credentials")
	}
	after, _ := os.ReadFile(c.XrayConfig)
	if string(before) != string(after) {
		t.Fatal("Xray config was not restored")
	}
	c, _ = loadConfig(path)
	if len(c.Devices) != 0 {
		t.Fatal("failed device persisted")
	}
}

func TestDeviceTokenPasswordChangeAndMissingCredential(t *testing.T) {
	h, path, c := managedSetup(t)
	token := responseToken(t, register(h, issueToken(c.TokenSecret, &c.Users[0], time.Now()), testDeviceA))
	c, _ = loadConfig(path)
	data, _ := os.ReadFile(c.XrayConfig)
	// External removal must never give this device the shared fallback credential.
	data = []byte(strings.ReplaceAll(string(data), "vpndeck-device-"+testDeviceA, "removed"))
	os.WriteFile(c.XrayConfig, data, 0o600)
	if doProfile(h, token).Code == 200 {
		t.Fatal("missing device fell back to shared credential")
	}
	c.Users[0].PasswordHash = "changed"
	saveConfig(path, c)
	if doProfile(h, token).Code != 401 {
		t.Fatal("device token survived password change")
	}
}

func TestOldServerModeRemainsCompatible(t *testing.T) {
	h, _, _ := setup(t)
	if register(h, "anything", testDeviceA).Code != 404 {
		t.Fatal("disabled device management must advertise legacy compatibility")
	}
	if doLogin(h, "family", "correct horse").Code != 200 {
		t.Fatal("legacy login broken")
	}
}

func TestDeviceLoginAndTamperedToken(t *testing.T) {
	h, path, _ := managedSetup(t)
	login := func(id string) *httptest.ResponseRecorder {
		body, _ := json.Marshal(map[string]string{"username": "family", "password": "correct horse", "device_id": id, "device_name": "Phone"})
		req := httptest.NewRequest(http.MethodPost, "/api/v1/login", strings.NewReader(string(body)))
		rec := httptest.NewRecorder()
		h.login(rec, req)
		return rec
	}
	token := responseToken(t, login(testDeviceA))
	if doProfile(h, token+"x").Code != 401 {
		t.Fatal("tampered token accepted")
	}
	if login("invalid").Code != 400 {
		t.Fatal("invalid device ID accepted")
	}
	if err := cmdDevices(path, []string{"revoke", testDeviceA}); err != nil {
		t.Fatal(err)
	}
	if login(testDeviceA).Code != 401 {
		t.Fatal("password login silently reactivated revoked device")
	}
}
