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
	// Keep the hash as a tombstone: removing it would turn the old token back
	// into a shared session. Restoring the device never restores this token.
	LegacyTokenRevoked bool `json:"legacy_token_revoked,omitempty"`
}

// defaultXrayDir is where setup-xray.sh puts Xray; the systemd unit allows writing there.
const defaultXrayDir = "/opt/xray-reality-8443"

var (
	// errDeviceRejected: the request can't be tied to this device (a token of
	// another installation, a consumed migration token, credentials changed meanwhile).
	errDeviceRejected = errors.New("device rejected")
	// errDeviceRevoked: the admin revoked this installation with `devices revoke`.
	errDeviceRevoked = errors.New("device access revoked")
	// errDeviceOtherAccount: this installation is registered to a different account.
	errDeviceOtherAccount = errors.New("device registered to another account")
)

const maxActiveDevices = 100

// xrayRestartTimeout is generous for `docker restart` (10 s stop grace + start).
const xrayRestartTimeout = 25 * time.Second

// deviceErrorCode tells the app why a correct password or a valid token was
// still refused. The status stays 401 in every case, so older apps behave as before.
func deviceErrorCode(err error, fallback string) string {
	switch {
	case errors.Is(err, errDeviceRevoked):
		return "device_revoked"
	case errors.Is(err, errDeviceOtherAccount):
		return "device_other_account"
	}
	return fallback
}

func isDeviceRefusal(err error) bool {
	return errors.Is(err, errDeviceRejected) || errors.Is(err, errDeviceRevoked) || errors.Is(err, errDeviceOtherAccount)
}

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
		if d.Account != account {
			return nil, errDeviceOtherAccount
		}
		if d.Disabled {
			return nil, errDeviceRevoked
		}
		if legacyToken != "" && d.LegacyTokenRevoked {
			return nil, errDeviceRevoked
		}
		// A consumed legacy token may authenticate only its original installation.
		if legacyToken != "" && d.LegacyTokenHash != tokenHash(legacyToken) {
			return nil, errDeviceRejected
		}
		return d, nil
	}
	// Revoked records stay (they keep consumed migration tokens dead) but don't count.
	if c.activeDevices() >= maxActiveDevices {
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
// inbounds, settings, Reality keys, existing legacy clients and the order of
// keys the admin wrote them in.
func deviceXrayConfig(c *Config, data []byte, id, flow string) ([]byte, error) {
	d := c.device(id)
	if d == nil {
		return nil, errors.New("unknown device")
	}
	var root jsonObject
	if err := json.Unmarshal(data, &root); err != nil {
		return nil, err
	}
	var inbounds []jsonObject
	if err := json.Unmarshal(root.get("inbounds"), &inbounds); err != nil {
		return nil, err
	}
	for i := range inbounds {
		in := &inbounds[i]
		var protocol, tag string
		json.Unmarshal(in.get("protocol"), &protocol)
		json.Unmarshal(in.get("tag"), &tag)
		var stream struct {
			Security string `json:"security"`
		}
		json.Unmarshal(in.get("streamSettings"), &stream)
		if protocol != "vless" || stream.Security != "reality" || (c.InboundTag != "" && tag != c.InboundTag) {
			continue
		}
		var settings jsonObject
		if err := json.Unmarshal(in.get("settings"), &settings); err != nil {
			return nil, err
		}
		var clients []json.RawMessage
		if err := json.Unmarshal(settings.get("clients"), &clients); err != nil {
			return nil, err
		}
		email := deviceEmail(id)
		next := make([]json.RawMessage, 0, len(clients)+1)
		for _, cl := range clients {
			var other struct {
				Email string `json:"email"`
			}
			json.Unmarshal(cl, &other)
			if other.Email != email {
				next = append(next, cl)
			}
		}
		if !d.Disabled {
			cl, err := json.Marshal(xrayClient{ID: d.UUID, Flow: flow, Email: email})
			if err != nil {
				return nil, err
			}
			next = append(next, cl)
		}
		if err := settings.set("clients", next); err != nil {
			return nil, err
		}
		if err := in.set("settings", settings); err != nil {
			return nil, err
		}
		if err := root.set("inbounds", inbounds); err != nil {
			return nil, err
		}
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
	// Fits twice (restart, then restart after rollback) into the API's write timeout.
	ctx, cancel := context.WithTimeout(context.Background(), xrayRestartTimeout)
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
			fmt.Println("ID\tACCOUNT\tNAME\tCREATED\tSTATUS")
			for _, d := range c.Devices {
				status := "active"
				if d.Disabled {
					status = "revoked"
				}
				created := time.Unix(d.CreatedAt, 0).Format("2006-01-02")
				fmt.Printf("%s\t%s\t%q\t%s\t%s\n", d.ID, d.Account, d.Name, created, status)
			}
			return nil
		}
		if len(args) == 2 && args[0] == "enable" {
			c.ManageDevices = true
			c.XrayRestartCommand = []string{"docker", "restart", args[1]}
			if err := saveConfig(path, c); err != nil {
				return err
			}
			if dir := filepath.Dir(c.XrayConfig); dir != defaultXrayDir {
				fmt.Printf("note: the systemd unit may write only to /etc/vpndeck and %s; allow %s with\n"+
					"  systemctl edit vpndeck-server   ->   [Service]  ReadWritePaths=%s\n", defaultXrayDir, dir, dir)
			}
			return nil
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
			d.LegacyTokenRevoked = d.LegacyTokenHash != ""
			if err := commitDeviceChange(path, c, d.ID, ""); err != nil {
				return err
			}
			fmt.Println("device revoked; its VPN credential has been removed from Xray")
			return nil
		}
		if len(args) == 2 && args[0] == "restore" {
			d := c.device(args[1])
			if d == nil {
				return errors.New("unknown device")
			}
			if !d.Disabled {
				return nil
			}
			in, err := readInbound(c)
			if err != nil {
				return err
			}
			if c.activeDevices() >= maxActiveDevices {
				return errors.New("device limit reached")
			}
			d.Disabled = false
			// Also covers devices revoked before legacy-token tombstones existed.
			d.LegacyTokenRevoked = d.LegacyTokenHash != ""
			// A fresh credential: parameters copied before the revocation stay dead.
			d.UUID = newUUID()
			if err := commitDeviceChange(path, c, d.ID, in.flow()); err != nil {
				return err
			}
			fmt.Println("device restored with a new VPN credential; sign in on that phone again")
			return nil
		}
		return errors.New("usage: devices | devices enable <xray-container> | devices revoke <device-id> | devices restore <device-id>")
	})
}
