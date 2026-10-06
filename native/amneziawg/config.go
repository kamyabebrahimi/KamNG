package main

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
)

type tunnelConfig struct {
	addresses, dns []netip.Addr
	mtu            int
	ipc            string
}

// Unknown options, including desktop shell hooks, fail closed.
func parseConfig(raw string) (tunnelConfig, error) {
	normalized, e := orderConfig(raw)
	if e != nil {
		return tunnelConfig{}, e
	}
	raw = normalized
	cfg := tunnelConfig{mtu: 1280}
	var ipc strings.Builder
	section := ""
	peers := 0
	private := false
	public := false
	endpoint := false
	allowed := false
	finish := func() error {
		if peers > 0 && (!public || !endpoint || !allowed) {
			return errors.New("peer requires PublicKey, Endpoint and AllowedIPs")
		}
		return nil
	}
	scan := bufio.NewScanner(strings.NewReader(raw))
	scan.Buffer(make([]byte, 4096), 1<<20)
	for scan.Scan() {
		line := strings.TrimSpace(strings.SplitN(scan.Text(), "#", 2)[0])
		if line == "" || strings.HasPrefix(line, ";") {
			continue
		}
		if strings.HasPrefix(line, "[") {
			if e := finish(); e != nil {
				return cfg, e
			}
			section = strings.ToLower(line)
			if section != "[interface]" && section != "[peer]" {
				return cfg, errors.New("unknown section")
			}
			if section == "[peer]" {
				peers++
				public = false
				endpoint = false
				allowed = false
			}
			continue
		}
		pair := strings.SplitN(line, "=", 2)
		if len(pair) != 2 {
			return cfg, errors.New("invalid configuration line")
		}
		key, value := strings.ToLower(strings.TrimSpace(pair[0])), strings.TrimSpace(pair[1])
		if value == "" {
			return cfg, errors.New("empty configuration value")
		}
		if section == "[interface]" {
			switch key {
			case "address":
				for _, token := range strings.Split(value, ",") {
					p, e := netip.ParsePrefix(strings.TrimSpace(token))
					if e != nil {
						return cfg, errors.New("invalid interface address")
					}
					cfg.addresses = append(cfg.addresses, p.Addr())
				}
			case "dns":
				for _, token := range strings.Split(value, ",") {
					a, e := netip.ParseAddr(strings.TrimSpace(token))
					if e != nil {
						return cfg, errors.New("DNS must contain IP addresses")
					}
					cfg.dns = append(cfg.dns, a)
				}
			case "mtu":
				n, e := strconv.Atoi(value)
				if e != nil || n < 576 || n > 9000 {
					return cfg, errors.New("invalid MTU")
				}
				cfg.mtu = n
			case "privatekey":
				k, e := keyHex(value)
				if e != nil {
					return cfg, e
				}
				fmt.Fprintf(&ipc, "private_key=%s\nreplace_peers=true\n", k)
				private = true
			case "listenport":
				n, e := strconv.Atoi(value)
				if e != nil || n < 0 || n > 65535 {
					return cfg, errors.New("invalid listen port")
				}
				fmt.Fprintf(&ipc, "listen_port=%d\n", n)
			default:
				options := map[string]bool{"jc": true, "jmin": true, "jmax": true, "s1": true, "s2": true, "s3": true, "s4": true, "h1": true, "h2": true, "h3": true, "h4": true, "i1": true, "i2": true, "i3": true, "i4": true, "i5": true, "header_protection_key": true, "content_padding_addition": true, "rekey_after_time": true, "rekey_timeout": true, "reject_after_time": true, "keepalive_timeout": true, "max_handshake_attempts": true, "random_trailers": true, "disable_cookies": true}
				if !options[key] {
					return cfg, errors.New("unsupported interface option")
				}
				fmt.Fprintf(&ipc, "%s=%s\n", key, value)
			}
		} else if section == "[peer]" {
			switch key {
			case "publickey", "presharedkey":
				k, e := keyHex(value)
				if e != nil {
					return cfg, e
				}
				target := "preshared_key"
				if key == "publickey" {
					target = "public_key"
					public = true
				}
				fmt.Fprintf(&ipc, "%s=%s\n", target, k)
			case "endpoint":
				host, port, e := net.SplitHostPort(value)
				if e != nil {
					return cfg, errors.New("invalid endpoint")
				}
				p, e := strconv.Atoi(port)
				if e != nil || p < 1 || p > 65535 {
					return cfg, errors.New("invalid endpoint port")
				}
				if _, e = netip.ParseAddr(host); e != nil {
					ips, e := net.LookupIP(host)
					if e != nil || len(ips) == 0 {
						return cfg, errors.New("endpoint resolution failed")
					}
					host = ips[0].String()
				}
				fmt.Fprintf(&ipc, "endpoint=%s\n", net.JoinHostPort(host, port))
				endpoint = true
			case "allowedips":
				ipc.WriteString("replace_allowed_ips=true\n")
				for _, token := range strings.Split(value, ",") {
					p, e := netip.ParsePrefix(strings.TrimSpace(token))
					if e != nil {
						return cfg, errors.New("invalid AllowedIPs")
					}
					fmt.Fprintf(&ipc, "allowed_ip=%s\n", p.Masked())
				}
				allowed = true
			case "persistentkeepalive":
				n, e := strconv.Atoi(value)
				if e != nil || n < 0 || n > 65535 {
					return cfg, errors.New("invalid keepalive")
				}
				fmt.Fprintf(&ipc, "persistent_keepalive_interval=%d\n", n)
			default:
				return cfg, errors.New("unsupported peer option")
			}
		} else {
			return cfg, errors.New("configuration requires an Interface section")
		}
	}
	if e := scan.Err(); e != nil {
		return cfg, e
	}
	if e := finish(); e != nil {
		return cfg, e
	}
	if !private || peers == 0 || len(cfg.addresses) == 0 {
		return cfg, errors.New("missing interface identity or peer")
	}
	if len(cfg.dns) == 0 {
		cfg.dns = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	cfg.ipc = ipc.String()
	return cfg, nil
}
func keyHex(value string) (string, error) {
	b, e := base64.StdEncoding.DecodeString(value)
	if e != nil || len(b) != 32 {
		return "", errors.New("invalid WireGuard key")
	}
	return hex.EncodeToString(b), nil
}

// UAPI peer options belong to the most recent public_key, regardless of INI field order.
func orderConfig(raw string) (string, error) {
	sections := [][]string{}
	current := -1
	interfaces := 0
	scan := bufio.NewScanner(strings.NewReader(raw))
	scan.Buffer(make([]byte, 4096), 1<<20)
	for scan.Scan() {
		line := strings.TrimSpace(strings.SplitN(scan.Text(), "#", 2)[0])
		if line == "" || strings.HasPrefix(line, ";") {
			continue
		}
		if strings.HasPrefix(line, "[") {
			name := strings.ToLower(line)
			if name == "[interface]" {
				interfaces++
				if interfaces != 1 || len(sections) != 0 {
					return "", errors.New("Interface must precede peers")
				}
			}
			if name != "[interface]" && name != "[peer]" {
				return "", errors.New("unknown section")
			}
			sections = append(sections, []string{line})
			current = len(sections) - 1
		} else {
			if current < 0 {
				return "", errors.New("missing Interface section")
			}
			sections[current] = append(sections[current], line)
		}
	}
	if e := scan.Err(); e != nil {
		return "", e
	}
	var out strings.Builder
	for _, section := range sections {
		out.WriteString(section[0] + "\n")
		identity := "publickey"
		if strings.EqualFold(section[0], "[interface]") {
			identity = "privatekey"
		}
		count := 0
		for _, line := range section[1:] {
			parts := strings.SplitN(line, "=", 2)
			if strings.EqualFold(strings.TrimSpace(parts[0]), identity) {
				count++
				out.WriteString(line + "\n")
			}
		}
		if count > 1 {
			return "", errors.New("duplicate identity key")
		}
		for _, line := range section[1:] {
			parts := strings.SplitN(line, "=", 2)
			if !strings.EqualFold(strings.TrimSpace(parts[0]), identity) {
				out.WriteString(line + "\n")
			}
		}
	}
	return out.String(), nil
}
