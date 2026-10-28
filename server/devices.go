package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

// Device IDs identify an installation, never authenticate it. Tokens remain
// bound to both the account's password hash and this device's VPN credential.
type Device struct {
	ID              string `json:"id"`
	Name            string `json:"name"`
	Account         string `json:"account"`
	UUID            string `json:"uuid"`
	Disabled        bool   `json:"disabled,omitempty"`
	CreatedAt       int64  `json:"created_at"`
	LegacyTokenHash string `json:"legacy_token_hash,omitempty"`
}

var errDeviceRejected = errors.New("device access revoked")

func (c *Config) device(id string) *Device {
	for i := range c.Devices {
		if c.Devices[i].ID == id {
			return &c.Devices[i]
		}
	}
	return nil
}

// Admin CLI commands and API registration must serialize across processes.
func withConfigLock(path string, fn func(*Config) error) error {
	f, err := os.OpenFile(path+".lock", os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return err
	}
	defer f.Close()
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX); err != nil {
		return err
	}
	defer syscall.Flock(int(f.Fd()), syscall.LOCK_UN)
	c, err := loadConfig(path)
	if err != nil {
		return err
	}
	return fn(c)
}

func (c *Config) activeDevices() int {
	n := 0
	for _, d := range c.Devices {
		if !d.Disabled {
			n++
		}
	}
	return n
}

func validDeviceID(id string) bool {
	if len(id) != 36 {
		return false
	}
	for i, ch := range id {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			if ch != '-' {
				return false
			}
		} else if !strings.ContainsRune("0123456789abcdef", ch) {
			return false
		}
	}
	return true
}

func newUUID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	h := hex.EncodeToString(b)
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}

func tokenHash(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func ensureDevice(path string, c *Config, account, id, name, legacyToken string) (*Device, error) {
	if !validDeviceID(id) || len(name) > 160 {
		return nil, errors.New("invalid device")
	}
	if d := c.device(id); d != nil {
		if d.Account != account || d.Disabled {
			return nil, errDeviceRejected
		}
		// A consumed legacy token may authenticate only its original installation.
		if legacyToken != "" && d.LegacyTokenHash != tokenHash(legacyToken) {
			return nil, errDeviceRejected
		}
		return d, nil
	}
	if len(c.Devices) >= 100 {
		return nil, errors.New("device limit reached")
	}
	if legacyToken != "" {
		for _, d := range c.Devices {
			if d.LegacyTokenHash == tokenHash(legacyToken) {
				return nil, errDeviceRejected
			}
		}
	}
	in, err := readInbound(c)
	if err != nil {
		return nil, err
	}
	d := Device{ID: id, Name: name, Account: account, UUID: newUUID(), CreatedAt: time.Now().Unix()}
	if legacyToken != "" {
		d.LegacyTokenHash = tokenHash(legacyToken)
	}
	c.Devices = append(c.Devices, d)
	if err := commitDeviceChange(path, c, d.ID, in.flow()); err != nil {
		return nil, err
	}
	return &c.Devices[len(c.Devices)-1], nil
}

// Modify only the selected inbound's managed clients, preserving unrelated
// inbounds, settings, Reality keys and existing legacy clients byte-for-value.
func deviceXrayConfig(c *Config, data []byte, id, flow string) ([]byte, error) {
	var root map[string]json.RawMessage
	if err := json.Unmarshal(data, &root); err != nil {
		return nil, err
	}
	var inbounds []map[string]json.RawMessage
	if err := json.Unmarshal(root["inbounds"], &inbounds); err != nil {
		return nil, err
	}
	for _, in := range inbounds {
		var protocol, tag string
		json.Unmarshal(in["protocol"], &protocol)
		json.Unmarshal(in["tag"], &tag)
		var stream struct {
			Security string `json:"security"`
		}
		json.Unmarshal(in["streamSettings"], &stream)
		if protocol != "vless" || stream.Security != "reality" || (c.InboundTag != "" && tag != c.InboundTag) {
			continue
		}
		var settings map[string]json.RawMessage
		if err := json.Unmarshal(in["settings"], &settings); err != nil {
			return nil, err
		}
		var clients []map[string]json.RawMessage
		if err := json.Unmarshal(settings["clients"], &clients); err != nil {
			return nil, err
		}
		d := c.device(id)
		if d == nil {
			return nil, errors.New("unknown device")
		}
		email := deviceEmail(id)
		next := make([]map[string]json.RawMessage, 0, len(clients)+1)
		for _, cl := range clients {
			var e string
			json.Unmarshal(cl["email"], &e)
			if e != email {
				next = append(next, cl)
			}
		}
		if !d.Disabled {
			raw, _ := json.Marshal(map[string]string{"id": d.UUID, "email": email, "flow": flow})
			var cl map[string]json.RawMessage
			json.Unmarshal(raw, &cl)
			next = append(next, cl)
		}
		settings["clients"], _ = json.Marshal(next)
		in["settings"], _ = json.Marshal(settings)
		root["inbounds"], _ = json.Marshal(inbounds)
		return json.MarshalIndent(root, "", "  ")
	}
	return nil, errors.New("no matching inbound")
}

func atomicWrite(path string, data []byte, mode os.FileMode) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".vpndeck-*")
	if err != nil {
		return err
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	if err = f.Chmod(mode); err == nil {
		_, err = f.Write(data)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(tmp, path)
}

func restartXray(c *Config) error {
	if len(c.XrayRestartCommand) == 0 {
		return errors.New("xray_restart_command is required for device management")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, c.XrayRestartCommand[0], c.XrayRestartCommand[1:]...)
	// Never propagate daemon output (which could contain configuration secrets).
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("xray restart failed: %w", err)
	}
	return nil
}

