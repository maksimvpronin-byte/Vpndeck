package main

import (
	"crypto/hmac"
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"
)

const pbkdf2Iterations = 600_000

// hashPassword produces "pbkdf2-sha256$<iter>$<salt>$<hash>".
func hashPassword(password string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	sum, err := pbkdf2.Key(sha256.New, password, salt, pbkdf2Iterations, 32)
	if err != nil {
		return "", err
	}
	enc := base64.RawStdEncoding
	return fmt.Sprintf("pbkdf2-sha256$%d$%s$%s", pbkdf2Iterations, enc.EncodeToString(salt), enc.EncodeToString(sum)), nil
}

func checkPassword(encoded, password string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 4 || parts[0] != "pbkdf2-sha256" {
		return false
	}
	iter, err := strconv.Atoi(parts[1])
	if err != nil || iter < 1 {
		return false
	}
	enc := base64.RawStdEncoding
	salt, err1 := enc.DecodeString(parts[2])
	want, err2 := enc.DecodeString(parts[3])
	if err1 != nil || err2 != nil {
		return false
	}
	got, err := pbkdf2.Key(sha256.New, password, salt, iter, len(want))
	return err == nil && subtle.ConstantTimeCompare(got, want) == 1
}

// Tokens are stateless: "v1.<user>.<issued>.<mac>". The MAC covers the
// user's current password hash, so changing the password logs out every
// device; rotating token_secret logs out everyone.
func issueToken(secret string, u *User, now time.Time) string {
	user := base64.RawURLEncoding.EncodeToString([]byte(u.Name))
	issued := strconv.FormatInt(now.Unix(), 10)
	return "v1." + user + "." + issued + "." + tokenMAC(secret, user, issued, u.PasswordHash)
}

func verifyToken(c *Config, token string) (*User, error) {
	u, _, err := authenticateToken(c, token)
	return u, err
}

func authenticateToken(c *Config, token string) (*User, *Device, error) {
	if strings.HasPrefix(token, "v2.") {
		return verifyDeviceToken(c, token)
	}
	u, err := verifyLegacyToken(c, token)
	if err != nil {
		return nil, nil, err
	}
	for i := range c.Devices {
		d := &c.Devices[i]
		if d.LegacyTokenHash != "" && d.LegacyTokenHash == tokenHash(token) {
			if d.Disabled || d.Account != u.Name {
				return nil, nil, errDeviceRejected
			}
			return u, d, nil
		}
	}
	return u, nil, nil
}

func verifyLegacyToken(c *Config, token string) (*User, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 4 || parts[0] != "v1" {
		return nil, errors.New("malformed token")
	}
	name, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, errors.New("malformed token")
	}
	u := c.user(string(name))
	if u == nil || u.Disabled {
		return nil, errors.New("unknown user")
	}
	want := tokenMAC(c.TokenSecret, parts[1], parts[2], u.PasswordHash)
	if !hmac.Equal([]byte(want), []byte(parts[3])) {
		return nil, errors.New("bad token")
	}
	return u, nil
}

func tokenMAC(secret, user, issued, passwordHash string) string {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte("v1|" + user + "|" + issued + "|" + passwordHash))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

// limiter locks an IP out after repeated failed logins.
type limiter struct {
	mu    sync.Mutex
	state map[string]*attempts
}

type attempts struct {
	fails       int
	first       time.Time
	lockedUntil time.Time
}

const (
	maxFails    = 5
	failWindow  = 10 * time.Minute
	lockoutTime = 15 * time.Minute
)

func newLimiter() *limiter { return &limiter{state: map[string]*attempts{}} }

func (l *limiter) locked(ip string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	a := l.state[ip]
	return a != nil && now.Before(a.lockedUntil)
}

func (l *limiter) fail(ip string, now time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	a := l.state[ip]
	if a == nil || now.Sub(a.first) > failWindow {
		a = &attempts{first: now}
		l.state[ip] = a
	}
	a.fails++
	if a.fails >= maxFails {
		a.lockedUntil = now.Add(lockoutTime)
		a.fails = 0
		a.first = now
	}
	// Opportunistic cleanup so the map cannot grow without bound.
	if len(l.state) > 10_000 {
		for k, v := range l.state {
			if now.After(v.lockedUntil) && now.Sub(v.first) > failWindow {
				delete(l.state, k)
			}
		}
	}
}

func (l *limiter) success(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.state, ip)
}

func issueDeviceToken(c *Config, u *User, d *Device, now time.Time) string {
	user := base64.RawURLEncoding.EncodeToString([]byte(u.Name))
	issued := strconv.FormatInt(now.Unix(), 10)
	return "v2." + user + "." + d.ID + "." + issued + "." + deviceMAC(c.TokenSecret, user, d, issued, u.PasswordHash)
}

func deviceMAC(secret, user string, d *Device, issued, hash string) string {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte("v2|" + user + "|" + d.ID + "|" + issued + "|" + hash + "|" + d.UUID))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

func verifyDeviceToken(c *Config, token string) (*User, *Device, error) {
	p := strings.Split(token, ".")
	if len(p) != 5 {
		return nil, nil, errors.New("malformed token")
	}
	raw, err := base64.RawURLEncoding.DecodeString(p[1])
	if err != nil {
		return nil, nil, err
	}
	u := c.user(string(raw))
	d := c.device(p[2])
	if u == nil || u.Disabled || d == nil || d.Disabled || d.Account != u.Name {
		return nil, nil, errDeviceRejected
	}
	if !hmac.Equal([]byte(p[4]), []byte(deviceMAC(c.TokenSecret, p[1], d, p[3], u.PasswordHash))) {
		return nil, nil, errors.New("bad token")
	}
	return u, d, nil
}
