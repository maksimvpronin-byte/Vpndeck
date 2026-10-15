package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// Config is the server's own settings file (default /etc/vpndeck/server.json).
// It is re-read on every request, so `vpndeck-server passwd` takes effect
// without a restart.
type Config struct {
	Listen      string `json:"listen"`       // e.g. ":8448"
	PublicHost  string `json:"public_host"`  // address clients dial for the VPN itself
	XrayConfig  string `json:"xray_config"`  // path to the Xray config holding the Reality inbound
	InboundTag  string `json:"inbound_tag"`  // which inbound to hand out; empty = first VLESS+Reality one
	Fingerprint string `json:"fingerprint"`  // uTLS fingerprint the client should mimic
	TokenSecret string `json:"token_secret"` // HMAC key for session tokens
	TLSCert     string `json:"tls_cert"`
	TLSKey      string `json:"tls_key"`
	Users       []User `json:"users"`
}

type User struct {
	Name         string `json:"name"`
	PasswordHash string `json:"password_hash"`
	Disabled     bool   `json:"disabled,omitempty"`
}

func loadConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var c Config
	if err := json.Unmarshal(data, &c); err != nil {
		return nil, fmt.Errorf("parse %s: %w", path, err)
	}
	if c.Listen == "" {
		c.Listen = ":8448"
	}
	if c.Fingerprint == "" {
		c.Fingerprint = "chrome"
	}
	dir := filepath.Dir(path)
	if c.TLSCert == "" {
		c.TLSCert = filepath.Join(dir, "tls.crt")
	}
	if c.TLSKey == "" {
		c.TLSKey = filepath.Join(dir, "tls.key")
	}
	if len(c.TokenSecret) < 32 {
		return nil, errors.New("token_secret is missing or too short")
	}
	if c.PublicHost == "" {
		return nil, errors.New("public_host is required")
	}
	return &c, nil
}

func saveConfig(path string, c *Config) error {
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, append(data, '\n'), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func (c *Config) user(name string) *User {
	for i := range c.Users {
		if c.Users[i].Name == name {
			return &c.Users[i]
		}
	}
	return nil
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}
