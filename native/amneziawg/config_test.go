package main

import (
	"strings"
	"testing"
)

const validConfig = `[Interface]
PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
Address = 10.0.0.2/32, fd00::2/128
DNS = 1.1.1.1, 2606:4700:4700::1111
Jc = 4
Jmin = 40
Jmax = 70
S1 = 0
H1 = 123456
[Peer]
PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
Endpoint = 127.0.0.1:51820
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
`

func TestPreservesAWGAndIPv6(t *testing.T) {
	cfg, e := parseConfig(validConfig)
	if e != nil {
		t.Fatal(e)
	}
	if len(cfg.addresses) != 2 || len(cfg.dns) != 2 || !strings.Contains(cfg.ipc, "jc=4\n") || !strings.Contains(cfg.ipc, "allowed_ip=::/0\n") {
		t.Fatal("configuration lost")
	}
}
func TestRejectsHooksAndMalformedKeys(t *testing.T) {
	for _, raw := range []string{"", strings.Replace(validConfig, "Jc = 4", "PostUp = touch /tmp/unsafe", 1), strings.Replace(validConfig, "PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "PrivateKey = invalid", 1), strings.Replace(validConfig, "Endpoint = 127.0.0.1:51820", "Endpoint = 127.0.0.1:99999", 1)} {
		if _, e := parseConfig(raw); e == nil {
			t.Fatal("invalid configuration accepted")
		}
	}
}
func TestPeerValidation(t *testing.T) {
	raw := validConfig + "[Peer]\nPublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\n"
	if _, e := parseConfig(raw); e == nil {
		t.Fatal("incomplete second peer accepted")
	}
}
