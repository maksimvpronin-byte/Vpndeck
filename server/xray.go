package main

import (
	"crypto/ecdh"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
)

// VlessServer is what a client needs to dial the VLESS+Reality inbound.
// The JSON shape is the wire contract with the Android app — change both together.
type VlessServer struct {
	Tag         string `json:"tag"`
	Host        string `json:"host"`
	Port        int    `json:"port"`
	UUID        string `json:"uuid"`
	Flow        string `json:"flow,omitempty"`
	ServerName  string `json:"server_name"`
	PublicKey   string `json:"public_key"`
	ShortID     string `json:"short_id"`
	Fingerprint string `json:"fingerprint"`
}

type xrayConfig struct {
	Inbounds []struct {
		Tag      string `json:"tag"`
		Port     any    `json:"port"`
		Protocol string `json:"protocol"`
		Settings struct {
			Clients []struct {
				ID    string `json:"id"`
				Flow  string `json:"flow"`
				Email string `json:"email"`
			} `json:"clients"`
		} `json:"settings"`
		StreamSettings struct {
			Security        string `json:"security"`
			RealitySettings struct {
				PrivateKey  string   `json:"privateKey"`
				ServerNames []string `json:"serverNames"`
				ShortIds    []string `json:"shortIds"`
			} `json:"realitySettings"`
		} `json:"streamSettings"`
	} `json:"inbounds"`
}

// readVlessServer derives the client-side parameters straight from the live
// Xray config, so the Xray file stays the single source of truth: rotate keys
// there, restart Xray, and every phone picks the change up on its next refresh.
func readVlessServer(c *Config, userName string) (*VlessServer, error) {
	data, err := os.ReadFile(c.XrayConfig)
	if err != nil {
		return nil, err
	}
	var xc xrayConfig
	if err := json.Unmarshal(data, &xc); err != nil {
		return nil, fmt.Errorf("parse xray config: %w", err)
	}
	for _, in := range xc.Inbounds {
		if in.Protocol != "vless" || in.StreamSettings.Security != "reality" {
			continue
		}
		if c.InboundTag != "" && in.Tag != c.InboundTag {
			continue
		}
		rs := in.StreamSettings.RealitySettings
		if len(in.Settings.Clients) == 0 || len(rs.ServerNames) == 0 {
			return nil, fmt.Errorf("inbound %q has no clients or serverNames", in.Tag)
		}
		port, err := parsePort(in.Port)
		if err != nil {
			return nil, fmt.Errorf("inbound %q: %w", in.Tag, err)
		}
		pub, err := realityPublicKey(rs.PrivateKey)
		if err != nil {
			return nil, fmt.Errorf("inbound %q: %w", in.Tag, err)
		}
		// Prefer an Xray client whose email matches the account name; this
		// lets each account get its own UUID later without a format change.
		client := in.Settings.Clients[0]
		for _, cl := range in.Settings.Clients {
			if cl.Email != "" && strings.EqualFold(cl.Email, userName) {
				client = cl
				break
			}
		}
		shortID := ""
		if len(rs.ShortIds) > 0 {
			shortID = rs.ShortIds[0]
		}
		return &VlessServer{
			Tag:         in.Tag,
			Host:        c.PublicHost,
			Port:        port,
			UUID:        client.ID,
			Flow:        client.Flow,
			ServerName:  rs.ServerNames[0],
			PublicKey:   pub,
			ShortID:     shortID,
			Fingerprint: c.Fingerprint,
		}, nil
	}
	return nil, errors.New("no matching VLESS+Reality inbound in xray config")
}

func parsePort(v any) (int, error) {
	switch p := v.(type) {
	case float64:
		return int(p), nil
	case string:
		var n int
		if _, err := fmt.Sscanf(p, "%d", &n); err == nil && n > 0 {
			return n, nil
		}
	}
	return 0, fmt.Errorf("unsupported port value %v", v)
}

// realityPublicKey turns Xray's X25519 private key (raw URL-safe base64) into
// the public key clients put in reality.public_key.
func realityPublicKey(private string) (string, error) {
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(private, "="))
	if err != nil {
		return "", fmt.Errorf("decode reality private key: %w", err)
	}
	key, err := ecdh.X25519().NewPrivateKey(raw)
	if err != nil {
		return "", fmt.Errorf("reality private key: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes()), nil
}