func commitDeviceChange(path string, c *Config, id, flow string) error {
	before, err := os.ReadFile(c.XrayConfig)
	if err != nil {
		return err
	}
	next, err := deviceXrayConfig(c, before, id, flow)
	if err != nil {
		return err
	}
	info, err := os.Stat(c.XrayConfig)
	if err != nil {
		return err
	}
	if err = atomicWrite(c.XrayConfig, next, info.Mode().Perm()); err != nil {
		return err
	}
	if err = restartXray(c); err == nil {
		err = saveConfig(path, c)
	}
	if err != nil {
		if rollback := atomicWrite(c.XrayConfig, before, info.Mode().Perm()); rollback != nil {
			return fmt.Errorf("%w; rollback failed: %v", err, rollback)
		}
		if rollback := restartXray(c); rollback != nil {
			return fmt.Errorf("%w; restart after rollback failed: %v", err, rollback)
		}
		return err
	}
	return nil
}

func cmdDevices(path string, args []string) error {
	return withConfigLock(path, func(c *Config) error {
		if len(args) == 0 {
			fmt.Println("ID\tACCOUNT\tNAME\tSTATUS")
			for _, d := range c.Devices {
				status := "active"
				if d.Disabled {
					status = "revoked"
				}
				fmt.Printf("%s\t%s\t%q\t%s\n", d.ID, d.Account, d.Name, status)
			}
			return nil
		}
		if len(args) == 2 && args[0] == "enable" {
			c.ManageDevices = true
			c.XrayRestartCommand = []string{"docker", "restart", args[1]}
			return saveConfig(path, c)
		}
		if len(args) == 2 && args[0] == "revoke" {
			d := c.device(args[1])
			if d == nil {
				return errors.New("unknown device")
			}
			if d.Disabled {
				return nil
			}
			d.Disabled = true
			if err := commitDeviceChange(path, c, d.ID, ""); err != nil {
				return err
			}
			fmt.Println("device revoked; its VPN credential has been removed from Xray")
			return nil
		}
		return errors.New("usage: devices | devices enable <xray-container> | devices revoke <device-id>")
	})
}
