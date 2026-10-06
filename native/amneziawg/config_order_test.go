package main

import (
	"strings"
	"testing"
)

func TestINIOrderPlacesIdentityBeforePeerOptions(t *testing.T) {
	key := "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
	raw := "[Interface]\nAddress=10.0.0.2/32\nPrivateKey=" + key + "\n[Peer]\nEndpoint=127.0.0.1:51820\nAllowedIPs=0.0.0.0/0\nPublicKey=" + key
	cfg, e := parseConfig(raw)
	if e != nil {
		t.Fatal(e)
	}
	if strings.Index(cfg.ipc, "public_key=") > strings.Index(cfg.ipc, "endpoint=") {
		t.Fatal("peer options preceded identity")
	}
	if _, e = parseConfig(raw + "\nPublicKey=" + key); e == nil {
		t.Fatal("duplicate identity accepted")
	}
}
