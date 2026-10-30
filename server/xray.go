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

// deviceEmailPrefix marks the Xray clients vpndeck-server manages itself, one per device.
const deviceEmailPrefix = "vpndeck-device-"

func deviceEmail(id string) string { return deviceEmailPrefix + id }

type xrayClient struct {
	ID    string `json:"id"`
	Flow  string `json:"flow"`
	Email string `json:"email"`
}

type xrayConfig struct {
	Inbounds []struct {
		Tag      string `json:"tag"`
		Port     any    `json:"port"`
		Protocol string `json:"protocol"`
		Settings struct {
			Clients []xrayClient `json:"clients"`
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

// xrayInbound is the VLESS+Reality inbound handed out to clients: the shared
// connection parameters plus the credentials it accepts.
type xrayInbound struct {
	server  VlessServer // UUID and Flow are per credential, see with()
	clients []xrayClient
}

// readInbound derives the client-side parameters straight from the live
// Xray config, so the Xray file stays the single source of truth: rotate keys
// there, restart Xray, and every phone picks the change up on its next refresh.
func readInbound(c *Config) (*xrayInbound, error) {
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
		if len(rs.ServerNames) == 0 {
			return nil, fmt.Errorf("inbound %q has no serverNames", in.Tag)
		}
		port, err := parsePort(in.Port)
		if err != nil {
			return nil, fmt.Errorf("inbound %q: %w", in.Tag, err)
		}
		pub, err := realityPublicKey(rs.PrivateKey)
		if err != nil {
			return nil, fmt.Errorf("inbound %q: %w", in.Tag, err)
		}
		shortID := ""
		if len(rs.ShortIds) > 0 {
			shortID = rs.ShortIds[0]
		}
		return &xrayInbound{
			server: VlessServer{
				Tag:         in.Tag,
				Host:        c.PublicHost,
				Port:        port,
				ServerName:  rs.ServerNames[0],
				PublicKey:   pub,
				ShortID:     shortID,
				Fingerprint: c.Fingerprint,
			},
			clients: in.Settings.Clients,
		}, nil
	}
	return nil, errors.New("no matching VLESS+Reality inbound in xray config")
}

// sharedClient is the credential for sessions without a device of their own:
// the Xray client whose email matches the account, otherwise the first one
// vpndeck-server does not manage. A device credential is never shared, wherever
// it sits in the list — otherwise removing the shared client would hand one
// phone's UUID to every old app.
func (in *xrayInbound) sharedClient(account string) (xrayClient, bool) {
	var first *xrayClient
	for i := range in.clients {
		cl := &in.clients[i]
		if strings.HasPrefix(cl.Email, deviceEmailPrefix) {
			continue
		}
		if cl.Email != "" && strings.EqualFold(cl.Email, account) {
			return *cl, true
		}
		if first == nil {
			first = cl
		}
	}
	if first == nil {
		return xrayClient{}, false
	}
	return *first, true
}

// flow is what a new device credential gets: the same as the existing clients.
// An empty inbound (e.g. after revoking its last device) starts with plain VLESS.
func (in *xrayInbound) flow() string {
	if cl, ok := in.sharedClient(""); ok {
		return cl.Flow
	}
	if len(in.clients) > 0 {
		return in.clients[0].Flow
	}
	return ""
}

func (in *xrayInbound) with(cl xrayClient) *VlessServer {
	s := in.server
	s.UUID, s.Flow = cl.ID, cl.Flow
	return &s
}

// readVlessServer returns the shared credential for sessions without a device.
func readVlessServer(c *Config, account string) (*VlessServer, error) {
	in, err := readInbound(c)
	if err != nil {
		return nil, err
	}
	cl, ok := in.sharedClient(account)
	if !ok {
		return nil, fmt.Errorf("inbound %q has only per-device clients, nothing to give a session without a device", in.server.Tag)
	}
	return in.with(cl), nil
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

// readManagedServer is strict: a device whose credential was removed from Xray
// gets an error, never the shared fallback.
func readManagedServer(c *Config, d *Device) (*VlessServer, error) {
	in, err := readInbound(c)
	if err != nil {
		return nil, err
	}
	for _, cl := range in.clients {
		if cl.Email == deviceEmail(d.ID) && cl.ID == d.UUID {
			return in.with(cl), nil
		}
	}
	return nil, errors.New("managed device credential missing from xray")
}
