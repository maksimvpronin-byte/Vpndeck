package main

import (
	"encoding/json"
	"fmt"
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

// editXrayClients rewrites the clients of the VLESS inbound the way an admin would by hand.
func editXrayClients(t *testing.T, path string, edit func([]any) []any) {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var root map[string]any
	if err := json.Unmarshal(data, &root); err != nil {
		t.Fatal(err)
	}
	for _, raw := range root["inbounds"].([]any) {
		in := raw.(map[string]any)
		if in["protocol"] == "vless" {
			settings := in["settings"].(map[string]any)
			settings["clients"] = edit(settings["clients"].([]any))
		}
	}
	out, _ := json.Marshal(root)
	if err := os.WriteFile(path, out, 0o600); err != nil {
		t.Fatal(err)
	}
}

func profileUUID(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	if rec.Code != 200 {
		t.Fatalf("profile: %d %s", rec.Code, rec.Body)
	}
	var p profile
	if err := json.Unmarshal(rec.Body.Bytes(), &p); err != nil {
		t.Fatal(err)
	}
	return p.Servers[0].UUID
}

func TestSharedCredentialIsNeverADeviceOne(t *testing.T) {
	const shared = "11111111-2222-3333-4444-555555555555"
	h, path, c := managedSetup(t)
	deviceToken := responseToken(t, register(h, issueToken(c.TokenSecret, &c.Users[0], time.Now()), testDeviceA))
	oldApp := issueToken(c.TokenSecret, &c.Users[0], time.Now().Add(-time.Minute))
	c, _ = loadConfig(path)
	deviceUUID := c.Devices[0].UUID

	// The device credential ends up first in the list.
	editXrayClients(t, c.XrayConfig, func(clients []any) []any {
		return append(clients[1:], clients[0])
	})
	if got := profileUUID(t, doProfile(h, oldApp)); got != shared {
		t.Fatalf("old app got %s, want the shared credential", got)
	}

	// The shared credential is removed altogether: old apps get nothing, not a phone's UUID.
	editXrayClients(t, c.XrayConfig, func(clients []any) []any {
		var kept []any
		for _, cl := range clients {
			if cl.(map[string]any)["id"] != shared {
				kept = append(kept, cl)
			}
		}
		return kept
	})
	if rec := doProfile(h, oldApp); rec.Code == 200 {
		t.Fatalf("old app got a device credential: %s", rec.Body)
	}
	if got := profileUUID(t, doProfile(h, deviceToken)); got != deviceUUID {
		t.Fatalf("device got %s, want its own %s", got, deviceUUID)
	}
	// New devices still register: the flow comes from the remaining clients.
	responseToken(t, register(h, issueToken(c.TokenSecret, &c.Users[0], time.Now().Add(-2*time.Minute)), testDeviceB))
}

func deviceLogin(h *handler, user, pass, id string) *httptest.ResponseRecorder {
	body, _ := json.Marshal(map[string]string{"username": user, "password": pass, "device_id": id, "device_name": "Phone"})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/login", strings.NewReader(string(body)))
	rec := httptest.NewRecorder()
	h.login(rec, req)
	return rec
}

func errorCode(rec *httptest.ResponseRecorder) string {
	var e struct {
		Error string `json:"error"`
	}
	json.Unmarshal(rec.Body.Bytes(), &e)
	return e.Error
}

func TestDeviceRefusalsAreExplained(t *testing.T) {
	h, path, c := managedSetup(t)
	hash, _ := hashPassword("kids password")
	c.Users = append(c.Users, User{Name: "kids", PasswordHash: hash})
	if err := saveConfig(path, c); err != nil {
		t.Fatal(err)
	}
	tokenA := responseToken(t, deviceLogin(h, "family", "correct horse", testDeviceA))

	// Same phone, another account: the right password must not look wrong.
	if rec := deviceLogin(h, "kids", "kids password", testDeviceA); rec.Code != 401 || errorCode(rec) != "device_other_account" {
		t.Fatalf("other account: %d %s", rec.Code, rec.Body)
	}
	responseToken(t, deviceLogin(h, "kids", "kids password", testDeviceB))
	if rec := deviceLogin(h, "kids", "wrong", testDeviceB); errorCode(rec) != "invalid_credentials" {
		t.Fatalf("wrong password: %s", rec.Body)
	}

	if err := cmdDevices(path, []string{"revoke", testDeviceA}); err != nil {
		t.Fatal(err)
	}
	if rec := doProfile(h, tokenA); rec.Code != 401 || errorCode(rec) != "device_revoked" {
		t.Fatalf("revoked profile: %d %s", rec.Code, rec.Body)
	}
	if rec := doProfile(h, tokenA+"x"); errorCode(rec) != "unauthorized" {
		t.Fatalf("a forged token must not learn the device state: %s", rec.Body)
	}
	if rec := deviceLogin(h, "family", "correct horse", testDeviceA); rec.Code != 401 || errorCode(rec) != "device_revoked" {
		t.Fatalf("revoked login: %d %s", rec.Code, rec.Body)
	}

	c, _ = loadConfig(path)
	oldUUID := c.device(testDeviceA).UUID
	if err := cmdDevices(path, []string{"restore", testDeviceA}); err != nil {
		t.Fatal(err)
	}
	if doProfile(h, tokenA).Code != 401 {
		t.Fatal("restore revived a token bound to the revoked credential")
	}
	token := responseToken(t, deviceLogin(h, "family", "correct horse", testDeviceA))
	if got := profileUUID(t, doProfile(h, token)); got == oldUUID {
		t.Fatal("restored device kept its revoked credential")
	}
	data, _ := os.ReadFile(c.XrayConfig)
	if strings.Contains(string(data), oldUUID) {
		t.Fatal("revoked UUID is back in Xray")
	}
}

func TestDeviceRestoreKeepsLegacyTokensRevoked(t *testing.T) {
	for _, oldConfig := range []bool{false, true} {
		t.Run(fmt.Sprintf("old_config=%t", oldConfig), func(t *testing.T) {
			h, path, c := managedSetup(t)
			legacy := issueToken(c.TokenSecret, &c.Users[0], time.Now())
			oldToken := responseToken(t, register(h, legacy, testDeviceA))
			if err := cmdDevices(path, []string{"revoke", testDeviceA}); err != nil {
				t.Fatal(err)
			}
			if oldConfig {
				// A device revoked by an older server has no persistent token tombstone.
				c, err := loadConfig(path)
				if err != nil {
					t.Fatal(err)
				}
				c.device(testDeviceA).LegacyTokenRevoked = false
				if err := saveConfig(path, c); err != nil {
					t.Fatal(err)
				}
			}
			if rec := doProfile(h, legacy); rec.Code != 401 {
				t.Fatalf("revoked migration token: %d %s", rec.Code, rec.Body)
			}
			if err := cmdDevices(path, []string{"restore", testDeviceA}); err != nil {
				t.Fatal(err)
			}
			for _, token := range []string{legacy, oldToken} {
				if rec := doProfile(h, token); rec.Code != 401 {
					t.Fatalf("restore revived an old token: %d", rec.Code)
				}
			}
			for _, id := range []string{testDeviceA, testDeviceB} {
				if rec := register(h, legacy, id); rec.Code != 401 {
					t.Fatalf("old migration token registered device %s: %d", id, rec.Code)
				}
			}
			token := responseToken(t, deviceLogin(h, "family", "correct horse", testDeviceA))
			if rec := doProfile(h, token); rec.Code != 200 {
				t.Fatalf("fresh login rejected: %d", rec.Code)
			}
			if rec := doProfile(h, legacy); rec.Code != 401 {
				t.Fatalf("fresh login revived the old migration token: %d", rec.Code)
			}
		})
	}
}

func TestDeviceManagementWithNoRemainingClients(t *testing.T) {
	for _, operation := range []string{"restore", "login", "migrate"} {
		t.Run(operation, func(t *testing.T) {
			h, path, c := managedSetup(t)
			responseToken(t, deviceLogin(h, "family", "correct horse", testDeviceA))
			// Remove the shared credential, then revoke the only remaining device.
			editXrayClients(t, c.XrayConfig, func(clients []any) []any {
				kept := make([]any, 0)
				for _, raw := range clients {
					cl := raw.(map[string]any)
					email, _ := cl["email"].(string)
					if strings.HasPrefix(email, deviceEmailPrefix) {
						kept = append(kept, raw)
					}
				}
				return kept
			})
			if err := cmdDevices(path, []string{"revoke", testDeviceA}); err != nil {
				t.Fatal(err)
			}
			var token string
			switch operation {
			case "restore":
				if err := cmdDevices(path, []string{"restore", testDeviceA}); err != nil {
					t.Fatalf("restore with no remaining clients: %v", err)
				}
				token = responseToken(t, deviceLogin(h, "family", "correct horse", testDeviceA))
			case "login":
				token = responseToken(t, deviceLogin(h, "family", "correct horse", testDeviceB))
			case "migrate":
				legacy := issueToken(c.TokenSecret, &c.Users[0], time.Now())
				token = responseToken(t, register(h, legacy, testDeviceB))
			}
			if got := profileUUID(t, doProfile(h, token)); got == "" {
				t.Fatal("device has no credential")
			}
			if rec := doLogin(h, "family", "correct horse"); rec.Code == 200 {
				t.Fatal("device credential was shared with a legacy login")
			}
		})
	}
}

func TestRevokedDevicesDoNotCountTowardsLimit(t *testing.T) {
	h, path, c := managedSetup(t)
	fill := func(disabled bool) {
		c.Devices = nil
		for i := range maxActiveDevices {
			c.Devices = append(c.Devices, Device{
				ID: fmt.Sprintf("00000000-0000-4000-8000-%012d", i+10), Account: "family",
				UUID: newUUID(), Disabled: disabled,
			})
		}
		if err := saveConfig(path, c); err != nil {
			t.Fatal(err)
		}
	}
	token := issueToken(c.TokenSecret, &c.Users[0], time.Now())
	fill(false)
	if register(h, token, testDeviceA).Code == 200 {
		t.Fatal("limit of active devices not enforced")
	}
	fill(true)
	responseToken(t, register(h, token, testDeviceA))
}

func TestXrayConfigKeepsItsLayout(t *testing.T) {
	h, _, c := managedSetup(t)
	data, err := os.ReadFile(c.XrayConfig)
	if err != nil {
		t.Fatal(err)
	}
	// Keys in an order no sorted encoder would produce.
	body := strings.TrimSpace(string(data))
	ordered := `{"log":{"loglevel":"warning"},` + body[1:len(body)-1] + `,"outbounds":[{"protocol":"freedom","tag":"direct"}]}`
	if err := os.WriteFile(c.XrayConfig, []byte(ordered), 0o600); err != nil {
		t.Fatal(err)
	}
	responseToken(t, register(h, issueToken(c.TokenSecret, &c.Users[0], time.Now()), testDeviceA))
	out, _ := os.ReadFile(c.XrayConfig)
	text := string(out)
	for _, keys := range [][]string{
		{`"log"`, `"inbounds"`, `"outbounds"`},
		{`"tag": "vless-reality-8443"`, `"port": 8443`, `"protocol": "vless"`, `"settings"`, `"streamSettings"`},
	} {
		last := -1
		for _, k := range keys {
			i := strings.Index(text, k)
			if i < 0 || i < last {
				t.Fatalf("key %s moved or vanished:\n%s", k, text)
			}
			last = i
		}
	}
	if !strings.Contains(text, deviceEmail(testDeviceA)) || !strings.Contains(text, "11111111-2222-3333-4444-555555555555") {
		t.Fatalf("clients not updated as expected:\n%s", text)
	}
}
