package main

import (
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestLimiterConcurrentReservations(t *testing.T) {
	l := newLimiter()
	now := time.Now()
	var admitted atomic.Int32
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			if l.attempt("2001:db8::1", now) {
				admitted.Add(1)
			}
		}()
	}
	close(start)
	wg.Wait()
	if admitted.Load() != maxFails {
		t.Fatalf("admitted %d, want %d", admitted.Load(), maxFails)
	}
	if l.attempt("2001:db8::abcd", now.Add(time.Minute)) {
		t.Fatal("same /64 bypassed lockout")
	}
	if !l.attempt("2001:db8:0:1::1", now) {
		t.Fatal("different /64 blocked")
	}
	if !l.attempt("2001:db8::1", now.Add(lockoutTime)) {
		t.Fatal("lockout did not expire")
	}
}

func TestLimiterSuccessAndNormalization(t *testing.T) {
	l := newLimiter()
	now := time.Now()
	l.attempt("::ffff:192.0.2.1", now)
	l.attempt("192.0.2.1", now.Add(time.Second))
	l.success("192.0.2.1", now)
	if l.state["192.0.2.1"].fails != 2 {
		t.Fatal("old success erased newer attempts")
	}
	l.success("::ffff:192.0.2.1", now.Add(time.Second))
	if len(l.state) != 0 {
		t.Fatal("success did not reset normalized key")
	}
}

func TestLoginPasswordCapacity(t *testing.T) {
	h, _, _ := setup(t)
	for i := 0; i < cap(passwordChecks); i++ {
		passwordChecks <- struct{}{}
	}
	defer func() {
		for len(passwordChecks) > 0 {
			<-passwordChecks
		}
	}()
	if rec := doLogin(h, "family", "correct horse"); rec.Code != http.StatusTooManyRequests {
		t.Fatalf("got %d", rec.Code)
	}
}

func TestDisabledLoginRejected(t *testing.T) {
	h, path, _ := setup(t)
	c, err := loadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	c.Users[0].Disabled = true
	if err := saveConfig(path, c); err != nil {
		t.Fatal(err)
	}
	if rec := doLogin(h, "family", "correct horse"); rec.Code != http.StatusUnauthorized {
		t.Fatalf("got %d", rec.Code)
	}
}
