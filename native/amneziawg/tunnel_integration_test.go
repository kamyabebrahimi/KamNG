package main

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"golang.org/x/crypto/curve25519"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestAmneziaWGUserspaceTunnelCarriesTCP(t *testing.T) {
	privateClient := make([]byte, 32)
	privateServer := make([]byte, 32)
	if _, e := rand.Read(privateClient); e != nil {
		t.Fatal(e)
	}
	if _, e := rand.Read(privateServer); e != nil {
		t.Fatal(e)
	}
	publicClient, e := curve25519.X25519(privateClient, curve25519.Basepoint)
	if e != nil {
		t.Fatal(e)
	}
	publicServer, e := curve25519.X25519(privateServer, curve25519.Basepoint)
	if e != nil {
		t.Fatal(e)
	}
	reservation, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	port := reservation.LocalAddr().(*net.UDPAddr).Port
	reservation.Close()
	serverTun, serverNet, e := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.99.0.1")}, nil, 1280)
	if e != nil {
		t.Fatal(e)
	}
	server := device.NewDevice(serverTun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer server.Close()
	options := "jc=2\njmin=40\njmax=60\ns1=0\ns2=0\nh1=12345\nh2=23456\nh3=34567\nh4=45678\n"
	ipc := fmt.Sprintf("private_key=%s\nlisten_port=%d\n%sreplace_peers=true\npublic_key=%s\nallowed_ip=10.99.0.2/32\n", hex.EncodeToString(privateServer), port, options, hex.EncodeToString(publicClient))
	if e = server.IpcSet(ipc); e != nil {
		t.Fatal(e)
	}
	if e = server.Up(); e != nil {
		t.Fatal(e)
	}
	raw := fmt.Sprintf("[Interface]\nAddress=10.99.0.2/32\nPrivateKey=%s\n%s[Peer]\nPublicKey=%s\nEndpoint=127.0.0.1:%d\nAllowedIPs=0.0.0.0/0\n", base64.StdEncoding.EncodeToString(privateClient), options, base64.StdEncoding.EncodeToString(publicServer), port)
	cfg, e := parseConfig(raw)
	if e != nil {
		t.Fatal(e)
	}
	clientTun, clientNet, e := netstack.CreateNetTUN(cfg.addresses, cfg.dns, cfg.mtu)
	if e != nil {
		t.Fatal(e)
	}
	client := device.NewDevice(clientTun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer client.Close()
	if e = client.IpcSet(cfg.ipc); e != nil {
		t.Fatal(e)
	}
	if e = client.Up(); e != nil {
		t.Fatal(e)
	}
	listener, e := serverNet.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.99.0.1"), Port: 18080})
	if e != nil {
		t.Fatal(e)
	}
	defer listener.Close()
	go func() {
		c, e := listener.Accept()
		if e == nil {
			defer c.Close()
			io.Copy(c, c)
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	stream, e := clientNet.DialContext(ctx, "tcp", "10.99.0.1:18080")
	if e != nil {
		t.Fatal(e)
	}
	defer stream.Close()
	stream.SetDeadline(time.Now().Add(5 * time.Second))
	if _, e = stream.Write([]byte("encrypted tunnel")); e != nil {
		t.Fatal(e)
	}
	reply := make([]byte, 16)
	if _, e = io.ReadFull(stream, reply); e != nil || string(reply) != "encrypted tunnel" {
		t.Fatalf("tunnel reply %q %v", reply, e)
	}
}
